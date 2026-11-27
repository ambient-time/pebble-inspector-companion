package com.lukesteuber.localmodels

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** An activation interrupted after the move must not cost a second 2.59 GB download. */
class GemmaRecoveryTest {
    private fun pinned(content: ByteArray) = PinnedModel(
        displayName = "Fixture", fileName = "fixture.litertlm", bytes = content.size.toLong(),
        sha256 = content.inputStream().use { ModelDownloadRules.sha256Hex(it) {} },
        url = "https://huggingface.co/fixture/resolve/abc/fixture.litertlm",
    )

    @Test
    fun `a full-size model without its receipt is verified in place, not downloaded again`() {
        val directory = Files.createTempDirectory("gemma-recovery").toFile()
        val content = ByteArray(4096) { (it * 7).toByte() }
        val model = pinned(content)
        val store = GemmaModelStore(directory, model)
        File(directory, model.fileName).writeBytes(content)
        assertFalse(store.isInstalled())
        assertTrue(store.needsRecovery())
        var verifying = false
        // No free space and no network: recovery must finish before either is needed.
        GemmaDownloader(store, model).run(freeBytes = 0, onProgress = { fail("must not download") }, onVerifying = { verifying = true })
        assertTrue(verifying)
        assertTrue(store.isInstalled())
        assertFalse(store.needsRecovery())
        directory.deleteRecursively()
    }

    @Test
    fun `a full-size model that fails verification is removed and downloaded fresh`() {
        val directory = Files.createTempDirectory("gemma-recovery-bad").toFile()
        val content = ByteArray(4096) { (it * 7).toByte() }
        val model = pinned(content)
        val store = GemmaModelStore(directory, model)
        File(directory, model.fileName).writeBytes(ByteArray(content.size))
        assertTrue(store.needsRecovery())
        try {
            GemmaDownloader(store, model).run(freeBytes = 0, onProgress = {}, onVerifying = {})
            fail("expected the free-space check to stop a fresh download")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("free space"))
        }
        assertFalse(File(directory, model.fileName).exists())
        assertFalse(store.isInstalled())
        directory.deleteRecursively()
    }
}
