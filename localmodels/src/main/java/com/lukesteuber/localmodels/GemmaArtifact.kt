package com.lukesteuber.localmodels

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException

/**
 * The model this app downloads. Pinned to a commit so the size and checksum can't drift. The
 * litert-community Gemma 4 repository is Apache 2.0 and not gated, so no account is needed.
 */
object GemmaArtifact {
    private const val REVISION = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"
    val MODEL = PinnedModel(
        displayName = "Gemma 4 E2B",
        fileName = "gemma-4-E2B-it.litertlm",
        bytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$REVISION/gemma-4-E2B-it.litertlm",
    )
}

/** The downloaded model, its partial transfer, and a receipt that avoids rehashing gigabytes. */
internal class GemmaModelStore(directory: File, private val pinned: PinnedModel = GemmaArtifact.MODEL) {
    val model = File(directory, pinned.fileName)
    val partial = File(directory, "${pinned.fileName}.part")
    private val receipt = File(directory, "${pinned.fileName}.verified")

    fun isInstalled(): Boolean = model.isFile && model.length() == pinned.bytes &&
        receipt.isFile && runCatching { receipt.readText() == receiptText() }.getOrDefault(false)

    /** Checks the finished transfer and swaps it into place atomically. */
    fun activate(checkCancelled: () -> Unit) {
        try {
            pinned.verify(partial, checkCancelled)
        } catch (error: CancellationException) {
            // Stopped mid-check (lost Wi-Fi, time limit, Cancel): keep the finished transfer for the next attempt.
            // This catch comes first because CancellationException is an IllegalStateException.
            throw error
        } catch (error: IllegalStateException) {
            // A bad or oversized transfer would otherwise be "resumed" and re-verified forever.
            partial.delete()
            throw error
        }
        checkCancelled()
        model.parentFile?.mkdirs()
        Files.move(partial.toPath(), model.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val pending = File(receipt.parentFile, "${receipt.name}.new")
        pending.writeText(receiptText())
        Files.move(pending.toPath(), receipt.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun remove() {
        listOf(receipt, partial, model, File(receipt.parentFile, "${receipt.name}.new")).forEach {
            if (it.exists() && !it.delete()) throw LocalModelFailure("Could not remove all model files. Try again.")
        }
    }

    private fun receiptText() = "${pinned.sha256}\n${pinned.bytes}\n${model.lastModified()}\n"
}
