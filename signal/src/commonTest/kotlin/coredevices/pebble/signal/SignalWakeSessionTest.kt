package coredevices.pebble.signal

import kotlin.test.*

class SignalWakeSessionTest {
    @Test fun onlyTheWholePhraseActivates() {
        assertTrue(SignalWakeSession.isWakePhrase(" GO  go gadget "))
        for (text in listOf("go gadget", "go go", "[unk]", "go go gadget [unk]", "please go go gadget"))
            assertFalse(SignalWakeSession.isWakePhrase(text))
    }
    @Test fun stopInvalidatesLatePermissionAndSpeechCallbacks() {
        val session = SignalWakeSession()
        val token = session.begin()
        session.update(token, "recording", "Speak")
        session.stop()
        assertFalse(session.update(token, "listening", "Late model"))
        assertFalse(session.draft(token, "Late transcript"))
        assertEquals("", session.state.draft)
    }
    @Test fun draftIsBoundedAndDeliveredOnceWithoutAutomaticSend() {
        val session = SignalWakeSession()
        val token = session.begin()
        assertFalse(session.draft(token, "Not recording"))
        session.update(token, "recording", "Speak")
        assertTrue(session.draft(token, "x".repeat(9000)))
        assertFalse(session.draft(token, "Duplicate"))
        assertFalse(session.update(token, "listening", "Resume"))
        assertEquals(8000, session.state.draft.length)
        session.stop()
        assertEquals(8000, session.state.draft.length)
        session.dismiss()
        assertEquals("", session.state.draft)
    }
}
