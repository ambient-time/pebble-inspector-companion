package com.lukesteuber.signalstation

import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coredevices.pebble.signal.SignalSpeechFailure
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.Rule
import kotlin.test.assertTrue

/** Run on an isolated, offline emulator. Callback success is not acoustic acceptance. */
class SignalSpeechEngineTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun installedEngineCompletesOrReportsUnavailableWithoutFallback() = runBlocking(Dispatchers.Main) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = AndroidSignalSpeechEngine(context) { engineInterrupted = true }
        var outcome = ""
        try {
            withTimeout(30_000) {
                val voice = engine.prepare()
                var started = false
                engine.speak("Synthetic test. Outside twenty two degrees, fresh. Room eighteen degrees, stale. Humidity unavailable.") { started = true }
                assertTrue(started)
                outcome = "Native speech callbacks completed with $voice. Acoustic quality unverified."
            }
        } catch (failure: SignalSpeechFailure) {
            val message = failure.message.orEmpty()
            assertTrue(message.startsWith("No installed offline voice") || message.startsWith("Android speech could not start"), message)
            outcome = "Native speech unavailable on this emulator: $message"
        } finally {
            engine.close()
            engine.close()
        }
        assertTrue(!engineInterrupted)
        File(context.getExternalFilesDir(null), "speech-engine.txt").writeText(outcome)
    }

    private var engineInterrupted = false
}
