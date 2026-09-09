package coredevices.pebble.signal

import kotlin.test.*

class SignalWakeReviewTest {
    private val settings = SignalSettings(watchId = "watch")
    @Test fun confirmationIsExactAndOneUse() {
        val review = SignalWakeReview()
        assertTrue(review.offer(1, 2, "Question", settings, 100))
        assertNull(review.claim(2, 2, "Question", settings, 101))
        assertNull(review.claim(1, 3, "Question", settings, 101))
        assertNull(review.claim(1, 2, "Changed", settings, 101))
        assertNull(review.claim(1, 2, "Question", settings.copy(provider = "xai"), 101))
        assertNull(review.claim(1, 2, "Question", settings.copy(watchId = "other"), 101))
        assertNull(review.claim(1, 2, "Question", settings.copy(enabled = setOf("location")), 101))
        assertNotNull(review.claim(1, 2, "Question", settings, 101))
        assertNull(review.claim(1, 2, "Question", settings, 102))
    }
    @Test fun expiresAndInvalidates() {
        val review = SignalWakeReview()
        review.offer(1, 2, "Question", settings, 100)
        assertNull(review.claim(1, 2, "Question", settings, 120100))
        review.invalidate()
        assertNull(review.claim(1, 2, "Question", settings, 101))
    }
    @Test fun cannotHideLongUnicodeQuestionOrProvider() {
        val review = SignalWakeReview()
        assertFalse(review.offer(1, 2, "界".repeat(134), settings, 100))
        assertTrue(review.offer(1, 2, "界".repeat(133), settings, 100))
        assertFalse(review.offer(1, 2, "Question", settings.copy(model = "x".repeat(350)), 100))
    }
}
