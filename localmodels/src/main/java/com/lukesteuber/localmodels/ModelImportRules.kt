package com.lukesteuber.localmodels

import java.util.Locale

/** Checks run before copying a user-picked model file, kept pure so they can be unit tested. */
object ModelImportRules {
    const val MODEL_EXTENSION = ".litertlm"
    const val MINIMUM_MODEL_BYTES = 16L * 1024 * 1024
    const val FREE_SPACE_MARGIN_BYTES = 256L * 1024 * 1024
    /**
     * The model runs in the separate :model process, which has to fit in RAM alongside the companion
     * and the foreground app, so it must stay well below total RAM.
     */
    const val MAX_FRACTION_OF_RAM = 0.35

    sealed interface Verdict {
        data object Ok : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    fun check(fileName: String?, sizeBytes: Long?, freeBytes: Long, totalRamBytes: Long, lowRamDevice: Boolean): Verdict {
        val name = fileName.orEmpty()
        if (!name.lowercase(Locale.ROOT).endsWith(MODEL_EXTENSION)) {
            return Verdict.Rejected("Pick a $MODEL_EXTENSION file. “${name.ifEmpty { "That file" }}” isn't a LiteRT-LM model.")
        }
        if (lowRamDevice) return Verdict.Rejected("This phone has too little memory to run a language model on this device.")
        val size = sizeBytes ?: return Verdict.Ok
        if (size < MINIMUM_MODEL_BYTES) return Verdict.Rejected("That file is too small to be a language model.")
        if (size > totalRamBytes * MAX_FRACTION_OF_RAM) {
            return Verdict.Rejected(
                "That model is ${formatBytes(size)}, too large for this phone with ${formatBytes(totalRamBytes)} of memory. Try a 1B model around 550 MB.",
            )
        }
        if (freeBytes < size + FREE_SPACE_MARGIN_BYTES) {
            return Verdict.Rejected("Not enough free space. Importing needs about ${formatBytes(size + FREE_SPACE_MARGIN_BYTES)}.")
        }
        return Verdict.Ok
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
