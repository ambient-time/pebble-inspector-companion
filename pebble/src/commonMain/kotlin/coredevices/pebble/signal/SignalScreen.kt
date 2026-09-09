package coredevices.pebble.signal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private enum class SignalPage(val title: String) {
    Capture("Capture"), Conversation("Ask"), History("History"), Presence("Presence"), Settings("Settings")
}

private data class SignalConfirmation(val title: String, val message: String, val action: () -> Unit)

/** Phone workbench. Sensitive drafts deliberately use memory state, never saved-instance state. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SignalScreen(station: SignalStation, standalone: Boolean = false, onManageWatch: (() -> Unit)? = null) {
    if (standalone) SignalMaterialTheme { SignalScreenContent(station, true, onManageWatch) }
    else SignalScreenContent(station, false, onManageWatch)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SignalScreenContent(station: SignalStation, standalone: Boolean, onManageWatch: (() -> Unit)?) {
    val state by station.state.collectAsState()
    var page by remember { mutableStateOf(SignalPage.Capture) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<SignalConfirmation?>(null) }
    var historyQuestion by remember { mutableStateOf(false) }
    var attachmentId by remember { mutableStateOf<String?>(null) }
    val selected = state.records.firstOrNull { it.id == detailId }

    if (!state.initialized) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SignalHeading("Signal Station")
            Text(state.status)
        }
        return
    }
    if (standalone && !state.settings.onboardingComplete) {
        SignalSetup(station, state)
        return
    }
    BackHandler(enabled = detailId != null || page != SignalPage.Capture) {
        if (detailId != null) detailId = null else page = SignalPage.Capture
    }
    LaunchedEffect(state.threadId) { attachmentId = null }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(if (standalone) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier).imePadding()) {
        if (standalone) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SignalAntennaGlyph()
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    SignalHeading("Signal Station")
                    if (state.buildVersion.isNotBlank()) Text(state.buildVersion, style = MaterialTheme.typography.labelSmall)
                }
                onManageWatch?.let { TextButton(onClick = it) { Text("Devices") } }
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SignalPage.entries.forEach { item ->
                FilterChip(
                    selected = page == item,
                    onClick = { page = item; detailId = null },
                    label = { Text(item.title) },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SignalStatus(state) }
            if (state.busy) TextButton(onClick = station::cancel) { Text("Cancel request") }
        }
        if (state.wakePhase !in setOf("stopped", "error", "draft")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.wakeStatus, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = station::stopWakeListening) { Text("Stop listening") }
            }
        } else if (state.wakeDraft.isNotBlank() && page != SignalPage.Conversation) {
            TextButton(onClick = { page = SignalPage.Conversation; detailId = null }) { Text("Review voice draft") }
        }
        if (selected != null) {
            SignalDetail(
                record = selected,
                state = state,
                onBack = { detailId = null },
                onAttach = {
                    station.attachRecord(selected.id)
                    attachmentId = selected.id
                    detailId = null
                    page = SignalPage.Conversation
                    historyQuestion = false
                },
                onResume = {
                    station.resumeThread(selected.threadId)
                    detailId = null
                    page = SignalPage.Conversation
                    historyQuestion = false
                },
                onAnalyze = { station.analyzeRecord(selected.id); detailId = null; page = SignalPage.Conversation },
                onSettings = { detailId = null; page = SignalPage.Settings },
                onReference = { detailId = it },
                onDelete = {
                    confirmation = SignalConfirmation(
                        "Delete this record?",
                        "This also removes dependent analysis and cached excerpts. This cannot be undone.",
                    ) { station.deleteRecord(selected.id); detailId = null }
                },
                onDeleteThread = {
                    confirmation = SignalConfirmation(
                        "Delete this conversation?",
                        "All its messages and dependent reports will be removed. This cannot be undone.",
                    ) { station.deleteThread(selected.threadId); detailId = null }
                },
            )
        } else when (page) {
            SignalPage.Capture -> SignalCapturePage(state, station,
                onDetail = { detailId = it; station.selectRecord(it) },
                onSources = { page = SignalPage.Settings },
                onManageWatch = onManageWatch,
            )
            SignalPage.Conversation -> SignalConversation(
                state, station, historyQuestion,
                attachmentId?.let { id -> state.records.firstOrNull { it.id == id } },
                onHistoryChange = { historyQuestion = it },
                onDetail = { detailId = it; station.selectRecord(it) },
                onSettings = { page = SignalPage.Settings },
                onNewThread = { station.newThread(); attachmentId = null; historyQuestion = false },
            )
            SignalPage.History -> SignalHistory(
                state, station,
                onDetail = { detailId = it; station.selectRecord(it) },
                onAskHistory = { historyQuestion = true; page = SignalPage.Conversation },
                onCompare = { first, second ->
                    station.compareRecords(first, second)
                    page = SignalPage.Conversation
                    historyQuestion = false
                },
                onConfirm = { confirmation = it },
            )
            SignalPage.Presence -> SignalPresencePage(station, state)
            SignalPage.Settings -> SignalConfiguration(state, station, onManageWatch) { confirmation = it }
        }
    }
    confirmation?.let { request ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(request.title) },
            text = { Text(request.message) },
            confirmButton = {
                TextButton(onClick = { confirmation = null; request.action() }) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SignalConversation(
    state: SignalState,
    station: SignalStation,
    searchHistory: Boolean,
    attachment: SignalRecord?,
    onHistoryChange: (Boolean) -> Unit,
    onDetail: (String) -> Unit,
    onSettings: () -> Unit,
    onNewThread: () -> Unit,
) {
    var draft by remember(state.threadId) { mutableStateOf("") }
    val records = state.records.filter { it.threadId == state.threadId }.sortedBy { it.createdAt }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SignalHeading("Ask from here. Read on either screen.")
            Text("${providerLabel(state.settings.provider)} · ${state.settings.model.ifBlank { "Choose a model" }}")
            if (state.settings.provider !in state.configuredProviders) {
                TextButton(onClick = onSettings) { Text("Set up a provider") }
            }
        }
        item { SignalWakeControls(station, state) { voice -> draft = if (draft.isBlank()) voice else "$draft\n\n$voice" } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(if (searchHistory) "Question about saved history" else "Your question") },
                    supportingText = { Text("Type, or use your keyboard’s dictation control.") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                    enabled = !state.busy,
                )
                if (attachment != null) {
                    Text("Attached: ${signalDateTime(attachment.createdAt)} · ${attachment.question}")
                    TextButton(onClick = onNewThread, enabled = !state.busy) { Text("Remove attachment / new conversation") }
                }
                SignalToggle(
                    "Ask about saved history", searchHistory, !state.busy,
                    "Retrieves matching local records and sends relevant evidence to the selected provider.",
                    onHistoryChange,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { station.ask(draft.trim(), searchHistory); draft = "" },
                        enabled = !state.busy && draft.isNotBlank(),
                    ) { Text("Send") }
                    OutlinedButton(onClick = station::recordOnWatch, enabled = !state.busy && state.watches.any { it.id == state.settings.watchId && it.connected }) {
                        Text("Record on Watch")
                    }
                    OutlinedButton(onClick = station::survey, enabled = !state.busy && state.settings.enabled.isNotEmpty()) { Text("Capture & analyze") }
                    TextButton(onClick = onNewThread, enabled = !state.busy) { Text("New conversation") }
                }
                Text(
                    if (state.settings.enabled.isEmpty()) "No collection sources enabled. Choose them in Settings."
                    else "Capture & analyze sends ${state.settings.enabled.size} enabled ${if (state.settings.enabled.size == 1) "source" else "sources"} to ${providerLabel(state.settings.provider)}. Inspect and change sources in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.watches.none { it.id == state.settings.watchId && it.connected }) {
                    Text("No selected watch connected. Phone chat and available phone readings still work.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (records.isEmpty()) item {
            Text("Your conversation will appear here. Completed questions and surveys stay on this phone until deleted.")
        }
        items(records, key = { it.id }) { record ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                Text(signalDateTime(record.createdAt), style = MaterialTheme.typography.labelMedium)
                SignalHeading(record.question)
                SelectionContainer { Text(record.answer.ifBlank { record.state }) }
                if (record.references.isNotEmpty()) Text("Based on ${record.references.size} saved records", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onDetail(record.id) }) { Text("Readings and details") }
            }
        }
    }
}

@Composable
private fun SignalHistory(
    state: SignalState,
    station: SignalStation,
    onDetail: (String) -> Unit,
    onAskHistory: () -> Unit,
    onCompare: (String, String) -> Unit,
    onConfirm: (SignalConfirmation) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val fromDate = from.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val toDate = to.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val dateError = (from.isNotBlank() && fromDate == null) || (to.isNotBlank() && toDate == null) || (fromDate != null && toDate != null && fromDate > toDate)
    val records = state.records.filter { record ->
        val date = Instant.fromEpochMilliseconds(record.createdAt).toLocalDateTime(TimeZone.currentSystemDefault()).date
        !dateError && (fromDate == null || date >= fromDate) && (toDate == null || date <= toDate) &&
            (source.isEmpty() || source in record.sourceKeys || record.observations.any { it.source == source || it.key == source }) &&
            (query.isBlank() || listOf(record.question, record.answer, record.summary).any { it.contains(query, ignoreCase = true) } || record.observations.any { "${it.key} ${it.value}".contains(query, ignoreCase = true) })
    }.sortedByDescending { it.createdAt }
    LaunchedEffect(state.records) { selected = selected.intersect(state.records.map { it.id }.toSet()) }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SignalHeading("History")
            Text("Saved on this phone. Searching here is local; asking about history sends matching evidence to your provider.")
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search questions, answers, and readings") })
        }
        item {
            OutlinedTextField(from, { from = it }, Modifier.fillMaxWidth(), label = { Text("From date · YYYY-MM-DD") }, singleLine = true, isError = from.isNotBlank() && fromDate == null)
            OutlinedTextField(to, { to = it }, Modifier.fillMaxWidth(), label = { Text("Through date · YYYY-MM-DD") }, singleLine = true, isError = to.isNotBlank() && toDate == null)
            if (dateError) Text("Use valid dates with the start on or before the end.", color = MaterialTheme.colorScheme.error)
            SignalChoice("Source", source, listOf("" to "All sources") + state.sources.map { it.key to it.name }) { source = it }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAskHistory, enabled = !state.busy && state.records.isNotEmpty()) { Text("Ask about history") }
                OutlinedButton(onClick = { val ids = selected.toList(); onCompare(ids[0], ids[1]) }, enabled = selected.size == 2 && !state.busy) { Text("Compare ${selected.size}/2") }
                TextButton(onClick = {
                    onConfirm(SignalConfirmation("Export history?", "The export contains your questions, answers, and enabled or previously saved readings. Choose where to share it.") { station.shareHistory("json") })
                }, enabled = state.records.isNotEmpty() && !state.busy) { Text("Export JSON") }
                TextButton(onClick = {
                    onConfirm(SignalConfirmation("Export history?", "The export contains your questions, answers, and saved readings. Choose where to share it.") { station.shareHistory("markdown") })
                }, enabled = state.records.isNotEmpty() && !state.busy) { Text("Export Markdown") }
                TextButton(onClick = {
                    onConfirm(SignalConfirmation("Clear all history?", "All conversations, surveys, and saved analysis on this phone will be deleted. Provider settings remain. This cannot be undone.") { station.clearHistory() })
                }, enabled = state.records.isNotEmpty() && !state.busy) { Text("Clear all") }
            }
            Text("${records.size} ${if (records.size == 1) "record" else "records"} · select two surveys to compare", style = MaterialTheme.typography.bodySmall)
            Text("Filters above affect this list. Ask about history searches saved records with currently enabled sources.", style = MaterialTheme.typography.bodySmall)
        }
        if (records.isEmpty()) item { Text(if (state.records.isEmpty()) "No saved history yet." else "No records match these filters.") }
        items(records, key = { it.id }) { record ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = record.id in selected,
                    onCheckedChange = { checked -> selected = if (checked) selected + record.id else selected - record.id },
                    enabled = !state.busy && record.observations.isNotEmpty() && (record.id in selected || selected.size < 2),
                    modifier = Modifier.semantics { contentDescription = "Compare survey from ${signalDateTime(record.createdAt)}" },
                )
                Column(Modifier.weight(1f)) {
                    Text(signalDateTime(record.createdAt), style = MaterialTheme.typography.labelMedium)
                    Text(record.question, style = MaterialTheme.typography.titleMedium)
                    Text("${providerLabel(record.provider)} · ${record.state}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onDetail(record.id) }) { Text("Open record") }
                }
            }
        }
    }
}

@Composable
private fun SignalDetail(
    record: SignalRecord,
    state: SignalState,
    onBack: () -> Unit,
    onAttach: () -> Unit,
    onResume: () -> Unit,
    onAnalyze: () -> Unit,
    onSettings: () -> Unit,
    onReference: (String) -> Unit,
    onDelete: () -> Unit,
    onDeleteThread: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("Back to records") }
            Text(signalDateTime(record.createdAt), style = MaterialTheme.typography.labelLarge)
            SignalHeading(record.question)
            Text(if (record.kind == "capture") "Saved on this phone · ${record.state}" else "${providerLabel(record.provider)} · ${record.model} · ${record.state}")
            if (record.watchId.isNotBlank()) Text("Watch: ${state.watches.firstOrNull { it.id == record.watchId }?.name ?: record.watchId}")
        }
        item { SelectionContainer { Text(record.answer.ifBlank { record.summary.ifBlank { "No answer saved." } }) } }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAnalyze, enabled = !state.busy && record.state == "ready" && record.observations.isNotEmpty() && state.settings.provider in state.configuredProviders && state.settings.model.isNotBlank() && SignalHistory.allowed(record, state.settings.enabled)) { Text("Analyze readings") }
                OutlinedButton(onClick = onAttach, enabled = !state.busy && record.state == "ready" && SignalHistory.allowed(record, state.settings.enabled)) { Text("Attach to conversation") }
                if (record.kind != "capture" && record.provider != "local") OutlinedButton(onClick = onResume, enabled = !state.busy) { Text("Resume conversation") }
                TextButton(onClick = onDelete, enabled = !state.busy) { Text("Delete record") }
                TextButton(onClick = onDeleteThread, enabled = !state.busy) { Text("Delete conversation") }
            }
        }
        if (record.observations.isNotEmpty() && (!SignalHistory.allowed(record, state.settings.enabled) || state.settings.provider !in state.configuredProviders || state.settings.model.isBlank())) item {
            Text(if (!SignalHistory.allowed(record, state.settings.enabled))
                "Some sources used in this record are currently disabled. You can inspect the saved readings here; enable their sources in Settings before analysis."
            else "Add a provider key and model in Settings to analyze these saved readings. The capture stays available without a key.")
            TextButton(onClick = onSettings) { Text("Open Settings") }
        }
        if (record.references.isNotEmpty()) item {
            SignalHeading("Evidence used")
            record.references.distinct().forEach { id ->
                val reference = state.records.firstOrNull { it.id == id }
                if (reference == null) Text("Referenced record unavailable")
                else TextButton(onClick = { onReference(id) }) { Text("${signalDateTime(reference.createdAt)} · ${reference.question}") }
            }
        }
        item { SignalHeading("Readings"); if (record.observations.isEmpty()) Text("No readings attached.") }
        items(record.observations) { reading ->
            HorizontalDivider()
            val name = state.sources.firstOrNull { it.key == reading.key }?.name ?: reading.key
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(if (reading.value.isBlank()) reading.status else "${reading.value} ${reading.unit}".trim()) }
                Text("${reading.source} · ${reading.status}", style = MaterialTheme.typography.bodySmall)
                Text("Collected ${signalDateTime(reading.collectedAt)}", style = MaterialTheme.typography.bodySmall)
                reading.measuredAt?.let { Text("Measured ${signalDateTime(it)}", style = MaterialTheme.typography.bodySmall) }
                reading.date?.let { Text("Reporting date $it", style = MaterialTheme.typography.bodySmall) }
                Text("Period: ${reading.period}", style = MaterialTheme.typography.bodySmall)
                reading.windowStart?.let { start ->
                    Text("From ${signalDateTime(start)}${reading.windowEnd?.let { " through ${signalDateTime(it)}" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                }
                if (reading.key !in state.settings.enabled) Text("Currently disabled; retained locally in this record.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SignalConfiguration(state: SignalState, station: SignalStation, onManageWatch: (() -> Unit)?, onConfirm: (SignalConfirmation) -> Unit) {
    val settings = state.settings
    val uriHandler = LocalUriHandler.current
    var model by remember(settings.provider, settings.model) { mutableStateOf(settings.model) }
    var endpoint by remember(settings.provider, settings.endpoint) { mutableStateOf(settings.endpoint) }
    var key by remember(settings.provider) { mutableStateOf("") }
    var recognitionKey by remember { mutableStateOf("") }
    var weatherQuery by remember { mutableStateOf("") }
    val pendingProfile = model != settings.model || endpoint != settings.endpoint
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SignalHeading("Setup")
            Text("Capture works without a provider key. Add a key when you want model analysis.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { station.updateSettings(settings.copy(onboardingComplete = false)) }, enabled = !state.busy) { Text("Revisit setup guide") }
                onManageWatch?.let { TextButton(onClick = it) { Text("Pair or manage watch") } }
                OutlinedButton(onClick = station::installWatchApp, enabled = !state.busy && state.watches.any { it.id == settings.watchId && it.connected }) { Text("Install watch app") }
            }
            if (state.installStatus.isNotBlank()) Text(state.installStatus)
            SignalHeading("Answer provider")
        }
        item {
            SignalChoice("Provider", settings.provider, providerOptions, enabled = !state.busy) { provider ->
                station.updateSettings(settings.copy(provider = provider, model = if (provider == "openai") "gpt-4.1-mini" else "", endpoint = ""))
            }
            Text("Changing providers starts a new conversation. Previous history stays on this phone.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model ID") }, singleLine = true, enabled = !state.busy)
            if (settings.provider == "custom") {
                OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS endpoint") }, supportingText = { Text("OpenAI-compatible Chat Completions endpoint") }, singleLine = true, enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { station.updateSettings(settings.copy(model = model.trim(), endpoint = endpoint.trim())) }, enabled = !state.busy && model.isNotBlank() && (settings.provider != "custom" || endpoint.startsWith("https://"))) { Text("Save model and endpoint") }
                OutlinedButton(onClick = station::testProvider, enabled = !state.busy && !pendingProfile && settings.model.isNotBlank() && settings.provider in state.configuredProviders) { Text("Test provider") }
            }
            Text("Test sends a small request without capture information. Provider charges may apply.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text(if (settings.provider in state.configuredProviders) "Answer key saved" else "Answer key not configured")
            SignalKeyField(key, { key = it }, "Answer provider key", !state.busy)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { station.saveKey(settings.provider, key.trim()); key = "" }, enabled = !state.busy && key.isNotBlank()) { Text("Save answer key") }
                TextButton(onClick = {
                    onConfirm(SignalConfirmation("Remove answer key?", "Active work will be cancelled. You can add another key later.") { station.saveKey(settings.provider, "") })
                }, enabled = !state.busy && settings.provider in state.configuredProviders) { Text("Remove key") }
            }
        }
        item {
            HorizontalDivider()
            SignalHeading("Speech recognition")
            SignalChoice("Recognition", settings.recognition, listOf("openai" to "OpenAI transcription", "stock" to "Companion dictation"), !state.busy) {
                station.updateSettings(settings.copy(recognition = it))
            }
            if (settings.recognition == "openai") {
                Text("Uses gpt-transcribe. The recognition key is separate from the answer key.")
                Text(if ("transcription" in state.configuredProviders) "Recognition key saved" else "Recognition key not configured")
                SignalKeyField(recognitionKey, { recognitionKey = it }, "OpenAI recognition key", !state.busy)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { station.saveKey("transcription", recognitionKey.trim()); recognitionKey = "" }, enabled = !state.busy && recognitionKey.isNotBlank()) { Text("Save recognition key") }
                    TextButton(onClick = {
                        onConfirm(SignalConfirmation("Remove recognition key?", "Watch speech input through OpenAI will be unavailable until another key is saved.") { station.saveKey("transcription", "") })
                    }, enabled = !state.busy && "transcription" in state.configuredProviders) { Text("Remove recognition key") }
                }
            }
            SignalToggle("Confirm transcript on watch", settings.confirmTranscript, !state.busy, onChange = { station.updateSettings(settings.copy(confirmTranscript = it)) })
            SignalToggle("Reduce watch motion", settings.reducedMotion, !state.busy, onChange = { station.updateSettings(settings.copy(reducedMotion = it)) })
        }
        item {
            HorizontalDivider()
            SignalHeading("Selected watch")
            SignalChoice("Watch", settings.watchId, listOf("" to "No watch selected") + state.watches.map { it.id to "${it.name} · ${if (it.connected) "connected" else "disconnected"}" }, !state.busy) {
                station.updateSettings(settings.copy(watchId = it))
            }
        }
        item {
            HorizontalDivider()
            SignalHeading("Weather location")
            SignalChoice("Weather for", settings.weatherLocation, listOf("place" to "A chosen place", "device" to "Near this phone"), !state.busy) {
                station.updateSettings(settings.copy(weatherLocation = it))
            }
            Text("Weather switches below send a chosen place or your phone position rounded to about 1 km to Open-Meteo when you tap Capture. Coordinates stay out of model context unless Location is also enabled.")
            if (settings.weatherLocation == "place") {
                Text(settings.weatherPlace?.let { "Saved place: ${it.name}" } ?: "Choose a place before collecting weather.")
                OutlinedTextField(weatherQuery, { weatherQuery = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("City and country") }, singleLine = true, enabled = !state.busy)
                OutlinedButton(onClick = { station.searchWeatherPlaces(weatherQuery) }, enabled = !state.busy && !state.weatherSearching && weatherQuery.trim().length >= 2) { Text("Find places") }
                Text("Find places sends this search to Open-Meteo. It does not use phone location.", style = MaterialTheme.typography.bodySmall)
                if (state.weatherSearchStatus.isNotBlank()) Text(state.weatherSearchStatus, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                state.weatherPlaces.forEach { place ->
                    TextButton(onClick = { station.updateSettings(settings.copy(weatherPlace = place)) }, enabled = !state.busy) { Text(place.name) }
                }
                if (settings.weatherPlace != null) TextButton(onClick = { station.updateSettings(settings.copy(weatherPlace = null)) }, enabled = !state.busy) { Text("Forget saved place") }
            }
            Text("Weather: Open-Meteo · air quality and UV: CAMS · place names: GeoNames. Modeled conditions may differ from your surroundings. Free service for this noncommercial experiment.", style = MaterialTheme.typography.bodySmall)
            FlowRow {
                TextButton(onClick = { uriHandler.openUri("https://open-meteo.com/") }) { Text("Open-Meteo") }
                TextButton(onClick = { uriHandler.openUri("https://atmosphere.copernicus.eu/") }) { Text("CAMS") }
                TextButton(onClick = { uriHandler.openUri("https://www.geonames.org/") }) { Text("GeoNames") }
            }
        }
        item {
            HorizontalDivider()
            SignalHeading("Collection sources")
            OutlinedButton(onClick = station::requestPermissions, enabled = !state.busy) { Text("Grant collection permissions") }
            TextButton(onClick = station::openPermissionSettings, enabled = !state.busy) { Text("Open Android permissions") }
            Text("Enable only what you want included in a capture. Each source is optional. Disabling a source clears active context; saved records remain locally viewable.")
        }
        state.sources.groupBy { it.group }.forEach { (group, sources) ->
            item(key = "group:$group") {
                SignalHeading(group)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { station.updateSettings(settings.copy(enabled = settings.enabled + sources.filter { it.available }.map { it.key })) }, enabled = !state.busy) { Text("Enable $group") }
                    TextButton(onClick = { station.updateSettings(settings.copy(enabled = settings.enabled - sources.map { it.key }.toSet())) }, enabled = !state.busy) { Text("Disable $group") }
                }
            }
            items(sources, key = { it.key }) { source ->
                SignalToggle(source.name, source.key in settings.enabled, !state.busy, if (source.available) null else "Unavailable on this device or permission not granted") { enabled ->
                    station.updateSettings(settings.copy(enabled = if (enabled) settings.enabled + source.key else settings.enabled - source.key))
                }
            }
        }
    }
}

@Composable
private fun SignalStatus(state: SignalState) {
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.status, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SignalHeading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
internal fun SignalToggle(label: String, checked: Boolean, enabled: Boolean = true, description: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun SignalKeyField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        singleLine = true, enabled = enabled,
        supportingText = { Text("Stored securely on this phone; never shown after saving.") },
    )
}

@Composable
internal fun SignalChoice(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean = true, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${options.firstOrNull { it.first == value }?.second ?: value}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onChange(id) })
            }
        }
    }
}

private val providerOptions = listOf(
    "openai" to "OpenAI", "anthropic" to "Anthropic", "gemini" to "Google Gemini",
    "xai" to "xAI", "openrouter" to "OpenRouter", "custom" to "Custom endpoint",
)
private fun providerLabel(value: String) = if (value == "local") "On this phone" else providerOptions.firstOrNull { it.first == value }?.second ?: value
internal fun signalDateTime(value: Long): String = Instant.fromEpochMilliseconds(value)
    .toLocalDateTime(TimeZone.currentSystemDefault()).toString().replace('T', ' ').substringBefore('.')
