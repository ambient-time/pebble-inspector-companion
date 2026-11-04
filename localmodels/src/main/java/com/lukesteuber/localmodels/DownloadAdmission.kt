package com.lukesteuber.localmodels

internal enum class DownloadAdmission { START, WAIT, OBSOLETE }

/** Scheduled attempt IDs are persisted and invalidated by Cancel, including across process death. */
internal fun downloadAdmission(scheduled: String?, current: String?, activeWorker: Boolean, cancelled: Boolean): DownloadAdmission = when {
    scheduled.isNullOrBlank() || scheduled != current || cancelled -> DownloadAdmission.OBSOLETE
    activeWorker -> DownloadAdmission.WAIT
    else -> DownloadAdmission.START
}
