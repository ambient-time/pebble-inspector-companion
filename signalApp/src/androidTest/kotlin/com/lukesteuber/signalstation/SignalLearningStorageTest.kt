package com.lukesteuber.signalstation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import kotlin.test.*

class SignalLearningStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun namespace() = "test-${UUID.randomUUID()}"
    private fun record(id: String) = SignalRecord(id, "thread", 1789000000000, "private-sentinel-$id", provider = "local", model = "", state = "ready", kind = "capture")
    private fun cleanup(name: String) {
        context.deleteDatabase("$name-history.db"); context.deleteSharedPreferences("${name}_private")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$name-station-v1") }
    }
    @Test fun encryptedLegacyMigrationPreservesSettingsAndRows() = runBlocking {
        val name = namespace()
        try {
            SignalStore(context, name).apply { settings(SignalSettings(provider = "xai", model = "saved-model")); close() }
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("$name-station-v1", null) as SecretKey
            val old = record("old")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(old.id.toByteArray()) }
            val payload = Base64.encodeToString(cipher.iv + cipher.doFinal(Json.encodeToString(old).toByteArray()), Base64.NO_WRAP)
            val path = context.getDatabasePath("$name-history.db")
            path.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("CREATE TABLE signal_records (id TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
                db.execSQL("INSERT INTO signal_records(id,payload) VALUES (?,?)", arrayOf(old.id, payload)); db.version = 1
            }
            val store = SignalStore(context, name)
            assertEquals("saved-model", store.settings().model)
            assertEquals(old.question, store.page().records.single().question)
            store.save(record("new"))
            store.index(old)
            assertEquals(setOf("old", "new"), store.threadIds("thread"))
            assertEquals(2, store.count())
            assertFalse(path.readBytes().toString(Charsets.ISO_8859_1).contains("private-sentinel"))
            store.close()
        } finally { cleanup(name) }
    }
    @Test fun deletionReachesMemoriesCorrectionsAndIndirectModelReports() = runBlocking {
        val name = namespace(); val store = SignalStore(context, name)
        try {
            store.save(record("evidence")); store.save(record("unrelated"))
            store.saveMemory(SignalMemory("memory", "pattern", "baseline", "A retained observation", "confirmed", createdAt = 1, evaluatedAt = 1,
                evidence = listOf(SignalEvidence("evidence", listOf("evidence:0"), 1, "Original measurement"))))
            store.correction(SignalMemoryCorrection("correction", "memory", 2, "before", "after"))
            store.save(record("answer").copy(kind = "analysis", memoryReferences = mapOf("memory" to 1L)))
            store.save(record("followup").copy(kind = "analysis", references = listOf("answer")))
            val closure = store.deletionClosure(setOf("evidence"))
            assertEquals(setOf("evidence", "m:memory", "c:correction", "answer", "followup"), closure)
            store.delete(closure)
            assertEquals(listOf("unrelated"), store.page().records.map { it.id })
            assertTrue(store.memory().isEmpty()); assertTrue(store.documents("correction").isEmpty())
        } finally { store.close(); cleanup(name) }
    }
    @Test fun deletingNewestRecordDoesNotReuseLearningCursor() = runBlocking {
        val name = namespace(); val store = SignalStore(context, name)
        try {
            store.save(record("one")); store.save(record("two"))
            val cursor = store.page(limit = 1).cursor
            store.delete(setOf("two")); store.save(record("three"))
            val seen = mutableListOf<String>(); store.walk(cursor) { _, r -> seen += r.id }
            assertEquals(listOf("three"), seen)
        } finally { store.close(); cleanup(name) }
    }
    @Test fun hundredThousandRowHistoryUsesBoundedIndexedPages() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SignalDatabase::class.java).build()
        try {
            db.withTransaction {
                val statement = db.openHelper.writableDatabase.compileStatement("INSERT INTO signal_records(id,payload,position,threadKey) VALUES (?,?,?,?)")
                repeat(100_000) { n -> statement.bindString(1, "record-$n"); statement.bindString(2, "opaque-encrypted-payload"); statement.bindLong(3, n + 1L); statement.bindString(4, "opaque-group-${n % 1000}"); statement.executeInsert() }
                statement.close()
            }
            val dao = db.records()
            assertEquals(100_000, dao.count())
            val page = dao.page(Long.MAX_VALUE, 101)
            assertEquals(101, page.size); assertEquals(100_000, page.first().position)
            assertEquals(99_899, dao.page(page.last().position, 100).first().position)
            assertEquals(100, dao.after(99_900, 100).size)
            assertEquals(20, dao.thread("opaque-group-7", Long.MAX_VALUE, 20).size)
            assertEquals(99_008, dao.thread("opaque-group-7", Long.MAX_VALUE, 20).first().position)
            assertTrue(dao.thread("absent", Long.MAX_VALUE, 100).isEmpty())
        } finally { db.close() }
    }
}
