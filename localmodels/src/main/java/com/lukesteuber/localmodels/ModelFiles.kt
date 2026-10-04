package com.lukesteuber.localmodels

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class ModelFiles(private val context: Context) {
    val directory = ModelDownloads.modelDirectory(context)
    val imported = File(directory, "imported.litertlm")
    val name = File(directory, "imported.name")
    val downloaded get() = GemmaModelStore(directory)
    fun active(): File? = imported.takeIf { it.isFile && it.length() >= ModelImportRules.MINIMUM_MODEL_BYTES }
        ?: downloaded.model.takeIf { downloaded.isInstalled() }
    fun label(): String? = active()?.let {
        if (it == imported) "Imported model" else GemmaArtifact.MODEL.displayName
    }
    fun checkSize(fileName: String, size: Long?, credit: Long = 0) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val verdict = ModelImportRules.check(fileName, size, directory.apply { mkdirs() }.usableSpace + credit,
            memory.totalMem, manager.isLowRamDevice)
        if (verdict is ModelImportRules.Verdict.Rejected) throw LocalModelFailure(verdict.reason)
    }
    suspend fun import(uri: Uri, progress: (Long) -> Unit) {
        var displayName = ""
        var expected: Long? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                displayName = it.getString(0).orEmpty()
                if (!it.isNull(1)) expected = it.getLong(1).takeIf { size -> size >= 0 }
            }
        }
        if (listOf("_google_tensor_", "_qualcomm_", ".qualcomm.", "mediatek").any { displayName.lowercase().contains(it) })
            throw LocalModelFailure("This build accepts portable GPU/CPU models, not accelerator-specific files.")
        checkSize(displayName, expected)
        val pending = File(directory, "import.part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                pending.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (expected != null && total > expected!!) throw LocalModelFailure("The file exceeded its stated size.")
                        // Repeat the RAM and remaining disk checks even if the document had no size.
                        checkSize(displayName, total.coerceAtLeast(ModelImportRules.MINIMUM_MODEL_BYTES), pending.length())
                        output.write(buffer, 0, count)
                        progress(total)
                    }
                }
            } ?: throw LocalModelFailure("Android could not open this model file.")
            checkSize(displayName, pending.length(), pending.length())
            if (expected != null && pending.length() != expected) throw LocalModelFailure("The model file is incomplete.")
            currentCoroutineContext().ensureActive()
            Files.move(pending.toPath(), imported.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }
    fun remove() {
        listOf(imported, name).forEach {
            if (it.exists() && !it.delete()) throw LocalModelFailure("Could not remove all model files. Try again.")
        }
        downloaded.remove()
    }
}
