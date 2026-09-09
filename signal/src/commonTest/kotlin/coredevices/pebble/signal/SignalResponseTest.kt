package coredevices.pebble.signal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalResponseTest {
    @Test fun responseLinksOnlyOpenExplicitWebDestinations() {
        assertTrue(signalResponseLink("https://example.org/report?q=weather#today"))
        assertTrue(signalResponseLink("http://example.org/"))
        for (uri in listOf("javascript:alert(1)", "file:///sdcard/private", "content://private", "intent://app", "//example.org", "https://user:secret@example.org", "https://example.org/\nfoo")) {
            assertFalse(signalResponseLink(uri), uri)
        }
    }
}
