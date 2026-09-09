package coredevices.coreapp.signal

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.SignalWakeSession
import org.json.JSONObject
import org.junit.Test
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises packaged model weights and Android JNI with synthetic speech, not a physical microphone. */
class SignalWakeModelTest {
    @Test fun packagedModelRecognizesWakePhraseAndRejectsUnrelatedSpeech() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".inspectorlab"))
        val ownedDirectory = File(context.cacheDir, "signal-wake-model-test-${UUID.randomUUID()}")
        check(ownedDirectory.mkdir())
        try {
            context.assets.open("signal-station/wake-model.zip").use { unpack(it, ownedDirectory) }
            Model(ownedDirectory.absolutePath).use { model ->
                fun detects(asset: String): Boolean {
                    val samples = instrumentation.context.assets.open(asset).use { pcmWave(boundedBytes(it, 4 * 1024 * 1024)) }
                    Recognizer(model, 16000f, "[\"go go gadget\", \"[unk]\"]").use { recognizer ->
                        var detected = false
                        fun inspect(json: String, field: String) {
                            if (SignalWakeSession.isWakePhrase(JSONObject(json).optString(field))) detected = true
                        }
                        var offset = 0
                        while (offset < samples.size) {
                            val end = minOf(samples.size, offset + 1600)
                            val chunk = samples.copyOfRange(offset, end)
                            if (recognizer.acceptWaveForm(chunk, chunk.size)) inspect(recognizer.result, "text")
                            else inspect(recognizer.partialResult, "partial")
                            offset = end
                        }
                        inspect(recognizer.finalResult, "text")
                        return detected
                    }
                }
                assertTrue(detects("signal-wake-positive.wav"), "Packaged model did not recognize the synthetic wake phrase")
                assertFalse(detects("signal-wake-negative.wav"), "Unrelated synthetic speech activated the wake phrase")
            }
        } finally {
            assertTrue(ownedDirectory.deleteRecursively(), "Could not remove the owned test model directory")
        }
    }

    private fun unpack(input: InputStream, directory: File) {
        var total = 0L
        var entries = 0
        ZipInputStream(input).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                val entry = zip.nextEntry ?: break
                check(++entries <= 1000)
                val prefix = "vosk-model-small-en-us-0.15/"
                check(entry.name.startsWith(prefix))
                val relative = entry.name.removePrefix(prefix)
                if (relative.isEmpty()) continue
                val file = File(directory, relative)
                check(file.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                if (entry.isDirectory) check(file.mkdirs() || file.isDirectory)
                else {
                    check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                    check(!file.exists())
                    file.outputStream().use { output ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= 128L * 1024 * 1024)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
        check(File(directory, "am/final.mdl").isFile)
        check(File(directory, "graph/Gr.fst").isFile)
    }

    private fun boundedBytes(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= maximum)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** RIFF chunks may include metadata and odd-byte padding before the PCM payload. */
    private fun pcmWave(bytes: ByteArray): ShortArray {
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        fun u16(offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        fun u32(offset: Int): Long = (0..3).fold(0L) { value, index -> value or ((bytes[offset + index].toLong() and 255) shl (8 * index)) }
        check(bytes.size >= 12)
        assertEquals("RIFF", tag(0)); assertEquals("WAVE", tag(8))
        val limit = u32(4) + 8
        check(limit in 12L..bytes.size.toLong())
        var formatFound = false
        var payload: IntRange? = null
        var offset = 12
        while (offset.toLong() + 8 <= limit) {
            val length = u32(offset + 4)
            val start = offset + 8
            val end = start.toLong() + length
            check(end <= limit)
            when (tag(offset)) {
                "fmt " -> {
                    check(length >= 16)
                    assertEquals(1, u16(start), "Fixture must use integer PCM")
                    assertEquals(1, u16(start + 2), "Fixture must be mono")
                    assertEquals(16000L, u32(start + 4))
                    assertEquals(2, u16(start + 12))
                    assertEquals(16, u16(start + 14))
                    formatFound = true
                }
                "data" -> { check(payload == null); payload = start until end.toInt() }
            }
            val next = end + (length and 1)
            check(next <= limit)
            offset = next.toInt()
        }
        check(formatFound)
        val data = checkNotNull(payload)
        check(!data.isEmpty() && data.count() % 2 == 0)
        return ShortArray(data.count() / 2) { u16(data.first + it * 2).toShort() }
    }
}
