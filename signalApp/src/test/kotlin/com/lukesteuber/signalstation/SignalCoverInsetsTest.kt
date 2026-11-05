package com.lukesteuber.signalstation

import kotlin.test.Test
import kotlin.test.assertEquals

class SignalCoverInsetsTest {
    @Test fun fullPanelUsesObservedCameraHeightWhenSamsungOmitsInsets() {
        assertEquals(314, signalCoverBottomInset("samsung", "SM-F776U1", 1422, 1572, emptyList()))
        assertEquals(209, signalCoverBottomInset("Samsung", "SM-F766B", 948, 1048, emptyList()))
    }

    @Test fun alreadyLetterboxedWideFrameDoesNotInsetAgain() {
        val belowView = SignalCoverCutout(642, 1259, 1422, 1572)
        assertEquals(0, signalCoverBottomInset("samsung", "SM-F776U1", 1422, 1259, listOf(belowView)))
        assertEquals(0, signalCoverBottomInset("samsung", "SM-F776U1", 1422, 1259, emptyList()))
    }

    @Test fun reportedCameraBoundsTakePrecedence() {
        val cameras = SignalCoverCutout(428, 839, 948, 1048)
        assertEquals(209, signalCoverBottomInset("samsung", "SM-F776U1", 948, 1048, listOf(cameras)))
    }

    @Test fun fallbackDoesNotAffectInnerScreenOrOtherDevices() {
        assertEquals(0, signalCoverBottomInset("samsung", "SM-F776U1", 1080, 2520, emptyList()))
        assertEquals(0, signalCoverBottomInset("google", "Pixel 10", 948, 1048, emptyList()))
        assertEquals(0, signalCoverBottomInset("samsung", "SM-F741B", 948, 1048, emptyList()))
    }

    @Test fun topCutoutAndEmptyLayoutDoNotBecomeBottomPadding() {
        assertEquals(0, signalCoverBottomInset("samsung", "SM-F776U1", 1080, 2520,
            listOf(SignalCoverCutout(500, 0, 580, 90))))
        assertEquals(0, signalCoverBottomInset("samsung", "SM-F776U1", 0, 0, emptyList()))
    }
}
