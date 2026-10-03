package com.lukesteuber.localmodels

import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

/**
 * Resumable HTTPS download of a pinned model into [store]. Runs on the caller's thread; the
 * background job owns scheduling, networking constraints, and the notification.
 */
internal class GemmaDownloader(
    private val store: GemmaModelStore,
    private val pinned: PinnedModel = GemmaArtifact.MODEL,
    /** The network JobScheduler assigned (for example unmetered); null uses the default network. */
    private val network: android.net.Network? = null,
) {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile var cancelled = false
        private set

    fun cancel() {
        cancelled = true
        runCatching { connection?.disconnect() }
    }

    private fun checkCancelled() {
        if (cancelled) throw CancellationException("Download cancelled")
    }

    fun run(freeBytes: Long, onProgress: (bytes: Long) -> Unit, onVerifying: () -> Unit) {
        if (store.isInstalled()) return
        store.partial.parentFile?.mkdirs()
        var offset = store.partial.takeIf { it.isFile }?.length() ?: 0L
        if (offset > pinned.bytes) {
            store.partial.delete()
            offset = 0
        }
        check(freeBytes >= ModelDownloadRules.requiredFreeBytes(pinned.bytes, offset)) {
            "Not enough free space. The model needs about ${ModelImportRules.formatBytes(ModelDownloadRules.requiredFreeBytes(pinned.bytes, offset))}."
        }
        if (offset < pinned.bytes) {
            onProgress(offset)
            val response = open(offset)
            try {
                when (response.responseCode) {
                    200 -> offset = 0
                    206 -> check(ModelDownloadRules.contentRangeMatches(response.getHeaderField("Content-Range"), offset, pinned.bytes)) {
                        "The server resumed from the wrong place."
                    }
                    416 -> {
                        // The saved part doesn't fit the server's file; start over next attempt.
                        store.partial.delete()
                        throw java.io.IOException("Resume rejected; restarting the download.")
                    }
                    else -> error("Hugging Face returned HTTP ${response.responseCode}.")
                }
                response.inputStream.buffered().use { input ->
                    FileOutputStream(store.partial, offset > 0).buffered().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var total = offset
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= pinned.bytes) { "The download is larger than expected." }
                            output.write(buffer, 0, count)
                            onProgress(total)
                        }
                    }
                }
            } finally {
                response.disconnect()
                connection = null
            }
        }
        checkCancelled()
        onVerifying()
        store.activate(::checkCancelled)
    }

    /** Follows redirects by hand so every hop stays on Hugging Face over HTTPS. */
    private fun open(offset: Long): HttpURLConnection {
        var url = URL(pinned.url)
        repeat(MAX_REDIRECTS) {
            checkCancelled()
            check(ModelDownloadRules.isAllowedHost(url)) { "Refusing to download from ${url.host}." }
            val candidate = ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            connection = candidate
            when (candidate.responseCode) {
                301, 302, 303, 307, 308 -> {
                    val location = candidate.getHeaderField("Location") ?: error("Redirect without a location.")
                    candidate.disconnect()
                    url = URL(url, location)
                }
                else -> return candidate
            }
        }
        error("Too many redirects.")
    }

    private companion object {
        const val MAX_REDIRECTS = 6
    }
}
