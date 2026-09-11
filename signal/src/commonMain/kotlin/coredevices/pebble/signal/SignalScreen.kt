package coredevices.pebble.signal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private data class SignalConfirmation(val title: String, val message: String, val action: () -> Unit)

/** Phone workbench. Sensitive drafts deliberately use memory state, never saved-instance state. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SignalScreen(station: SignalStation, standalone: Boolean = false, onManageWatch: (() -> Unit)? = null,
    uiSession: SignalUiSession = remember { SignalUiSession() }) {
    androidx.compose.runtime.CompositionLocalProvider(LocalSignalUiSession provides uiSession) {
        if (standalone) SignalMaterialTheme { SignalScreenContent(station, true, onManageWatch) }
        else SignalScreenContent(station, false, onManageWatch)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SignalScreenContent(station: SignalStation, standalone: Boolean, onManageWatch: (() -> Unit)?) {
    val state by station.state.collectAsState()
    val ui = LocalSignalUiSession.current
    var page by object : kotlin.properties.ReadWriteProperty<Any?, SignalPage> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = ui.route.page
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: SignalPage) { ui.navigate(value) }
    }
    var questionDraft by ui::questionDraft
    var submittedQuestion by remember(state.threadId) { mutableStateOf<String?>(null) }
    var submittedRecordIds by remember(state.threadId) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(state.records, submittedQuestion) {
        val text = submittedQuestion
        if (text != null && state.records.any { it.id !in submittedRecordIds && it.threadId == state.threadId && it.question == text && it.state == "ready" }) {
            if (questionDraft.trim() == text) questionDraft = ""
            submittedQuestion = null
        }
    }
    var fieldTestOpen by object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = ui.route.fieldTest
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            if (value) { ui.stack = ui.stack + ui.route; ui.route = ui.route.copy(fieldTest = true) }
            else if (ui.route.fieldTest) ui.back()
        }
    }
    val fieldTestDraft = remember(ui) { ui.values.getOrPut("fieldDraft") { SignalFieldTestDraft() } as SignalFieldTestDraft }
    var detailId by object : kotlin.properties.ReadWriteProperty<Any?, String?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = ui.route.detailId
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String?) {
            if (value != null && value != ui.route.detailId) ui.detail(value)
            else if (value == null && ui.route.detailId != null) ui.back()
        }
    }
    var deletionWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.deletionPreview) {
        if (state.deletionPreview != null) deletionWasOpen = true
        else if (deletionWasOpen) {
            deletionWasOpen = false
            if (state.selectedRecordId == null) detailId = null
        }
    }
    var confirmation by remember { mutableStateOf<SignalConfirmation?>(null) }
    var historyQuestion by ui::historyQuestion
    LaunchedEffect(state.questionDraftToken) {
        if (state.questionDraftToken.isNotBlank() && state.questionDraftToken != ui.consumedQuestionToken) {
            ui.consumedQuestionToken = state.questionDraftToken
            ui.closeAskPanel()
            questionDraft = state.questionDraft
            historyQuestion = false
            page = SignalPage.Conversation
            detailId = null
        }
    }
    LaunchedEffect(state.savedQuestionOpenToken) {
        state.savedQuestionDraft?.takeIf { state.savedQuestionOpenToken != ui.consumedSavedQuestionToken }?.let { recipe ->
            ui.consumedSavedQuestionToken = state.savedQuestionOpenToken
            ui.closeAskPanel()
            questionDraft = recipe.question; historyQuestion = recipe.searchHistory
            page = SignalPage.Conversation; detailId = null
        }
    }
    LaunchedEffect(detailId) { detailId?.let(station::selectRecord) }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
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
                SignalStartDestination.Capture -> SignalPage.Today
                SignalStartDestination.Observe -> SignalPage.Live
                SignalStartDestination.Ask -> SignalPage.Conversation
            }
        }
        return
    }
    BackHandler(enabled = fieldTestOpen || detailId != null || page != SignalPage.Today) {
        ui.back()
    }
    LaunchedEffect(page) {
        if (page in setOf(SignalPage.Today, SignalPage.History, SignalPage.Capture, SignalPage.Presence, SignalPage.Sessions)) station.searchSavedHistory("")
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(if (standalone) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier).imePadding()) {
        if (!keyboardOpen) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SignalAntennaGlyph()
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    SignalHeading("Signal Station")
                }
                TextButton(onClick = { ui.settingsSection = "menu"; page = SignalPage.Settings }) { Text("Settings") }
            }
        }
        if (page !in setOf(SignalPage.Today, SignalPage.Conversation, SignalPage.History) && detailId == null && !fieldTestOpen) {
            TextButton(onClick = { ui.back() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Back")
            }
        }
        if (state.busy) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { SignalStatus(state) }
                TextButton(onClick = station::cancel) { Text("Cancel") }
            }
        }
        if (!state.busy && state.status.isNotBlank() && state.status !in setOf("Ask a question, or capture readings to explore later.", "Settings saved. Collection runs only when requested.", "New conversation.", "Credential saved on this phone.")) {
            Text(state.status, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.wakePhase !in setOf("stopped", "error", "draft")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.wakeStatus, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = station::stopWakeListening) { Text("Stop listening") }
            }
        } else if (state.wakeDraft.isNotBlank() && page != SignalPage.Conversation) {
            TextButton(onClick = { page = SignalPage.Conversation; detailId = null }) { Text("Review voice draft") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        if (fieldTestOpen) {
            SignalFieldTestPage(state, station, fieldTestDraft) { fieldTestOpen = false }
        } else if (selected != null) {
            SignalDetail(
                record = selected,
                state = state,
                onBack = { detailId = null },
                onAttach = {
                    station.attachRecord(selected.id)
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
                onSettings = { page = SignalPage.Settings },
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
                onAsk = { page = SignalPage.Conversation }, onCapture = station::capture,
                onCaptureTools = { page = SignalPage.Capture },
                onAnalyze = { station.analyzeRecord(it); page = SignalPage.Conversation },
                onObserve = { page = SignalPage.Sessions }, onMemory = { page = SignalPage.Memory },
                onDetail = { detailId = it; station.selectRecord(it) }, onSources = { page = SignalPage.Sources },
                onNearby = { page = SignalPage.Presence }, onLive = { page = SignalPage.Live })
            SignalPage.Live -> SignalAroundPage(state, station, onSources = { page = SignalPage.Sources }, onDetail = { detailId = it; station.selectRecord(it) })
            SignalPage.Memory -> SignalMemoryPage(state, station,
                onEvidence = { detailId = it; station.selectRecord(it) }, onCapture = { page = SignalPage.Capture },
                onNearby = { page = SignalPage.Presence }, onSources = { page = SignalPage.Sources })
            SignalPage.Sessions -> SignalSessionsPage(state, station, onSources = { page = SignalPage.Sources }, onDetail = { station.selectRecord(it); detailId = it }, onFieldTest = { fieldTestOpen = true })
            SignalPage.Capture -> SignalCapturePage(state, station,
                onDetail = { detailId = it; station.selectRecord(it) },
                onSources = { page = SignalPage.Sources },
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
                onHistoryChange = { historyQuestion = it; station.dismissQuestionReview() },
                onSources = { page = SignalPage.Sources },
                onChooseAttachment = { page = SignalPage.History },
                onDetail = { detailId = it; station.selectRecord(it) },
                onSettings = { ui.settingsSection = "answers"; page = SignalPage.Settings },
                onNewThread = { station.newThread(); questionDraft = ""; historyQuestion = false },
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
                onSessions = { page = SignalPage.Sessions }, onPatterns = { page = SignalPage.Memory },
            )
            SignalPage.Presence -> SignalAroundPage(state, station, onSources = { page = SignalPage.Sources }, onDetail = { detailId = it; station.selectRecord(it) })
            SignalPage.Sources -> SignalSourcesPage(state, station, onSettings = { ui.settingsSection = "sources"; page = SignalPage.Settings })
            SignalPage.Settings -> SignalConfiguration(state, station, onManageWatch) { confirmation = it }
        }
        }
        if (!keyboardOpen) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, windowInsets = WindowInsets(0, 0, 0, 0)) {
            listOf(SignalPage.Today, SignalPage.Conversation, SignalPage.History).forEach { destination ->
                val roots = setOf(SignalPage.Today, SignalPage.Conversation, SignalPage.History)
                val activePage = page.takeIf { it in roots } ?: ui.stack.lastOrNull { it.page in roots }?.page ?: SignalPage.Today
                val active = destination == activePage
                NavigationBarItem(selected = active, onClick = { ui.tab(destination) },
                    icon = { Icon(when (destination) {
                        SignalPage.Today -> Icons.Outlined.RadioButtonChecked
                        SignalPage.Conversation -> Icons.Outlined.ChatBubbleOutline
                        else -> Icons.Outlined.History
                    }, contentDescription = null) }, label = { Text(destination.title) }, alwaysShowLabel = true)
            }
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SignalConversation(
    state: SignalState,
    station: SignalStation,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    searchHistory: Boolean,
    onHistoryChange: (Boolean) -> Unit,
    onDetail: (String) -> Unit,
    onSettings: () -> Unit,
    onSources: () -> Unit,
    onChooseAttachment: () -> Unit,
    onNewThread: () -> Unit,
) {
    var saveTitle by signalUiState<String?>("ask.saveTitle") { null }
    var saveAsNew by signalUiState("ask.saveAsNew") { false }
    var panel by signalUiState("ask.panel") { "" }
    var newReply by signalUiState("ask.newReply") { false }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(draft) { station.suggestMemory(draft) }
    LaunchedEffect(state.threadId) { station.loadConversation() }
    val records = state.records.filter { it.threadId == state.threadId && it.provider != "local" }.sortedBy { it.createdAt }
    val scroll = signalListState("conversation.${state.threadId}")
    LaunchedEffect(records.lastOrNull()?.id, records.lastOrNull()?.state, records.lastOrNull()?.answer) {
        if (records.isNotEmpty() && records.last().state == "ready") {
            if (!scroll.canScrollForward) scroll.scrollToItem(records.size)
            else newReply = true
        }
    }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val ready = state.settings.provider in state.configuredProviders && state.settings.model.isNotBlank()
    saveTitle?.let { title -> AlertDialog(onDismissRequest = { saveTitle = null }, title = { Text("Save question") },
        text = { OutlinedTextField(title, { saveTitle = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { station.saveQuestion(title, draft, searchHistory, saveAsNew); saveTitle = null }, enabled = title.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { saveTitle = null }) { Text("Cancel") } }) }
    val review = state.questionReview
    if (review != null || panel.isNotBlank()) {
        BackHandler { if (review != null) station.dismissQuestionReview() else panel = "" }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TextButton(onClick = { if (review != null) station.dismissQuestionReview() else panel = "" }) { Text("Back to question") } }
            if (review != null) {
                item {
                    SignalHeading("Review before sending")
                    Text("${providerLabel(state.settings.provider)} · ${state.settings.model}")
                    Text("${signalCount(review.captureCount, "capture")} · ${signalCount(review.observationCount, "reading")} · ${signalCount(review.priorTurnCount, "earlier turn")}")
                    Text("${signalCount(review.memoryCount, "memory")} · ${review.bytes} message bytes")
                    if (review.omittedObservations > 0) Text("${review.omittedObservations} readings omitted to fit. Originals remain in History.")
                    if (review.omittedRecords > 0) Text("${review.omittedRecords} records omitted by the size limit.")
                    var exact by remember(review) { mutableStateOf(false) }
                    TextButton(onClick = { exact = !exact }) { Text(if (exact) "Hide exact message text" else "Show exact message text") }
                    if (exact) SelectionContainer { Column { review.messages.forEach { (role, text) -> Text("$role\n$text") } } }
                    if (review.recordCount == 0 && review.captureCount == 0 && review.observationCount == 0 && review.memoryCount == 0 && review.priorTurnCount == 0) {
                        Text("No saved readings are included. You can send an ordinary question, or add evidence first.")
                        TextButton(onClick = { station.dismissQuestionReview(); panel = "context" }) { Text("Add evidence") }
                    }
                    Text("Only Send contacts your provider. It may use credits.")
                    if (!ready) OutlinedButton(onClick = onSettings) { Text("Set up answers") }
                    Button(onClick = station::sendReviewedQuestion, enabled = !state.busy && ready, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text("Send question") }
                }
            } else if (panel == "context") {
                item {
                    SignalHeading("Question evidence")
                    state.evidenceSelection?.let { selection ->
                        Text("${selection.matchedCount} selected history results. Current source eligibility and message limits still apply.")
                        TextButton(onClick = station::clearEvidenceSelection) { Text("Remove history selection") }
                    }
                    Text(if (state.attachedRecords.isEmpty()) "No capture attached." else "${state.attachedRecords.size} observations attached.")
                    TextButton(onClick = onChooseAttachment) { Text("Attach from History") }
                }
                items(state.attachedRecords, key = { "attachment:${it.id}" }) { attachment ->
                    Text(signalDateTime(attachment.createdAt)); Text(signalObservationCoverage(attachment))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onDetail(attachment.id) }) { Text("Inspect") }
                        TextButton(onClick = { station.removeAttachment(attachment.id) }, enabled = !state.busy) { Text("Remove observation") }
                    }
                }
                item {
                    SignalSuggestedMemoryContext(state, station, onDetail)
                    if (state.evidenceSelection == null) SignalToggle("Ask about saved history", searchHistory, !state.busy, "Review matching saved records before sending.", onHistoryChange)
                    SignalSourcePreview(state.sources, state.settings.enabled, "Sources for a new capture")
                    OutlinedButton(onClick = station::survey, enabled = !state.busy && state.settings.enabled.isNotEmpty()) { Text("Capture and prepare question") }
                    TextButton(onClick = onSources) { Text("Choose sources") }
                }
            } else if (panel == "saved") {
                item { SignalHeading("Saved questions"); if (state.savedQuestions.isEmpty()) Text("Save a question from the composer to use it again.") }
                items(state.savedQuestions, key = { it.id }) { recipe ->
                    Text(recipe.title, style = MaterialTheme.typography.titleMedium)
                    Text(recipe.question)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { station.openSavedQuestion(recipe.id); panel = "" }, enabled = !state.busy) { Text("Open question") }
                        TextButton(onClick = { station.deleteSavedQuestion(recipe.id) }, enabled = !state.busy) { Text("Delete ${recipe.title}") }
                    }
                }
            } else if (panel == "voice") item {
                SignalHeading("Voice")
                SignalWakeControls(station, state) { voice -> onDraft(if (draft.isBlank()) voice else "$draft\n\n$voice"); panel = "" }
                OutlinedButton(onClick = station::recordOnWatch, enabled = !state.busy && state.watches.any { it.id == state.settings.watchId && it.connected }) { Text("Dictate on watch") }
                if (state.watches.none { it.id == state.settings.watchId && it.connected }) Text("Choose a watch in Settings to use watch dictation.")
            } else item {
                SignalHeading("Question options")
                TextButton(onClick = { saveAsNew = false; saveTitle = state.savedQuestionDraft?.title ?: draft.take(60) }, enabled = draft.isNotBlank() && !state.busy) { Text(if (state.savedQuestionDraft == null) "Save question" else "Save changes") }
                if (state.savedQuestionDraft != null) {
                    TextButton(onClick = { saveAsNew = true; saveTitle = draft.take(60) }, enabled = draft.isNotBlank() && !state.busy) { Text("Save as new") }
                    TextButton(onClick = station::dismissSavedQuestion, enabled = !state.busy) { Text("Use current context / remove saved attachments") }
                }
                TextButton(onClick = { panel = "saved" }) { Text("Saved questions") }
                TextButton(onClick = { panel = "voice" }) { Text("Voice · Go go gadget & watch") }
                TextButton(onClick = { onNewThread(); panel = "" }, enabled = !state.busy) { Text("New conversation") }
                TextButton(onClick = onSettings) { Text("Answer provider") }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SignalHeading("Ask about your observations")
                if (ready) Text("${providerLabel(state.settings.provider)} · ${state.settings.model}", style = MaterialTheme.typography.bodySmall)
                else { Text("Add your provider key and model when you want a reply. Your question stays here."); OutlinedButton(onClick = onSettings) { Text("Set up answers") } }
                TextButton(onClick = { panel = "saved" }) { Text("Saved questions") }
                if (records.isEmpty() && !keyboardOpen) {
                    if (state.attachedRecords.isNotEmpty() || state.evidenceSelection != null) {
                        Text("Your selected evidence is attached. Choose a starting question or write your own.", style = MaterialTheme.typography.bodySmall)
                        if (draft.isBlank()) {
                            TextButton(onClick = { onDraft("Summarize the attached observations. State their age and distinguish measurements from estimates.") }, enabled = !state.busy) { Text("Summarize these readings") }
                            TextButton(onClick = { onDraft("Which readings are missing, old or uncertain in the attached observations? Explain what can and cannot be concluded.") }, enabled = !state.busy) { Text("Explain missing readings") }
                        }
                    } else {
                        Text("Ask an ordinary question, or add readings about your surroundings.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { panel = "context" }) { Text("Add readings") }
                    }
                }
            }
            items(records, key = { it.id }) { record ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("You · ${signalDateTime(record.createdAt)}", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(record.question, style = MaterialTheme.typography.titleMedium) }
                    Text("${providerLabel(record.provider)} · ${record.state.replace('_', ' ')}", style = MaterialTheme.typography.labelMedium)
                    SignalResponse(record.answer, record.summary.ifBlank { if (record.state == "working") "Waiting for reply…" else record.state })
                    if (record.state in setOf("error", "interrupted", "cancelled")) {
                        Text("Your question is saved. Review it before sending again.")
                        TextButton(onClick = { onDraft(record.question) }, enabled = !state.busy) { Text("Edit question") }
                        TextButton(onClick = onSettings) { Text("Check provider") }
                    }
                    TextButton(onClick = { onDetail(record.id) }) { Text("Readings and details") }
                    HorizontalDivider()
                }
            }
        }
        if (newReply) TextButton(onClick = { scope.launch { scroll.scrollToItem(records.size); newReply = false } }) { Text("New reply") }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!keyboardOpen) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { panel = "context" }) { Text(state.evidenceSelection?.let { "Evidence · ${it.matchedCount} results" } ?: "Evidence · ${state.attachedRecords.size} attached") }
                TextButton(onClick = { panel = "options" }) { Text("Options") }
            }
            OutlinedTextField(draft, onDraft, Modifier.fillMaxWidth(), label = { Text("Your question") }, minLines = 1, maxLines = 3, enabled = !state.busy)
            Button(onClick = onSend, enabled = !state.busy && !state.historyLoading && draft.isNotBlank(), modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text("Review question") }
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
    onSessions: () -> Unit,
    onPatterns: () -> Unit,
) {
    var query by signalUiState("history.text") { "" }
    var from by signalUiState("history.from") { "" }
    var to by signalUiState("history.to") { "" }
    var source by signalUiState("history.source") { "" }
    var kind by signalUiState("history.kind") { "all" }
    var selected by signalUiState("history.selected") { emptySet<String>() }
    var toolsOpen by signalUiState("history.tools") { false }
    var moreActions by signalUiState("history.moreActions") { false }
    val requested = SignalHistoryQuery(text = query, kind = kind, fromDate = from, throughDate = to, sourceKeys = source.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet())
    val results = state.scopedHistory
    LaunchedEffect(Unit) { if (results.updatedAt == 0L && !results.loading) station.queryHistory(requested) }
    val fromDate = from.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val toDate = to.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val dateError = (from.isNotBlank() && fromDate == null) || (to.isNotBlank() && toDate == null) || (fromDate != null && toDate != null && fromDate > toDate)
    val pending = requested != results.query
    val ids = selected.takeIf { it.isNotEmpty() }
    val actionCount = ids?.size ?: results.matchingCount
    val usable = !state.busy && !results.loading && !pending && results.error.isBlank() && actionCount > 0
    LaunchedEffect(results.recordIds) { selected = selected.intersect(results.recordIds) }
    LazyColumn(Modifier.fillMaxSize(), state = signalListState("history"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SignalHeading("History")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSessions) { Text("Recordings") }
                OutlinedButton(onClick = onPatterns) { Text("Patterns") }
            }
            Text("Find saved readings and answers.")
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search questions, answers, and readings") })
            SignalChoice("Record type", kind, listOf("all" to "All records", "captures" to "Captures", "conversations" to "Conversations", "reports" to "Reports and imports")) { kind = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "Today", 7 to "Last 7 days", 30 to "Last 30 days").forEach { (days, label) ->
                    TextButton(onClick = {
                        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                        from = today.minus(days - 1, DateTimeUnit.DAY).toString(); to = today.toString()
                        selected = emptySet()
                        station.queryHistory(requested.copy(fromDate = from, throughDate = to))
                    }, enabled = !results.loading && !state.busy) { Text(label) }
                }
            }
            if (from.isNotBlank() || to.isNotBlank()) Text("Saved ${from.ifBlank { "any time" }} through ${to.ifBlank { "any time" }}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { toolsOpen = !toolsOpen }) { Text(if (toolsOpen) "Hide filters" else "Date and source filters") }
            if (toolsOpen) {
                OutlinedTextField(from, { from = it }, Modifier.fillMaxWidth(), label = { Text("From date · YYYY-MM-DD") }, singleLine = true, isError = from.isNotBlank() && fromDate == null)
                OutlinedTextField(to, { to = it }, Modifier.fillMaxWidth(), label = { Text("Through date · YYYY-MM-DD") }, singleLine = true, isError = to.isNotBlank() && toDate == null)
                SignalChoice("Source", source, listOf("" to "All sources") + state.sources.map { it.key to it.name }) { source = it }
                Text("Dates use capture or save time in ${requested.zoneId}. Measurement periods remain in each record.", style = MaterialTheme.typography.bodySmall)
            }
            if (dateError) Text("Use valid dates with the start on or before the end.", color = MaterialTheme.colorScheme.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { selected = emptySet(); station.queryHistory(requested) }, enabled = !results.loading && !dateError) { Text(if (pending) "Apply filters" else "Refresh results") }
                TextButton(onClick = { query = ""; from = ""; to = ""; source = ""; kind = "all"; selected = emptySet(); station.queryHistory(SignalHistoryQuery()) }, enabled = !results.loading) { Text("All history") }
            }
            if (results.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (results.error.isNotBlank()) Text(results.error, color = MaterialTheme.colorScheme.error)
            Text("${results.matchingCount} results · ${selected.size} selected", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (pending) Text("Apply changed filters before using these results.")
            else if (results.updatedAt > 0) Text("Updated ${signalDateTime(results.updatedAt)} · refresh for new records.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { station.openHistoryQuestion(ids) }, enabled = usable) { Text(if (ids == null) "Ask about these results" else "Ask about selected") }
                if (selected.isNotEmpty()) OutlinedButton(onClick = { station.openHistoryQuestion(selected, compare = true) }, enabled = usable && selected.size == 2 && SignalHistoryScope.comparable(results.records.filter { it.id in selected })) { Text("Compare selected (${selected.size}/2)") }
            }
            Text("Uses ${actionCount} ${if (ids == null) "matching" else "selected"} records.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { moreActions = !moreActions }) { Text(if (moreActions) "Hide more actions" else "More actions") }
            if (moreActions) {
            Text("${signalStorageLabel(state.storageBytes)} stored. Disabled-source evidence stays inspectable; sharing includes eligible sources only.", style = MaterialTheme.typography.bodySmall)
            SignalChoice("Export these results", "", listOf("" to "Choose format", "json" to "JSON", "markdown" to "Markdown", "csv" to "CSV"), usable) { format ->
                if (format.isNotBlank()) onConfirm(SignalConfirmation("Export these results?", "Share eligible evidence from $actionCount records using the applied filters. Other app data is excluded.") { station.shareHistorySelection(format, ids) })
            }
            TextButton(onClick = { station.previewDeleteHistorySelection(ids) }, enabled = usable) { Text("Review deletion of these results") }
            }
            if (selected.isNotEmpty()) TextButton(onClick = { selected = emptySet() }) { Text("Clear selection") }
        }
        if (results.records.isEmpty() && !results.loading) item { Text("No records match these filters.") }
        items(results.records, key = { it.id }) { record ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = record.id in selected, onCheckedChange = { checked -> selected = if (checked) selected + record.id else selected - record.id }, enabled = !results.loading && !pending,
                    modifier = Modifier.semantics { contentDescription = "Select record from ${signalDateTime(record.createdAt)}" })
                Column(Modifier.weight(1f)) {
                    Text(signalDateTime(record.createdAt), style = MaterialTheme.typography.labelMedium)
                    Text(record.question, style = MaterialTheme.typography.titleMedium)
                    Text("${record.kind.replace('_', ' ')} · ${record.state}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onDetail(record.id) }) { Text("Open record") }
                }
            }
        }
        if (results.hasMore) item { OutlinedButton(onClick = station::loadMoreScopedHistory, enabled = !results.loading) { Text("Show more results") } }
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
        Modifier.fillMaxSize(), state = signalListState("detail.${record.id}"), contentPadding = PaddingValues(16.dp),
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
        if (signalSupportsLocalChanges(record)) item { SignalTrendPanel(record, state, onReference) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAnalyze, enabled = !state.busy && record.state == "ready" && record.observations.isNotEmpty() && SignalHistory.allowed(record, state.settings.enabled)) { Text("Ask about this") }
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
    val ui = LocalSignalUiSession.current
    var section by ui::settingsSection
    val settings = state.settings
    val uriHandler = LocalUriHandler.current
    var model by signalUiState("settings.model.${settings.provider}.${settings.model}") { settings.model }
    var endpoint by signalUiState("settings.endpoint.${settings.provider}.${settings.endpoint}") { settings.endpoint }
    var key by signalUiState("settings.key.${settings.provider}") { "" }
    var recognitionKey by signalUiState("settings.recognitionKey") { "" }
    var weatherQuery by signalUiState("settings.weatherQuery") { "" }
    var expandedGroups by signalUiState("settings.sourceGroups") { emptySet<String>() }
    val pendingProfile = model != settings.model || endpoint != settings.endpoint
    if (section == "menu") {
        LazyColumn(Modifier.fillMaxSize(), state = signalListState("settings.menu"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SignalHeading("Settings") }
            items(listOf("sources" to "Sources and permissions", "answers" to "Answers", "devices" to "Connected devices", "voice" to "Voice", "learning" to "Learning and privacy", "storage" to "Storage"), key = { it.first }) { (key, title) ->
                OutlinedButton(onClick = { section = key }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text(title) }
            }
            item { Text(state.buildVersion, style = MaterialTheme.typography.bodySmall); TextButton(onClick = { uriHandler.openUri("https://lukesteuber.com/downloads/apps/signal-station/") }) { Text("Download page and guide") } }
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(), state = signalListState("settings.$section"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TextButton(onClick = { section = "menu" }) { Text("Settings categories") } }
        if (section == "storage") item {
            SignalHeading("Storage")
            Text("${state.historyCount} records · ${signalStorageLabel(state.storageBytes)} on this phone")
            Text("Export all data includes eligible saved app data. Use History filters to export a specific selection.")
            listOf("json" to "JSON", "markdown" to "Markdown", "csv" to "CSV readings").forEach { (format, label) ->
                OutlinedButton(onClick = { onConfirm(SignalConfirmation("Export all data?", "Share all currently eligible data using the existing full export. History filters do not apply.") { station.shareHistory(format) }) }, enabled = !state.busy && state.historyCount > 0) { Text("Export all data · $label") }
            }
            TextButton(onClick = { station.previewDeleteRecords(null) }, enabled = !state.busy && state.historyReady && state.historyCount > 0) { Text("Review deletion of all history") }
        }
        if (section == "voice") item { SignalWakeControls(station, state) { voice -> ui.questionDraft = if (ui.questionDraft.isBlank()) voice else "${ui.questionDraft}\n\n$voice"; ui.navigate(SignalPage.Conversation) } }

        if (section == "answers") {
        item {
            SignalHeading("Settings")
            Text("Capture works without a provider key. Add a key when you want model analysis.")
            SignalHeading("Answers")
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
                OutlinedButton(onClick = station::checkAnswerSetup, enabled = !state.busy && !pendingProfile && key.isBlank()) { Text("Check local setup") }
                OutlinedButton(onClick = station::testProvider, enabled = !state.busy && !pendingProfile && key.isBlank() && settings.model.isNotBlank() && settings.provider in state.configuredProviders) { Text("Test with provider") }
            }
            if (pendingProfile || key.isNotBlank()) Text("Save these changes before testing or asking.", style = MaterialTheme.typography.bodySmall)
            state.diagnostics?.let { report ->
                Text("${report.stage.replace('_', ' ')}: ${report.result.replace('_', ' ')} · ${report.elapsedMs} ms · ${report.payloadBytes} message bytes")
                TextButton(onClick = station::shareDiagnostics, enabled = !state.busy) { Text("Share diagnostic report") }
                Text("The report contains build, timing, size, source outcomes and scheduler state. Your question, keys and readings are excluded.", style = MaterialTheme.typography.bodySmall)
            }
            Text("The test sends a short question using the saved model and key. Provider charges may apply.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                onConfirm(SignalConfirmation("Remove answer key?", "You can add another key later.") { station.saveKey(settings.provider, "") })
            }, enabled = !state.busy && settings.provider in state.configuredProviders) { Text("Remove saved key") }
        }

        }
        if (section == "learning") {
        item {
            HorizontalDivider()
            SignalLearningControls(state, station)
        }

        }
        if (section == "sources") {
        item {
            HorizontalDivider()
            SignalHealthControls(state, station)
        }

        }
        if (section == "voice") {
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

        }
        if (section == "devices") {
        item {
            HorizontalDivider()
            SignalHeading("About and watch connection")
            Text(state.buildVersion, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uriHandler.openUri("https://dr.eamer.dev/downloads/apps/signal-station/") }) { Text("Download page and guide") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { ui.values.keys.filter { it.startsWith("onboarding.") }.toList().forEach(ui.values::remove); station.updateSettings(settings.copy(onboardingComplete = false)) }, enabled = !state.busy) { Text("Revisit setup guide") }
                onManageWatch?.let { TextButton(onClick = it) { Text("Watch connection") } }
                OutlinedButton(onClick = station::installWatchApp, enabled = !state.busy && state.watchCapabilities.install && state.watches.any { it.id == settings.watchId && it.connected }) { Text("Install watch app") }
            }
            Text(state.watchCapabilities.description)
            Text("Watch buttons: Up captures readings · Select asks · Down opens history. Your Pebble app keeps ownership of pairing and watch settings.")
            if (state.installStatus.isNotBlank()) Text(state.installStatus)
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

        }
        if (section == "sources") {
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
