package coredevices.pebble.signal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "signal_records", indices = [Index("position"), Index(value = ["threadKey", "position"])])
data class SignalRow(@PrimaryKey val id: String, val payload: String, @ColumnInfo(defaultValue = "0") val position: Long = 0, @ColumnInfo(defaultValue = "''") val threadKey: String = "")
@Entity(tableName = "signal_documents", indices = [Index("kind")])
data class SignalDocumentRow(@PrimaryKey val id: String, val kind: String, val payload: String)
@Entity(tableName = "signal_edges", primaryKeys = ["parent", "child"], indices = [Index("child")])
data class SignalEdge(val parent: String, val child: String)

@Dao
interface SignalDao {
    @Query("SELECT * FROM signal_records WHERE position < :before ORDER BY position DESC LIMIT :limit") suspend fun page(before: Long, limit: Int): List<SignalRow>
    @Query("SELECT * FROM signal_records WHERE position > :after ORDER BY position ASC LIMIT :limit") suspend fun after(after: Long, limit: Int): List<SignalRow>
    @Query("SELECT * FROM signal_records WHERE id = :id") suspend fun get(id: String): SignalRow?
    @Query("SELECT * FROM signal_records WHERE threadKey = :thread AND position < :before ORDER BY position DESC LIMIT :limit") suspend fun thread(thread: String, before: Long, limit: Int): List<SignalRow>
    @Query("SELECT id FROM signal_records WHERE threadKey = :thread") suspend fun threadIds(thread: String): List<String>
    @Query("UPDATE signal_records SET threadKey = :thread WHERE id = :id") suspend fun setThreadIndex(id: String, thread: String)
    @Query("SELECT COALESCE(MAX(position),0) FROM signal_records") suspend fun maximum(): Long
    @Query("SELECT COUNT(*) FROM signal_records") suspend fun count(): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: SignalRow)
    @Query("DELETE FROM signal_records WHERE id IN (:ids)") suspend fun delete(ids: List<String>)
    @Query("DELETE FROM signal_records") suspend fun clear()
    @Query("SELECT * FROM signal_documents WHERE kind = :kind") suspend fun documents(kind: String): List<SignalDocumentRow>
    @Query("SELECT * FROM signal_documents WHERE id = :id") suspend fun document(id: String): SignalDocumentRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDocument(row: SignalDocumentRow)
    @Query("DELETE FROM signal_documents WHERE id IN (:ids)") suspend fun deleteDocuments(ids: List<String>)
    @Query("DELETE FROM signal_documents WHERE kind = :kind") suspend fun clearDocuments(kind: String)
    @Query("SELECT DISTINCT child FROM signal_edges WHERE parent IN (:parents)") suspend fun children(parents: List<String>): List<String>
    @Query("DELETE FROM signal_edges WHERE child = :child") suspend fun clearParents(child: String)
    @Query("DELETE FROM signal_edges WHERE child IN (:ids) OR parent IN (:ids)") suspend fun deleteEdges(ids: List<String>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun edges(edges: List<SignalEdge>)
}

@Database(entities = [SignalRow::class, SignalDocumentRow::class, SignalEdge::class], version = 2, exportSchema = false)
abstract class SignalDatabase : RoomDatabase() { abstract fun records(): SignalDao }

val SIGNAL_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE signal_records ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE signal_records SET position = rowid")
        db.execSQL("ALTER TABLE signal_records ADD COLUMN threadKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX index_signal_records_threadKey_position ON signal_records(threadKey,position)")
        db.execSQL("CREATE INDEX index_signal_records_position ON signal_records(position)")
        db.execSQL("CREATE TABLE IF NOT EXISTS signal_documents (id TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE INDEX index_signal_documents_kind ON signal_documents(kind)")
        db.execSQL("CREATE TABLE IF NOT EXISTS signal_edges (parent TEXT NOT NULL, child TEXT NOT NULL, PRIMARY KEY(parent,child))")
        db.execSQL("CREATE INDEX index_signal_edges_child ON signal_edges(child)")
    }
}

data class SignalRecordPage(val records: List<SignalRecord>, val cursor: Long, val hasMore: Boolean)

