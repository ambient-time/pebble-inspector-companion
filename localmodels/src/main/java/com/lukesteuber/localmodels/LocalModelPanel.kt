package com.lukesteuber.localmodels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

/** Embedded in each companion's existing Answers settings. Nothing downloads on entry. */
@Composable fun LocalModelPanel(provider: String) {
    val context = LocalContext.current
    val runtime = remember(context) { LocalModels.get(context) }
    LocalModelControls(provider, runtime)
}

@Composable private fun LocalModelControls(provider: String, runtime: LocalModels) {
    val scope = rememberCoroutineScope()
    val nano by runtime.nanoAvailability.collectAsState()
    val busy by runtime.busy.collectAsState()
    val importing by runtime.importing.collectAsState()
    var progress by remember { mutableStateOf(ModelDownloadProgress(ModelDownloadProgress.State.IDLE)) }
    var installed by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var confirmDownload by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var unmetered by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = (e as? LocalModelFailure)?.message ?: "This operation could not finish. Try again." }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runtime.importModel(it) { result -> message = result } }
    }
    LaunchedEffect(provider) {
        runtime.checkNano()
        while (isActive) {
            progress = runtime.downloadProgress()
            installed = runtime.installedLabel()
            runtime.filesChanged()
            delay(1_000)
        }
    }
    Text("On-device setup", style = MaterialTheme.typography.titleMedium)
    Text("Answers run on this phone. Local failures never switch to a cloud provider. Pebble dictation and optional hosted speech have their own network settings.")
    if (provider == LocalModelPolicy.NANO) {
        Text(when (nano) {
            NanoAvailability.READY -> "Gemini Nano is available. Keep this phone app open on screen while asking."
            NanoAvailability.DOWNLOADABLE -> "Android offers a system-model download for this phone."
            NanoAvailability.DOWNLOADING -> "Android is downloading the system model."
            NanoAvailability.UNSUPPORTED -> "Android does not offer Gemini Nano prompting on this phone."
            NanoAvailability.UNKNOWN -> "System-model availability has not been checked."
            NanoAvailability.UNAVAILABLE -> "System-model availability could not be checked. Try again."
        }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text("Nano cannot answer with the phone app in the background, including from a foreground service. Use downloaded Gemma for testing watch requests with the phone put away.")
        OutlinedButton(onClick = { action { checking = true; try { runtime.checkNano() } finally { checking = false } } }, enabled = !checking && !busy) {
            Text(if (checking) "Checking…" else "Check availability")
        }
        if (nano == NanoAvailability.DOWNLOADABLE) Button(onClick = { action { runtime.downloadNano() } }, enabled = !busy) { Text("Download Android system model") }
        Text("Provided by Android AICore under Google's ML Kit terms. Device support, quotas and model versions can change.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text(installed?.let { "$it installed · setup test still separate" } ?: "No local Gemma model installed.")
        Text("Gemma 4 E2B · 2.59 GB download (2.41 GiB) · Apache 2.0 · Hugging Face LiteRT Community. Each app keeps its own private copy, excluded from backups.")
        if (progress.isActive) {
            Text(when (progress.state) {
                ModelDownloadProgress.State.VERIFYING -> "Checking the model checksum…"
                ModelDownloadProgress.State.CANCELLING -> "Stopping download safely…"
                ModelDownloadProgress.State.WAITING_FOR_NETWORK -> "Waiting for the selected network. Partial download is kept."
                else -> "Downloaded ${ModelImportRules.formatBytes(progress.bytes)} of 2.41 GiB"
            })
            LinearProgressIndicator(progress = { (progress.bytes.toFloat() / GemmaArtifact.MODEL.bytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = runtime::cancelDownload) { Text("Cancel download") }
        } else {
            if (progress.state == ModelDownloadProgress.State.FAILED) Text(progress.message ?: "The download failed. Retry when ready.")
            OutlinedButton(onClick = { confirmDownload = true }, enabled = !busy && importing == null && installed == null) { Text("Download or resume Gemma · 2.59 GB") }
        }
        importing?.let {
            Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            OutlinedButton(onClick = runtime::cancelImport) { Text("Cancel import") }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy && importing == null && !progress.isActive) { Text("Import portable .litertlm model") }
        Text("Imported files are your chosen models, not verified copies of the official download. GPU/CPU builds only. No model runs just by opening these settings.", style = MaterialTheme.typography.bodySmall)
        if (installed != null || progress.bytes > 0) TextButton(onClick = { confirmRemove = true }, enabled = !busy && importing == null && !progress.isActive) { Text("Remove local model files") }
        Text("First answers may take longer while loading. Android can stop background work; keep the app open if a watch request cannot start.")
    }
    if (message.isNotEmpty()) Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (confirmDownload) AlertDialog(onDismissRequest = { confirmDownload = false }, title = { Text("Download 2.59 GB to this phone?") },
        text = { Column {
            Text("Gemma 4 E2B from Hugging Face LiteRT Community. The exact size and SHA-256 are checked before use. No account or provider key is needed.")
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = unmetered, role = Role.Checkbox, onValueChange = { unmetered = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(unmetered, null); Text("Unmetered network only")
            }
        } }, confirmButton = { TextButton(onClick = { confirmDownload = false; action { runtime.downloadGemma(unmetered) } }) { Text("Download") } },
        dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("Not now") } })
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Remove local model files?") },
        text = { Text("Removes imported, downloaded and partial Gemma files from this app. Chats, cloud keys and Android's system model are kept.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; action { runtime.removeModel(); message = "Local model files removed." } }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep model") } })
}
