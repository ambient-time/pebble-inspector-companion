package coredevices.pebble.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalDisplayTest {
    private fun record(kind: String, question: String, provider: String = "local", model: String = "", state: String = "ready", answer: String = "") =
        SignalRecord("4e7a1608-56bf", "t", 0, question, answer = answer, provider = provider, model = model, state = state, kind = kind)

    @Test fun captureGetsAPlainTitle() {
        val capture = record("capture", "Capture current context")
        assertEquals("Capture", SignalDisplay.title(capture))
        assertEquals("", SignalDisplay.kindLine(capture))
        assertEquals("Saved on this device", SignalDisplay.sourceLine(capture))
    }

    @Test fun otherRecordsKeepTheirQuestionAndGetAKind() {
        val changes = record("changes", "What changed?")
        assertEquals("What changed?", SignalDisplay.title(changes))
        assertEquals("Comparison", SignalDisplay.kindLine(changes))
        assertEquals("Answer · failed", SignalDisplay.kindLine(record("analysis", "Why?", "openai", "gpt-4.1-mini", "error")))
        assertEquals("Health import", SignalDisplay.kind("health_sync"))
    }

    @Test fun onDeviceModelsHideTheirInternalId() {
        assertEquals("On device · Gemma", SignalDisplay.answerSource("local-gemma", "local-file"))
        assertEquals("OpenAI · gpt-4.1-mini", SignalDisplay.answerSource("openai", "gpt-4.1-mini"))
    }

    @Test fun reviewLineHasNoIdOrIsoTime() {
        val line = SignalDisplay.reviewLine(record("capture", "Capture current context"))
        assertTrue(line.startsWith("Capture · 19"))
        assertFalse(line.contains("4e7a1608"))
        assertFalse(line.contains("T00:"))
    }

    @Test fun wholeNumbersDropTheDecimal() {
        assertEquals("82", SignalDisplay.number(82.0))
        assertEquals("-18", SignalDisplay.number(-18.0))
        assertEquals("0.5", SignalDisplay.number(0.5))
    }
}