class SignalStore(context: Context, private val namespace: String = "signal", private val defaultSettings: SignalSettings = SignalSettings()) : SignalSecrets {
    private val prefs = context.getSharedPreferences("${namespace}_private", Context.MODE_PRIVATE)
    private val database = Room.databaseBuilder(context, SignalDatabase::class.java, "${namespace}-history.db").addMigrations(SIGNAL_MIGRATION_1_2).build()
    private val dao get() = database.records()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key: SecretKey by lazy {
        require(namespace.matches(Regex("[a-z0-9-]+")))
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
    fun settings(): SignalSettings = prefs.getString("settings", null)?.let { json.decodeFromString(decrypt(it, "settings")) } ?: defaultSettings
    fun settings(value: SignalSettings) { check(prefs.edit().putString("settings", encrypt(json.encodeToString(value), "settings")).commit()) }
    private fun decode(row: SignalRow): SignalRecord = SignalLearning.normalize(json.decodeFromString(decrypt(row.payload, row.id)))
    suspend fun page(before: Long = Long.MAX_VALUE, limit: Int = 100): SignalRecordPage {
        val rows = dao.page(before, limit.coerceIn(1, 200) + 1)
        val selected = rows.take(limit.coerceIn(1, 200))
        return SignalRecordPage(selected.map(::decode), selected.lastOrNull()?.position ?: 0, rows.size > selected.size)
    }
    suspend fun record(id: String): SignalRecord? = dao.get(id)?.let(::decode)
    suspend fun count() = dao.count()
    suspend fun thread(id: String, limit: Int = 100): List<SignalRecord> = dao.thread(opaqueIndex(id), Long.MAX_VALUE, limit.coerceIn(1, 200)).map(::decode)
    suspend fun threadIds(id: String): Set<String> = dao.threadIds(opaqueIndex(id)).toSet()
    suspend fun newest(limit: Int = 100, predicate: suspend (SignalRecord) -> Boolean): List<SignalRecord> {
        val result = mutableListOf<SignalRecord>(); var before = Long.MAX_VALUE
        do {
            val page = page(before); before = page.cursor
            for (record in page.records) { if (predicate(record)) result += record; if (result.size >= limit) return result }
        } while (page.hasMore)
        return result
    }
    suspend fun walk(after: Long = 0, visit: suspend (Long, SignalRecord) -> Unit) {
        var cursor = after
        while (true) {
            val rows = dao.after(cursor, 100)
            if (rows.isEmpty()) break
            for (row in rows) { visit(row.position, decode(row)); cursor = row.position }
        }
    }
    suspend fun save(record: SignalRecord) = database.withTransaction {
        val normalized = SignalLearning.normalize(record)
        val position = dao.get(record.id)?.position ?: run {
            val high = maxOf(dao.maximum(), document("sequence")?.toLongOrNull() ?: 0L) + 1
            document("sequence", "checkpoint", high.toString())
            high
        }
        dao.put(SignalRow(record.id, encrypt(json.encodeToString(normalized), record.id), position))
        index(normalized)
        if (record.state in setOf("working", "recording")) {
            document("work:${record.id}", "active_record", record.id)
            dao.edges(listOf(SignalEdge(record.id, "work:${record.id}")))
        } else { dao.deleteDocuments(listOf("work:${record.id}")); dao.deleteEdges(listOf("work:${record.id}")) }
    }
    suspend fun index(record: SignalRecord) {
        dao.setThreadIndex(record.id, opaqueIndex(record.threadId))
        dao.clearParents(record.id)
        dao.edges((record.references + record.memoryReferences.keys.map { "m:$it" }).distinct().map { SignalEdge(it, record.id) })
    }
    suspend fun deletionClosure(initial: Set<String>): Set<String> {
        val seen = initial.toMutableSet(); var pending = initial
        while (pending.isNotEmpty()) {
            val children = pending.toList().chunked(400).flatMap { dao.children(it) }.filter { it !in seen }.toSet()
            seen += children; pending = children
        }
        return seen
    }
    suspend fun delete(ids: Set<String>) = database.withTransaction {
        ids.toList().chunked(400).forEach { dao.delete(it); dao.deleteDocuments(it); dao.deleteEdges(it) }
    }
    suspend fun clear() { val ids = mutableSetOf<String>(); walk { _, r -> ids += r.id }; delete(deletionClosure(ids)) }
    suspend fun memory(): List<SignalMemory> = documents("memory").map { json.decodeFromString(it) }
    suspend fun saveMemory(memory: SignalMemory) = database.withTransaction {
        val id = "m:${memory.id}"
        document(id, "memory", json.encodeToString(memory))
        dao.clearParents(id); dao.edges((memory.evidence + memory.proposedEvidence).map { SignalEdge(it.recordId, id) }.distinct())
    }
    suspend fun correction(value: SignalMemoryCorrection) {
        val id = "c:${value.id}"; document(id, "correction", json.encodeToString(value)); dao.edges(listOf(SignalEdge("m:${value.memoryId}", id)))
    }
    suspend fun sessions(): List<SignalObservationSession> = documents("session").map { json.decodeFromString<SignalObservationSession>(it) }.sortedByDescending { it.startedAt }
    suspend fun session(value: SignalObservationSession) = document("s:${value.id}", "session", json.encodeToString(value))
    suspend fun documents(kind: String): List<String> = dao.documents(kind).map { decrypt(it.payload, it.id) }
    suspend fun document(id: String): String? = dao.document(id)?.let { decrypt(it.payload, it.id) }
    suspend fun document(id: String, kind: String, value: String) = dao.putDocument(SignalDocumentRow(id, kind, encrypt(value, id)))
    suspend fun clearDocuments(kind: String) = dao.clearDocuments(kind)
    suspend fun atomic(block: suspend () -> Unit) = database.withTransaction { block() }
    @Volatile private var cachedIndexKey: ByteArray? = null
    suspend fun opaqueIndex(value: String): String {
        val bytes = cachedIndexKey ?: indexKeyMutex.withLock {
            cachedIndexKey ?: Base64.decode(get("index") ?: Base64.encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }, Base64.NO_WRAP).also { put("index", it) }, Base64.NO_WRAP).also { cachedIndexKey = it }
        }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(bytes, "HmacSHA256")) }
        return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun bytes(): Long = database.openHelper.readableDatabase.path?.let { java.io.File(it).length() + java.io.File("$it-wal").length() } ?: 0
    fun close() = database.close()
    companion object { private val indexKeyMutex = Mutex() }
}
