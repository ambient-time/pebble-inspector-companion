package com.lukesteuber.localmodels

import com.lukesteuber.localmodels.ModelImportRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelImportRulesTest {
    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    private fun check(name: String? = "gemma3-1b-it-int4.litertlm", size: Long? = 557 * mb, free: Long = 20 * gb, ram: Long = 8 * gb, lowRam: Boolean = false) =
        ModelImportRules.check(name, size, free, ram, lowRam)

    @Test
    fun `a 1B model on an 8 GB phone is accepted`() {
        assertEquals(Verdict.Ok, check())
    }

    @Test
    fun `wrong file types are rejected with the file name`() {
        val verdict = check(name = "photo.jpg")
        assertTrue(verdict is Verdict.Rejected && "photo.jpg" in verdict.reason)
    }

    @Test
    fun `models too big for memory are rejected`() {
        assertTrue(check(size = 3 * gb) is Verdict.Rejected)
    }

    @Test
    fun `imports need the file size plus a margin free`() {
        assertTrue(check(free = 600 * mb) is Verdict.Rejected)
        assertEquals(Verdict.Ok, check(free = 557 * mb + ModelImportRules.FREE_SPACE_MARGIN_BYTES))
    }

    @Test
    fun `low ram devices and tiny files are rejected`() {
        assertTrue(check(lowRam = true) is Verdict.Rejected)
        assertTrue(check(size = 2 * mb) is Verdict.Rejected)
    }

    @Test
    fun `unknown size is allowed through to the copy`() {
        assertEquals(Verdict.Ok, check(size = null))
    }
}
