package coredevices.pebble.signal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

/** Only opaque IDs and encrypted payloads are stored; no plaintext search index. */
@Entity(tableName = "signal_records")
data class SignalRow(@PrimaryKey val id: String, val payload: String)
@Dao
interface SignalDao {
    @Query("SELECT * FROM signal_records") suspend fun all(): List<SignalRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: SignalRow)
    @Query("DELETE FROM signal_records WHERE id IN (:ids)") suspend fun delete(ids: List<String>)
    @Query("DELETE FROM signal_records") suspend fun clear()
}
@Database(entities = [SignalRow::class], version = 1, exportSchema = false)
abstract class SignalDatabase : RoomDatabase() { abstract fun records(): SignalDao }

class SignalStore(context: Context, private val namespace: String = "signal") : SignalSecrets {
    init { require(namespace.matches(Regex("[a-z0-9-]+"))) }
    private val prefs = context.getSharedPreferences("${namespace}_private", Context.MODE_PRIVATE)
    private val database = Room.databaseBuilder(context, SignalDatabase::class.java, "${namespace}-history.db").build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("${namespace}-station-v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("${namespace}-station-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun encrypt(value: String, identity: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(identity.toByteArray())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    private fun decrypt(value: String, identity: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(identity.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    override suspend fun get(provider: String): String? = prefs.getString("key:$provider", null)?.let { decrypt(it, "key:$provider") }
    override suspend fun put(provider: String, key: String) {
        val editor = prefs.edit()
        if (key.isBlank()) editor.remove("key:$provider") else editor.putString("key:$provider", encrypt(key.trim(), "key:$provider"))
        check(editor.commit())
    }
    fun settings(): SignalSettings = prefs.getString("settings", null)?.let { json.decodeFromString(decrypt(it, "settings")) } ?: SignalSettings()
    fun settings(value: SignalSettings) { check(prefs.edit().putString("settings", encrypt(json.encodeToString(value), "settings")).commit()) }
    suspend fun records(): List<SignalRecord> = database.records().all().map { json.decodeFromString<SignalRecord>(decrypt(it.payload, it.id)) }.sortedByDescending { it.createdAt }
    suspend fun save(record: SignalRecord) = database.records().put(SignalRow(record.id, encrypt(json.encodeToString(record), record.id)))
    suspend fun delete(ids: Set<String>) = database.withTransaction { ids.toList().chunked(500).forEach { database.records().delete(it) } }
    suspend fun clear() = database.records().clear()
    fun close() = database.close()
}
