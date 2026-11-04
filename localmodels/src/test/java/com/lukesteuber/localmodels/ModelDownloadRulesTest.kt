package com.lukesteuber.localmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CancellationException

class ModelDownloadRulesTest {
    @Test
    fun `only hugging face over https is allowed`() {
        assertTrue(ModelDownloadRules.isAllowedHost(URL("https://huggingface.co/litert-community/x/resolve/abc/model.litertlm")))
        assertTrue(ModelDownloadRules.isAllowedHost(URL("https://cas-bridge.xethub.hf.co/xet-bridge/abc")))
        assertTrue(ModelDownloadRules.isAllowedHost(URL("https://cdn-lfs.huggingface.co/repos/abc")))
        assertFalse(ModelDownloadRules.isAllowedHost(URL("http://huggingface.co/model")))
        assertFalse(ModelDownloadRules.isAllowedHost(URL("https://huggingface.co.evil.example/model")))
        assertFalse(ModelDownloadRules.isAllowedHost(URL("https://example.com/huggingface.co")))
    }

    @Test
    fun `content range parsing is lenient about formatting but strict about position`() {
        assertTrue(ModelDownloadRules.contentRangeMatches("bytes 1024-2047/2048", 1024, 2048))
        assertTrue(ModelDownloadRules.contentRangeMatches("Bytes  1024 - 2047 / 2048 ", 1024, 2048))
        assertTrue(ModelDownloadRules.contentRangeMatches("bytes 1024-2047/*", 1024, 2048))
        assertFalse(ModelDownloadRules.contentRangeMatches("bytes 0-2047/2048", 1024, 2048))
        assertFalse(ModelDownloadRules.contentRangeMatches("bytes 1024-2047/9999", 1024, 2048))
        assertFalse(ModelDownloadRules.contentRangeMatches(null, 1024, 2048))
    }

    @Test
    fun `network errors are retried but bad files are not`() {
        assertTrue(ModelDownloadRules.isTransient(java.net.SocketTimeoutException()))
        assertTrue(ModelDownloadRules.isTransient(java.net.UnknownHostException()))
        assertFalse(ModelDownloadRules.isTransient(IllegalStateException("checksum")))
        assertFalse(ModelDownloadRules.isTransient(java.io.FileNotFoundException()))
    }

    @Test
    fun `free space counts only the bytes still missing`() {
        val margin = ModelImportRules.FREE_SPACE_MARGIN_BYTES
        assertEquals(1000 + margin, ModelDownloadRules.requiredFreeBytes(3000, 2000))
        assertEquals(margin, ModelDownloadRules.requiredFreeBytes(3000, 5000))
    }

    @Test
    fun `pinned model verification checks size and checksum`() {
        val file = File.createTempFile("model", ".litertlm").apply { writeText("hello") }
        val sha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        PinnedModel("Test", file.name, 5, sha, "https://huggingface.co/x").verify(file)

        val wrongSize = runCatching { PinnedModel("Test", file.name, 6, sha, "https://huggingface.co/x").verify(file) }
        val wrongSha = runCatching { PinnedModel("Test", file.name, 5, "0".repeat(64), "https://huggingface.co/x").verify(file) }
        assertTrue(wrongSize.isFailure && wrongSha.isFailure)
        file.delete()
    }

    @Test
    fun `stopping during the checksum keeps the finished transfer`() {
        val dir = Files.createTempDirectory("models").toFile()
        val sha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        val store = GemmaModelStore(dir, PinnedModel("Test", "m.litertlm", 5, sha, "https://huggingface.co/x"))
        store.partial.writeText("hello")

        val stopped = runCatching { store.activate { throw CancellationException() } }
        assertTrue(stopped.exceptionOrNull() is CancellationException)
        assertTrue(store.partial.isFile)
        assertEquals(5L, store.partial.length())

        store.activate {}
        assertTrue(store.isInstalled())
        dir.deleteRecursively()
    }

    @Test
    fun `a bad transfer is deleted so it is not resumed`() {
        val dir = Files.createTempDirectory("models").toFile()
        val store = GemmaModelStore(dir, PinnedModel("Test", "m.litertlm", 5, "0".repeat(64), "https://huggingface.co/x"))
        store.partial.writeText("hello")

        assertTrue(runCatching { store.activate {} }.exceptionOrNull() is IllegalStateException)
        assertFalse(store.partial.exists())
        dir.deleteRecursively()
    }
}
