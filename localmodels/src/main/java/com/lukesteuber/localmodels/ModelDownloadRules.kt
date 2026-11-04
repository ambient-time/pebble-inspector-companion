package com.lukesteuber.localmodels

import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest

/** Download checks kept free of Android types so they can be unit tested. */
object ModelDownloadRules {
    /** Hugging Face and its CDN redirect targets, over HTTPS only. */
    fun isAllowedHost(url: URL): Boolean = url.protocol == "https" &&
        (url.host == "huggingface.co" || url.host.endsWith(".huggingface.co") || url.host.endsWith(".hf.co"))

    /** Parses `bytes start-end/total` leniently (case, spacing) and checks it resumes where asked. */
    fun contentRangeMatches(header: String?, offset: Long, total: Long): Boolean {
        val match = CONTENT_RANGE.matchEntire(header?.trim().orEmpty()) ?: return false
        val (start, end, size) = match.destructured
        return start.toLongOrNull() == offset && end.toLongOrNull() == total - 1 && (size == "*" || size.toLongOrNull() == total)
    }

    private val CONTENT_RANGE = Regex("""(?i)bytes\s+(\d+)\s*-\s*(\d+)\s*/\s*(\d+|\*)""")

    /**
     * I/O errors (dropped connection, DNS, timeouts) are retried later. Errors thrown with check() are
     * not: a bad file, an unexpected response, or too little free space before the download starts. A
     * disk that fills mid-download surfaces as an IOException, so it is retried, and the next run's
     * free-space check reports it.
     */
    fun isTransient(error: Throwable): Boolean = error is java.io.IOException && error !is java.io.FileNotFoundException

    /** Bytes still needed on disk, plus headroom so the phone isn't left completely full. */
    fun requiredFreeBytes(total: Long, alreadyDownloaded: Long): Long =
        (total - alreadyDownloaded).coerceAtLeast(0) + ModelImportRules.FREE_SPACE_MARGIN_BYTES

    fun sha256Hex(input: InputStream, checkCancelled: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(actualHex: String, expectedHex: String): Boolean =
        MessageDigest.isEqual(actualHex.lowercase().toByteArray(), expectedHex.lowercase().toByteArray())
}

/** Where a background model download stands, shared between the job and the settings screen. */
data class ModelDownloadProgress(
    val state: State,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String? = null,
) {
    enum class State { IDLE, WAITING_FOR_NETWORK, DOWNLOADING, VERIFYING, CANCELLING, DONE, FAILED }

    val isActive: Boolean get() = state == State.WAITING_FOR_NETWORK || state == State.DOWNLOADING || state == State.VERIFYING || state == State.CANCELLING
}

/** A pinned model file: size and checksum are known before a single byte is fetched. */
data class PinnedModel(
    val displayName: String,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val url: String,
) {
    /** Verifies size and checksum. Throws with a readable message on mismatch. */
    fun verify(file: File, checkCancelled: () -> Unit = {}) {
        check(file.isFile && file.length() == bytes) { "The model file is incomplete or the wrong size." }
        val actual = file.inputStream().buffered().use { ModelDownloadRules.sha256Hex(it, checkCancelled) }
        check(ModelDownloadRules.matches(actual, sha256)) { "The downloaded model didn't match its checksum. Try again." }
    }
}
