package coredevices.pebble.signal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalAnswerReportTest {
    private val answer = SignalRecord("id-1", "t", 0, "What changed?", answer = "Battery fell.", provider = "openai", model = "gpt-4.1-mini", state = "ready",
        observations = listOf(SignalObservation("location", "phone", "47.6,-122.3", collectedAt = 0)))

    @Test fun onlyFinishedModelAnswersCanBeReported() {
        assertTrue(SignalAnswerReport.reportable(answer))
        assertFalse(SignalAnswerReport.reportable(answer.copy(provider = "local")))
        assertFalse(SignalAnswerReport.reportable(answer.copy(state = "working")))
        assertFalse(SignalAnswerReport.reportable(answer.copy(answer = "")))
    }

    @Test fun reportCarriesQuestionAnswerAndSourceButNoReadingsOrIds() {
        val text = SignalAnswerReport.text(answer, "false", "  wrong number  ", "Version 0.9.7 (29)")
        assertTrue(text.contains("Reason: False or misleading"))
        assertTrue(text.contains("Note: wrong number"))
        assertTrue(text.contains("Answer source: OpenAI · gpt-4.1-mini"))
        assertTrue(text.contains("What changed?"))
        assertTrue(text.contains("Battery fell."))
        assertFalse(text.contains("47.6"))
        assertFalse(text.contains("id-1"))
    }

    @Test fun longTextIsShortenedAndTheNoteIsOptional() {
        val text = SignalAnswerReport.text(answer.copy(answer = "x".repeat(9000)), "other", "", "v")
        assertTrue(text.length < 4000)
        assertTrue(text.endsWith("[shortened]"))
        assertFalse(text.contains("Note:"))
    }

    @Test fun mailtoIsEncoded() {
        val link = SignalAnswerReport.mailto("someone@example.com", answer, "other", "a b&c", "v")
        assertTrue(link.startsWith("mailto:someone@example.com?subject=Signal%20Station%20answer%20report"))
        assertFalse(link.substringAfter("body=").contains(" "))
        assertFalse(link.substringAfter("body=").contains("&"))
    }
}
