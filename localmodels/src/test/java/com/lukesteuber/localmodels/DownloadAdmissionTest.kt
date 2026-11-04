package com.lukesteuber.localmodels

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadAdmissionTest {
    @Test fun cancelBeforeDelayedStartNeverReactivatesEvenAfterProcessRestart() {
        val scheduled = "first-attempt"
        assertEquals(DownloadAdmission.START, downloadAdmission(scheduled, scheduled, false, false))
        val cancelledGeneration = "cancelled-attempt"
        assertEquals(DownloadAdmission.OBSOLETE, downloadAdmission(scheduled, cancelledGeneration, false, true))
        assertEquals(DownloadAdmission.OBSOLETE, downloadAdmission(scheduled, cancelledGeneration, false, false))
        assertEquals(DownloadAdmission.OBSOLETE, downloadAdmission(scheduled, "second-attempt", false, false))
        assertEquals(DownloadAdmission.START, downloadAdmission("second-attempt", "second-attempt", false, false))
    }
    @Test fun onlyCurrentAttemptCanWaitForAStoppingWorker() {
        assertEquals(DownloadAdmission.WAIT, downloadAdmission("current", "current", true, false))
        assertEquals(DownloadAdmission.OBSOLETE, downloadAdmission("old", "current", true, false))
        assertEquals(DownloadAdmission.OBSOLETE, downloadAdmission(null, null, false, false))
    }
}
