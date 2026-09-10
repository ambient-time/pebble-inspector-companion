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
    Today("Today"), Conversation("Ask"), Capture("Capture"), History("Activity"), Presence("Nearby"), Sessions("Sessions"), Memory("Memory"), Settings("Settings")
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
    var page by remember { mutableStateOf(if (standalone) SignalPage.Today else SignalPage.Conversation) }
    var questionDraft by remember { mutableStateOf("") }
    var submittedQuestion by remember(state.threadId) { mutableStateOf<String?>(null) }
    var submittedRecordIds by remember(state.threadId) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(state.records, submittedQuestion) {
        val text = submittedQuestion
        if (text != null && state.records.any { it.id !in submittedRecordIds && it.threadId == state.threadId && it.question == text && it.state == "ready" }) {
            if (questionDraft.trim() == text) questionDraft = ""
            submittedQuestion = null
        }
    }
    var fieldTestOpen by remember { mutableStateOf(false) }
    val fieldTestDraft = remember { SignalFieldTestDraft() }
    var detailId by remember { mutableStateOf<String?>(null) }
    var deletionWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.deletionPreview) {
        if (state.deletionPreview != null) deletionWasOpen = true
        else if (deletionWasOpen) {
            deletionWasOpen = false
            if (state.selectedRecordId == null) detailId = null
        }
    }
    var confirmation by remember { mutableStateOf<SignalConfirmation?>(null) }
    var historyQuestion by remember { mutableStateOf(false) }
    var attachmentId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.savedQuestionOpenToken) {
        state.savedQuestionDraft?.let { recipe ->
            questionDraft = recipe.question; historyQuestion = recipe.searchHistory; attachmentId = null
            page = SignalPage.Conversation; detailId = null
        }
    }
    val selected = state.selectedRecord?.takeIf { it.id == detailId } ?: state.records.firstOrNull { it.id == detailId }

    if (!state.initialized) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SignalHeading("Signal Station")
            Text(state.status)
        }
        return
    }
    if (standalone && !state.settings.onboardingComplete) {
        SignalLearningSetup(state, station) { destination ->
            page = when (destination) {
                SignalStartDestination.Capture -> SignalPage.Capture
                SignalStartDestination.Observe -> SignalPage.Sessions
                SignalStartDestination.Ask -> SignalPage.Conversation
            }
        }
        return
    }
    BackHandler(enabled = fieldTestOpen || detailId != null || page != SignalPage.Today) {
        if (fieldTestOpen) fieldTestOpen = false else if (detailId != null) detailId = null else page = SignalPage.Today
    }
    LaunchedEffect(state.threadId) { attachmentId = null }
    LaunchedEffect(page) {
        if (page in setOf(SignalPage.Today, SignalPage.History, SignalPage.Capture, SignalPage.Presence)) station.searchSavedHistory("")
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(if (standalone) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier).imePadding()) {
        run {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SignalAntennaGlyph()
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    SignalHeading("Signal Station")
                    if (state.buildVersion.isNotBlank()) Text(state.buildVersion, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { page = SignalPage.Settings; detailId = null; fieldTestOpen = false }) { Text("Settings") }
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(SignalPage.Today to "Today", SignalPage.Conversation to "Ask", SignalPage.History to "Activity", SignalPage.Memory to "Memory").forEach { (destination, title) ->
                val selected = if (destination == SignalPage.History) page in listOf(SignalPage.History, SignalPage.Capture, SignalPage.Presence, SignalPage.Sessions) else page == destination
                FilterChip(selected = selected, onClick = { page = destination; detailId = null; fieldTestOpen = false }, label = { Text(title) })
            }
        }
        if (page in listOf(SignalPage.History, SignalPage.Capture, SignalPage.Presence, SignalPage.Sessions) && detailId == null && !fieldTestOpen) {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SignalPage.History to "Saved activity", SignalPage.Capture to "Capture", SignalPage.Presence to "Nearby", SignalPage.Sessions to "Sessions").forEach { (destination, title) ->
                    FilterChip(selected = page == destination, onClick = { page = destination }, label = { Text(title) })
                }
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
        if (fieldTestOpen) {
            SignalFieldTestPage(state, station, fieldTestDraft) { fieldTestOpen = false }
        } else if (selected != null) {
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
                onChanges = { station.summarizeChanges(selected.id) },
                onAnalyze = { station.analyzeRecord(selected.id); detailId = null; page = SignalPage.Conversation },
                onSettings = { detailId = null; page = SignalPage.Settings },
                onReference = { detailId = it; station.selectRecord(it) },
                onDelete = { station.previewDeleteRecords(setOf(selected.id)) },
                onDeleteThread = { station.deleteThread(selected.threadId) },
            )
        } else if (detailId != null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { detailId = null }) { Text("Back") }
                Text(if (state.selectedRecordLoading) "Loading saved evidence…" else "This saved evidence is no longer available.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.selectedRecordLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } else when (page) {
            SignalPage.Today -> SignalTodayPage(state, station,
                onAsk = { page = SignalPage.Conversation }, onCapture = { page = SignalPage.Capture },
                onObserve = { page = SignalPage.Sessions }, onMemory = { page = SignalPage.Memory },
                onDetail = { detailId = it; station.selectRecord(it) }, onSources = { page = SignalPage.Settings },
                onNearby = { page = SignalPage.Presence })
            SignalPage.Memory -> SignalMemoryPage(state, station,
                onEvidence = { detailId = it; station.selectRecord(it) }, onCapture = { page = SignalPage.Capture },
                onNearby = { page = SignalPage.Presence }, onSources = { page = SignalPage.Settings })
            SignalPage.Sessions -> SignalSessionsPage(state, station) { page = SignalPage.Settings }
            SignalPage.Capture -> SignalCapturePage(state, station,
                onDetail = { detailId = it; station.selectRecord(it) },
                onSources = { page = SignalPage.Settings },
                onManageWatch = onManageWatch,
                onFieldTest = { fieldTestOpen = true },
            )
            SignalPage.Conversation -> SignalConversation(
                state, station, questionDraft, { questionDraft = it; station.dismissQuestionReview() },
                onSend = {
                    submittedQuestion = questionDraft.trim()
                    submittedRecordIds = state.records.map { it.id }.toSet()
                    station.ask(questionDraft.trim(), historyQuestion)
                },
                searchHistory = historyQuestion,
                attachment =
                attachmentId?.let { id -> state.selectedRecord?.takeIf { it.id == id } ?: state.records.firstOrNull { it.id == id } },
                onHistoryChange = { historyQuestion = it; station.dismissQuestionReview() },
                onDetail = { detailId = it; station.selectRecord(it) },
                onSettings = { page = SignalPage.Settings },
                onNewThread = { station.newThread(); questionDraft = ""; attachmentId = null; historyQuestion = false },
            )
            SignalPage.History -> SignalHistory(
                state, station,
                onDetail = { detailId = it; station.selectRecord(it) },
                onAskHistory = { station.dismissQuestionReview(); historyQuestion = true; page = SignalPage.Conversation },
                onCompare = { first, second ->
                    station.compareRecords(first, second)
                    page = SignalPage.Conversation
                    historyQuestion = false
                },
                onConfirm = { confirmation = it },
            )
            SignalPage.Presence -> SignalPresencePage(station, state) { detailId = it; station.selectRecord(it) }
            SignalPage.Settings -> SignalConfiguration(state, station, onManageWatch) { confirmation = it }
        }
    }
    SignalDeletionDialog(state, station)
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
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    searchHistory: Boolean,
    attachment: SignalRecord?,
    onHistoryChange: (Boolean) -> Unit,
    onDetail: (String) -> Unit,
    onSettings: () -> Unit,
    onNewThread: () -> Unit,
) {
    var saveTitle by remember { mutableStateOf<String?>(null) }
    var saveAsNew by remember { mutableStateOf(false) }
    saveTitle?.let { title -> AlertDialog(onDismissRequest = { saveTitle = null }, title = { Text("Save question") },
        text = { OutlinedTextField(title, { saveTitle = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { station.saveQuestion(title, draft, searchHistory, saveAsNew); saveTitle = null }, enabled = title.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { saveTitle = null }) { Text("Cancel") } }) }
    var voiceOpen by remember { mutableStateOf(false) }
    var contextOpen by remember { mutableStateOf(false) }
    LaunchedEffect(draft) { station.suggestMemory(draft) }
    LaunchedEffect(state.threadId) { station.loadConversation() }
    val records = state.records.filter { it.threadId == state.threadId }.sortedByDescending { it.createdAt }
    val ready = state.settings.provider in state.configuredProviders && state.settings.model.isNotBlank()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SignalHeading("What would you like to ask?")
            if (ready) {
                Text("${providerLabel(state.settings.provider)} · ${state.settings.model}", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Add your provider key and model to get a reply. Your question will stay here while you set up.")
                Button(onClick = onSettings, enabled = !state.busy) { Text("Set up answers") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, onDraft, Modifier.fillMaxWidth(),
                    label = { Text(if (searchHistory) "Question about saved history" else "Your question") },
                    supportingText = { Text("Type or use keyboard dictation. No watch is needed.") },
                    minLines = 2, maxLines = 6, enabled = !state.busy)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { saveAsNew = false; saveTitle = state.savedQuestionDraft?.title ?: draft.take(60) }, enabled = !state.busy && draft.isNotBlank()) { Text(if (state.savedQuestionDraft == null) "Save question" else "Save changes") }
                    if (state.savedQuestionDraft != null) TextButton(onClick = { saveAsNew = true; saveTitle = draft.take(60) }, enabled = !state.busy && draft.isNotBlank()) { Text("Save as new") }
                }
                state.savedQuestionDraft?.let { recipe ->
                    Text("Saved question: ${recipe.title}")
                    Text("Sources: ${recipe.sourceKeys.sorted().joinToString().ifEmpty { "none" }} · ${recipe.attachmentIds.size} saved attachments")
                    if (!state.settings.enabled.containsAll(recipe.sourceKeys)) Text("Some saved sources are disabled. Review them in Settings.")
                    TextButton(onClick = station::dismissSavedQuestion, enabled = !state.busy) { Text("Use current context / remove saved attachments") }
                }
                if (attachment != null) {
                    Text("Attached: ${signalDateTime(attachment.createdAt)} · ${attachment.question}")
                    TextButton(onClick = onNewThread, enabled = !state.busy) { Text("Remove attachment / new conversation") }
                }
                SignalSuggestedMemoryContext(state, station, onDetail)
                if (state.historyLoading) Text("Loading this conversation before sending…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
                Button(onClick = onSend, enabled = !state.busy && !state.historyLoading && draft.isNotBlank(), modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                    Text(if (state.busy) "Working…" else "Review context")
                }
                state.questionReview?.let { review ->
                    SignalHeading("Review before sending")
                    Text("${providerLabel(state.settings.provider)} · ${state.settings.model}")
                    Text("${review.recordCount} records · ${review.memoryCount} memories · ${review.bytes} bytes")
                    Text("History is ranked and bounded. Dates and source restrictions are applied to the evidence below.")
                    if (review.omittedRecords > 0) Text("${review.omittedRecords} records omitted by the size limit.")
                    var showEvidence by remember(review) { mutableStateOf(false) }
                    TextButton(onClick = { showEvidence = !showEvidence }) { Text(if (showEvidence) "Hide exact message text" else "Show exact message text") }
                    if (showEvidence) SelectionContainer { Column { review.messages.forEach { (role, text) -> Text("$role\n$text") } } }
                    Text("Only Send contacts your provider. It may use credits.")
                    Button(onClick = station::sendReviewedQuestion, enabled = !state.busy && ready, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text("Send question") }
                    TextButton(onClick = station::dismissQuestionReview, enabled = !state.busy) { Text("Back to editing") }
                }
                Text(if (searchHistory) "Includes matching saved history from enabled sources."
                    else "Sends your question, attached records, this conversation and any selected memory above. New readings are collected from Capture.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (records.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Conversation", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onNewThread, enabled = !state.busy) { Text("New conversation") }
            }
        }
        items(records, key = { it.id }) { record ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                Text(record.question, style = MaterialTheme.typography.titleMedium)
                SignalResponse(record.answer, record.summary.ifBlank { if (record.state == "working") "Waiting for ${providerLabel(record.provider)}…" else record.state })
                if (record.state in setOf("error", "interrupted", "cancelled")) {
                    Text("Your question is saved. Review it before sending again.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onDraft(record.question) }, enabled = !state.busy) { Text("Edit question") }
                        TextButton(onClick = onSettings, enabled = !state.busy) { Text("Check provider") }
                    }
                }
                TextButton(onClick = { onDetail(record.id) }) { Text("Readings and details") }
            }
        }
        item {
            HorizontalDivider()
            TextButton(onClick = { voiceOpen = !voiceOpen }) { Text(if (voiceOpen) "Hide voice options" else "Voice · Go go gadget & watch") }
            if (voiceOpen || state.wakeDraft.isNotBlank() || state.wakePhase !in setOf("stopped", "error", "denied", "draft")) {
                SignalWakeControls(station, state) { voice -> onDraft(if (draft.isBlank()) voice else "$draft\n\n$voice") }
                OutlinedButton(onClick = station::recordOnWatch, enabled = !state.busy && state.watches.any { it.id == state.settings.watchId && it.connected }) { Text("Dictate on watch") }
                if (state.watches.none { it.id == state.settings.watchId && it.connected }) Text("Choose a watch in Settings to use watch dictation.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            TextButton(onClick = { contextOpen = !contextOpen }) { Text(if (contextOpen) "Hide context options" else "Context · saved history & readings") }
            if (contextOpen || searchHistory) {
                SignalToggle("Ask about saved history", searchHistory, !state.busy,
                    "Sends matching local records to the selected provider.", onHistoryChange)
                SignalSourcePreview(state.sources, state.settings.enabled, "Sources for Capture & analyze")
                OutlinedButton(onClick = station::survey, enabled = ready && !state.busy && state.settings.enabled.isNotEmpty()) { Text("Capture & analyze") }
                TextButton(onClick = onSettings) { Text("Choose sources") }
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
    var toolsOpen by remember { mutableStateOf(false) }
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
            OutlinedTextField(query, { query = it; station.searchSavedHistory(it) }, Modifier.fillMaxWidth(), label = { Text("Search questions, answers, and readings") })
            Text("${state.historyCount} stored records · ${signalStorageLabel(state.storageBytes)} · showing ${state.records.size} records", style = MaterialTheme.typography.bodySmall)
            if (state.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item {
            TextButton(onClick = { toolsOpen = !toolsOpen }) { Text(if (toolsOpen) "Hide history tools" else "Filter, compare & export") }
            if (!toolsOpen && (from.isNotBlank() || to.isNotBlank() || source.isNotBlank())) Text("Date or source filters are active.")
        }
        if (toolsOpen) item {
            OutlinedTextField(from, { from = it }, Modifier.fillMaxWidth(), label = { Text("From date · YYYY-MM-DD") }, singleLine = true, isError = from.isNotBlank() && fromDate == null)
            OutlinedTextField(to, { to = it }, Modifier.fillMaxWidth(), label = { Text("Through date · YYYY-MM-DD") }, singleLine = true, isError = to.isNotBlank() && toDate == null)
            if (dateError) Text("Use valid dates with the start on or before the end.", color = MaterialTheme.colorScheme.error)
            SignalChoice("Source", source, listOf("" to "All sources") + state.sources.map { it.key to it.name }) { source = it }
        }
        if (toolsOpen) item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAskHistory, enabled = !state.busy && state.records.isNotEmpty()) { Text("Ask about history") }
                OutlinedButton(onClick = { val ids = selected.toList(); onCompare(ids[0], ids[1]) }, enabled = selected.size == 2 && !state.busy) { Text("Compare ${selected.size}/2") }
                TextButton(onClick = {
                    onConfirm(SignalConfirmation("Export history?", "The export contains your questions, answers, and enabled or previously saved readings. Choose where to share it.") { station.shareHistory("json") })
                }, enabled = state.records.isNotEmpty() && !state.busy) { Text("Export JSON") }
                TextButton(onClick = {
                    onConfirm(SignalConfirmation("Export history?", "The export contains your questions, answers, and saved readings. Choose where to share it.") { station.shareHistory("markdown") })
                }, enabled = state.records.isNotEmpty() && !state.busy) { Text("Export Markdown") }
                TextButton(onClick = { station.previewDeleteRecords(null) }, enabled = state.historyReady && state.historyCount > 0 && !state.busy) { Text("Review deletion of all history") }
            }
            Text("${records.size} ${if (records.size == 1) "record" else "records"} · select two surveys to compare", style = MaterialTheme.typography.bodySmall)
            Text("Date and source filters apply to this page. Text search searches all stored records. Ask about history uses currently enabled sources.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { station.previewDeleteRecords(records.map { it.id }.toSet()) }, enabled = state.historyReady && records.isNotEmpty() && !state.busy) { Text("Review deletion of this filtered page") }
        }
        if (records.isEmpty()) item { Text(if (!state.historyReady || state.historyLoading) "Loading saved activity…" else if (state.historyCount == 0L) "No saved history yet." else "No records match this search or these page filters.") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.historyHasMore) OutlinedButton(onClick = station::loadMoreHistory, enabled = !state.historyLoading) { Text("Older activity") }
                TextButton(onClick = { query = ""; station.searchSavedHistory("") }, enabled = !state.historyLoading) { Text("Newest activity") }
            }
        }
        items(records, key = { it.id }) { record ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (toolsOpen) Checkbox(
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
    onChanges: () -> Unit,
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
            Text(if (record.provider == "local") "Saved on this phone · ${record.state}" else "${providerLabel(record.provider)} · ${record.model} · ${record.state}")
            if (record.watchId.isNotBlank()) Text("Watch: ${state.watches.firstOrNull { it.id == record.watchId }?.name ?: record.watchId}")
        }
        item { SignalResponse(record.answer, record.summary.ifBlank { "No answer saved." }) }
        if (signalSupportsLocalChanges(record)) item { SignalLocalChangesAction(record, state, onChanges) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAnalyze, enabled = !state.busy && record.state == "ready" && record.observations.isNotEmpty() && state.settings.provider in state.configuredProviders && state.settings.model.isNotBlank() && SignalHistory.allowed(record, state.settings.enabled)) { Text("Analyze readings") }
                OutlinedButton(onClick = onAttach, enabled = !state.busy && record.state == "ready" && SignalHistory.allowed(record, state.settings.enabled)) { Text("Attach to conversation") }
                if (record.kind != "capture" && record.provider != "local") OutlinedButton(onClick = onResume, enabled = !state.busy) { Text("Resume conversation") }
                TextButton(onClick = onDelete, enabled = !state.busy && state.historyReady) { Text("Delete record") }
                TextButton(onClick = onDeleteThread, enabled = !state.busy && state.historyReady) { Text("Delete conversation") }
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
                TextButton(onClick = { onReference(id) }) { Text(reference?.let { "${signalDateTime(it.createdAt)} · ${it.question}" } ?: "Inspect saved evidence") }
            }
        }
        if (record.memoryReferences.isNotEmpty()) item { SignalHeading("Memory used") }
        record.memoryReferences.forEach { (memoryId, revision) ->
            item(key = "memory:$memoryId") {
                val memory = state.memories.firstOrNull { it.id == memoryId }
                if (memory == null) Text("Referenced memory is no longer available · revision $revision")
                else {
                    if (memory.revision != revision) Text("This answer used revision $revision. The current memory is revision ${memory.revision}; its wording and evidence below may have changed.", style = MaterialTheme.typography.bodySmall)
                    else Text("Revision $revision · ${memory.state}", style = MaterialTheme.typography.labelLarge)
                    Text(memory.text)
                    if (!SignalLearning.eligible(memory, state.settings)) Text("This memory is not currently available for new model context.", style = MaterialTheme.typography.bodySmall)
                    if (memory.coverage.isNotBlank()) Text(memory.coverage, style = MaterialTheme.typography.bodySmall)
                    if (memory.evidence.isEmpty()) Text("No observation evidence is attached.", style = MaterialTheme.typography.bodySmall)
                    memory.evidence.forEach { evidence ->
                        TextButton(onClick = { onReference(evidence.recordId) }) { Text("Inspect evidence · ${signalDateTime(evidence.collectedAt)}") }
                    }
                }
            }
        }
        if (record.coverage.isNotEmpty()) item { SignalRecordCoverage(record, state.sources) }
        item { SignalHeading("Readings"); if (record.observations.isEmpty()) Text("No readings attached.") }
        items(record.observations) { reading ->
            HorizontalDivider()
            val name = state.sources.firstOrNull { it.key == reading.key }?.name ?: reading.key
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                if (reading.metric.isNotBlank()) Text(reading.metric.replace('_', ' ').replace('.', ' '), style = MaterialTheme.typography.labelLarge)
                val value = reading.number?.takeIf { it.isFinite() }?.toString() ?: reading.boolean?.toString() ?: reading.value
                SelectionContainer { Text(if (value.isBlank()) reading.status.replace('_', ' ') else "$value ${reading.unit}".trim()) }
                if (reading.value.isNotBlank() && reading.value != value && (reading.number == null || reading.value.toDoubleOrNull() != reading.number)) {
                    SelectionContainer { Text(reading.value, style = MaterialTheme.typography.bodySmall) }
                }
                Text("${reading.source} · ${reading.status.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                reading.sampleCount?.let { Text("${signalCount(it, "sample")}", style = MaterialTheme.typography.bodySmall) }
                reading.accuracy?.let { accuracy ->
                    val label = when (accuracy) { -1 -> "no contact"; 0 -> "unreliable"; 1 -> "low"; 2 -> "medium"; 3 -> "high"; else -> "unknown ($accuracy)" }
                    Text("Sensor accuracy: $label", style = MaterialTheme.typography.bodySmall)
                }
                Text("Collected ${signalDateTime(reading.collectedAt)}", style = MaterialTheme.typography.bodySmall)
                reading.measuredAt?.let { Text("Measured ${signalDateTime(it)}", style = MaterialTheme.typography.bodySmall) }
                reading.date?.let { Text("Reporting date $it", style = MaterialTheme.typography.bodySmall) }
                Text("Period: ${reading.period}", style = MaterialTheme.typography.bodySmall)
                reading.windowStart?.let { start ->
                    Text("From ${signalDateTime(start)}${reading.windowEnd?.let { " through ${signalDateTime(it)}" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                }
                if (reading.windowStart == null) reading.windowEnd?.let { Text("Window ends ${signalDateTime(it)}", style = MaterialTheme.typography.bodySmall) }
                reading.fields.entries.sortedBy { it.key }.forEach { (key, fieldValue) ->
                    SelectionContainer { Text("${key.replace('_', ' ')}: $fieldValue", style = MaterialTheme.typography.bodySmall) }
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
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }
    val pendingProfile = model != settings.model || endpoint != settings.endpoint
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SignalHeading("Settings")
            Text("Capture works without a provider key. Add a key when you want model analysis.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { station.updateSettings(settings.copy(onboardingComplete = false)) }, enabled = !state.busy) { Text("Revisit setup guide") }
                onManageWatch?.let { TextButton(onClick = it) { Text("Watch connection") } }
                OutlinedButton(onClick = station::installWatchApp, enabled = !state.busy && state.watchCapabilities.install && state.watches.any { it.id == settings.watchId && it.connected }) { Text("Install watch app") }
            }
            Text(state.watchCapabilities.description)
            Text("Watch buttons: Up captures readings · Select asks · Down opens history. Your Pebble app keeps ownership of pairing and watch settings.")
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
            Text(if (settings.provider in state.configuredProviders) "Key saved · leave blank to keep it" else "Add your provider key")
            SignalKeyField(key, { key = it }, "Answer provider key", !state.busy)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { station.saveProvider(model, endpoint, key); key = "" },
                    enabled = !state.busy && model.isNotBlank() && (key.isNotBlank() || settings.provider in state.configuredProviders) && (settings.provider != "custom" || endpoint.startsWith("https://"))) { Text("Save answer setup") }
                OutlinedButton(onClick = station::checkAnswerSetup, enabled = !state.busy && !pendingProfile && key.isBlank()) { Text("Check answer setup") }
                OutlinedButton(onClick = station::testProvider, enabled = !state.busy && !pendingProfile && key.isBlank() && settings.model.isNotBlank() && settings.provider in state.configuredProviders) { Text("Test with provider") }
            }
            if (pendingProfile || key.isNotBlank()) Text("Save these changes before testing or asking.", style = MaterialTheme.typography.bodySmall)
            state.diagnostics?.let { report ->
                Text("${report.stage.replace('_', ' ')}: ${report.result.replace('_', ' ')} · ${report.elapsedMs} ms · ${report.payloadBytes} message bytes")
                TextButton(onClick = station::shareDiagnostics, enabled = !state.busy) { Text("Share diagnostic report") }
                Text("The report contains build, stage, result, timing and size only. Your question, keys and readings are excluded.", style = MaterialTheme.typography.bodySmall)
            }
            Text("The test sends a short question using the saved model and key. Provider charges may apply.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                onConfirm(SignalConfirmation("Remove answer key?", "You can add another key later.") { station.saveKey(settings.provider, "") })
            }, enabled = !state.busy && settings.provider in state.configuredProviders) { Text("Remove saved key") }
        }
        item {
            HorizontalDivider()
            SignalLearningControls(state, station)
        }
        item {
            HorizontalDivider()
            SignalHealthControls(state, station)
        }
        item {
            HorizontalDivider()
            SignalHeading("Speech recognition")
            SignalChoice("Recognition", settings.recognition, listOf("stock" to "Pebble app dictation") + if (state.watchCapabilities.customTranscription) listOf("openai" to "OpenAI transcription") else emptyList(), !state.busy) {
                station.updateSettings(settings.copy(recognition = it))
            }
            if (settings.recognition == "openai" && !state.watchCapabilities.customTranscription) Text("Your custom transcription setting is preserved. This connection uses the Pebble app speech service; select Pebble app dictation to use it.")
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
            val watch = state.watches.firstOrNull { it.id == settings.watchId }
            Text(watch?.connectionStatus?.takeIf { it.isNotBlank() }
                ?: "Select a connected watch, then check its Signal Station connection.",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            OutlinedButton(onClick = station::checkWatchConnection, enabled = !state.busy && watch?.connected == true) { Text("Check connection") }
            Text("This opens the watch app and checks its link. It does not start dictation or capture readings.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            HorizontalDivider()
            SignalHeading("Weather location")
            SignalChoice("Weather for", settings.weatherLocation, listOf("place" to "A chosen place", "device" to "Near this phone"), !state.busy) {
                station.updateSettings(settings.copy(weatherLocation = it))
            }
            Text("Weather switches below send a chosen place or your phone position rounded to about 1 km to Open-Meteo during a capture or enabled observation session. Coordinates stay out of model context unless Location is also enabled.")
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
        val sourceIssues = state.sourceStatus.filter { it.key in settings.enabled && signalSourceNeedsAttention(it) }
        if (sourceIssues.isNotEmpty()) item { SignalHeading("Sources to review") }
        items(sourceIssues, key = { "issue:${it.key}" }) { source -> SignalSourceStatusRow(source, state, station) }
        state.sources.groupBy { it.group }.forEach { (group, sources) ->
            item(key = "group:$group") {
                val expanded = group in expandedGroups
                TextButton(onClick = { expandedGroups = if (expanded) expandedGroups - group else expandedGroups + group }) {
                    Text("$group · ${sources.count { it.key in settings.enabled }} selected · ${if (expanded) "Hide" else "Choose"}")
                }
                if (expanded) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { station.updateSettings(settings.copy(enabled = settings.enabled + sources.filter { it.available }.map { it.key })) }, enabled = !state.busy) { Text("Enable $group") }
                    TextButton(onClick = { station.updateSettings(settings.copy(enabled = settings.enabled - sources.map { it.key }.toSet())) }, enabled = !state.busy) { Text("Disable $group") }
                }
            }
            if (group in expandedGroups) items(sources, key = { it.key }) { source ->
                SignalToggle(source.name, source.key in settings.enabled, !state.busy, if (source.available) null else "Unavailable on this device or permission not granted") { enabled ->
                    station.updateSettings(settings.copy(enabled = if (enabled) settings.enabled + source.key else settings.enabled - source.key))
                }
                if (source.key in settings.enabled) state.sourceStatus.firstOrNull { it.key == source.key && !signalSourceNeedsAttention(it) }?.let {
                    SignalSourceStatusRow(it, state, station)
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
