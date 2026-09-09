package coredevices.coreapp.signal

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*
import java.io.File

/** Real Android Keystore + Room persistence. Uses a unique test-only namespace. */
class SignalStoreTest {
    @Test fun encryptedRoundTripDeletionAndRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".inspectorlab"))
        val namespace = "signal-test-${System.nanoTime()}"
        val secret = java.util.UUID.randomUUID().toString()
        val phrase = "private-health-marker-85de3"
        val settings = SignalSettings(enabled = setOf("health.steps"), watchId = "test-watch")
        val record = SignalRecord("record", "thread", 1L, phrase, answer = phrase, provider = "openai", model = "model", state = "ready", sourceKeys = setOf("health.steps"))
        var store = SignalStore(context, namespace)
        try {
            store.put("openai", secret); store.put("transcription", "different-key")
            store.settings(settings); store.save(record)
            assertEquals(secret, store.get("openai")); assertEquals("different-key", store.get("transcription"))
            store.close()
            val db = context.getDatabasePath("$namespace-history.db")
            val files = listOf(db, File(db.path + "-wal"), File(context.applicationInfo.dataDir, "shared_prefs/${namespace}_private.xml"))
            files.filter(File::exists).forEach { file ->
                val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(bytes.contains(secret), "Credential in ${file.name}")
                assertFalse(bytes.contains(phrase), "Content in ${file.name}")
            }
            store = SignalStore(context, namespace)
            assertEquals(settings, store.settings()); assertEquals(listOf(record), store.records())
            store.delete(setOf(record.id)); assertTrue(store.records().isEmpty())
            store.save(record); store.clear(); assertTrue(store.records().isEmpty())
            store.put("openai", ""); assertNull(store.get("openai")); assertEquals("different-key", store.get("transcription"))
        } finally {
            store.close(); context.deleteDatabase("$namespace-history.db"); context.deleteSharedPreferences("${namespace}_private")
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$namespace-station-v1") }
        }
    }
}
