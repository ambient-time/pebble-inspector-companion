package coredevices.pebble.signal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class SignalStartDestination { Capture, Observe, Ask }

@Composable
private fun LearningHeading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
internal fun SignalLearningSetup(state: SignalState, station: SignalStation, onComplete: (SignalStartDestination) -> Unit) {
    var destination by remember { mutableStateOf<SignalStartDestination?>(null) }
    var selected by remember { mutableStateOf(state.settings.enabled) }
    var sourcesSaved by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    LaunchedEffect(state.settings.enabled, state.busy, waiting) {
        if (waiting && !state.busy) {
            waiting = false
            sourcesSaved = state.settings.enabled == selected
        }
    }
    fun complete(target: SignalStartDestination) {
        station.updateSettings(state.settings.copy(onboardingComplete = true))
        onComplete(target)
    }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SignalAntennaGlyph()
            Text("Signal Station", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
            Text("Understand your surroundings. Remember what matters.", style = MaterialTheme.typography.titleMedium)
        }
        if (destination == null) {
            item {
                Text("Start with something real", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                Text("Save a moment, observe changes over time, or ask a question. Your phone is enough; a watch is optional.")
            }
            item {
                Button(onClick = { destination = SignalStartDestination.Capture }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Capture once") }
                Text("Choose readings to save on this phone. No provider key is needed.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { destination = SignalStartDestination.Observe }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Observe for a while") }
                Text("Start a timed session with selected sources and a visible Stop control.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { complete(SignalStartDestination.Ask) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Ask a question") }
            }
            item { Text("History stays here until you delete it. Learning and background observation are separate choices. A model receives your question and selected context only when you send a request.", style = MaterialTheme.typography.bodySmall) }
        } else if (!sourcesSaved) {
            item {
                LearningHeading("Choose your sources")
                Text("Every source is optional. You can change these choices later in Settings. Weather and place lookups use their named data services.")
                Text("${signalCount(selected.size, "source")} selected")
                Text(state.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
            }
            state.sources.groupBy { it.group }.forEach { (group, sources) ->
                item { LearningHeading(group) }
                items(sources, key = { it.key }) { source ->
                    SignalToggle(source.name, source.key in selected, !waiting,
                        if (source.available) null else "Access or a supported device may be needed") {
                        selected = if (it) selected + source.key else selected - source.key
                    }
                }
            }
            item {
                Button(onClick = { waiting = true; station.updateSettings(state.settings.copy(enabled = selected)) }, enabled = selected.isNotEmpty() && !waiting && !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (waiting) "Saving sources…" else "Continue with these sources") }
                TextButton(onClick = { destination = null }, enabled = !waiting) { Text("Back") }
            }
        } else {
            item {
                LearningHeading("Give selected sources access")
                Text("Review only the permissions needed for your choices. You can decline and still use other available sources.")
                OutlinedButton(onClick = station::requestPermissions, enabled = !state.busy) { Text("Review collection permissions") }
                if (selected.any { it.startsWith("healthconnect.") }) {
                    Text("Health Connect access is chosen by data type. You can review it from Settings.", style = MaterialTheme.typography.bodySmall)
                }
                Text(state.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            item {
                Button(onClick = { complete(destination!!) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (destination == SignalStartDestination.Observe) "Choose session length" else "Open Capture")
                }
                TextButton(onClick = { sourcesSaved = false }) { Text("Change sources") }
            }
        }
    }
}

@Composable
internal fun SignalTodayPage(state: SignalState, station: SignalStation, onAsk: () -> Unit, onCapture: () -> Unit,
    onObserve: () -> Unit, onMemory: () -> Unit, onDetail: (String) -> Unit, onSources: () -> Unit, onNearby: () -> Unit) {
    val latest = state.records.filter { signalSupportsLocalChanges(it) && it.observations.isNotEmpty() && it.state == "ready" }.maxByOrNull { it.createdAt }
    val sourceIssues = state.sourceStatus.filter { it.key in state.settings.enabled && signalSourceNeedsAttention(it) }
    val proposals = state.memories.filter { it.state == "proposed" || it.needsReview }.take(3)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Today", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Text("Understand your surroundings. Remember what matters.")
        }
        item {
            Button(onClick = onCapture, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Capture once") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onObserve) { Text("Observe for a while") }
                TextButton(onClick = onAsk) { Text("Ask a question") }
                TextButton(onClick = onNearby) { Text("Nearby") }
            }
            Text("${signalCount(state.settings.enabled.size, "source")} enabled · captures stay on this phone", style = MaterialTheme.typography.bodySmall)
            if (state.settings.enabled.isEmpty()) TextButton(onClick = onSources) { Text("Choose sources") }
        }
        if (sourceIssues.isNotEmpty()) item {
            LearningHeading("Sources to review")
            Text("Other available sources can still be captured.", style = MaterialTheme.typography.bodySmall)
            sourceIssues.take(3).forEach { SignalSourceStatusRow(it, state, station, onSources) }
            if (sourceIssues.size > 3) TextButton(onClick = onSources) { Text("Review all ${sourceIssues.size} source issues") }
        }
        if (state.savedQuestions.isNotEmpty()) item {
            LearningHeading("Saved questions")
            Text("Open a draft, review its current context, then send when ready.")
        }
        items(state.savedQuestions, key = { "question:${it.id}" }) { recipe ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { station.openSavedQuestion(recipe.id) }, enabled = !state.busy) { Text(recipe.title) }
                TextButton(onClick = { station.deleteSavedQuestion(recipe.id) }, enabled = !state.busy) { Text("Delete ${recipe.title}") }
            }
        }
        state.observationSession?.let { session -> item { SignalSessionSummary(session, state, station, onSources) } }
        if (latest != null) item {
            HorizontalDivider()
            LearningHeading("Latest observation")
            Text(signalDateTime(latest.createdAt), style = MaterialTheme.typography.labelLarge)
            Text(latest.summary.ifBlank { "${signalCount(latest.observations.size, "reading")} saved" })
            TextButton(onClick = { onDetail(latest.id) }) { Text("Inspect readings") }
            SignalLocalChangesAction(latest, state) { station.summarizeChanges(latest.id) }
            if (state.settings.placeFences.isEmpty()) {
                Text("Give a familiar place a name to make future observations easier to understand.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onNearby) { Text("Name a familiar place") }
            }
        } else item { Text("Your first capture will appear here. Choose its sources, save it, then inspect the evidence.") }
        item {
            HorizontalDivider()
            LearningHeading(if (state.settings.learningEnabled) "Learning from your observations" else "Memory, when you are ready")
            Text(state.learningStatus)
            if (!state.settings.learningEnabled) Text("Learning looks for patterns in eligible saved and future observations on this phone. Suggestions become remembered knowledge only after your review.")
            TextButton(onClick = onMemory) { Text(if (proposals.isEmpty()) "Open Memory" else "Review ${signalCount(proposals.size, "suggestion")}") }
        }
        items(proposals, key = { it.id }) { memory ->
            Text(memory.proposedText.takeIf { memory.needsReview && it.isNotBlank() } ?: memory.text)
            Text(memory.coverage, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SignalSessionSummary(session: SignalObservationSession, state: SignalState, station: SignalStation, onSources: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LearningHeading("Observation session")
        Text(session.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text("${session.state.replace('_', ' ')} · ${signalCount(session.captures, "capture")} · ends ${signalDateTime(session.endsAt)}", style = MaterialTheme.typography.bodySmall)
        session.lastSuccessAt?.let { Text("Last saved ${signalDateTime(it)}", style = MaterialTheme.typography.bodySmall) }
        if (session.state in setOf("running", "paused")) OutlinedButton(onClick = station::stopObservation) { Text("Stop observing") }
        state.sourceStatus.filter { it.key in session.sourceKeys }.forEach { source ->
            SignalSourceStatusRow(source, state, station, onSources)
        }
    }
}

@Composable
internal fun SignalSessionsPage(state: SignalState, station: SignalStation, onSources: () -> Unit) {
    var minutes by remember { mutableStateOf("60") }
    var selected by remember { mutableStateOf(state.settings.enabled) }
    LaunchedEffect(state.settings.enabled) { selected = selected.intersect(state.settings.enabled) }
    val active = state.observationSession?.state in setOf("running", "paused")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            LearningHeading("Observe for a while")
            Text("Collect selected readings during a timed session. A notification shows that observation is active and lets you stop it. Android can delay or interrupt individual readings.")
            Text("Starting a session does not enable learning or send observations to a model.", style = MaterialTheme.typography.bodySmall)
        }
        state.observationSession?.let { session -> item { SignalSessionSummary(session, state, station, onSources) } }
        if (!active) {
            item {
                SignalChoice("Session length", minutes, listOf("15" to "15 minutes", "60" to "1 hour", "240" to "4 hours"), !state.busy) { minutes = it }
                LearningHeading("Sources for this session")
                Text("Select from your enabled sources. Sources you leave off remain available for manual capture.", style = MaterialTheme.typography.bodySmall)
            }
            items(state.sources.filter { it.key in state.settings.enabled }, key = { it.key }) { source ->
                SignalToggle(source.name, source.key in selected, !state.busy, if (source.available) null else "Access or a connected device may be needed") {
                    selected = if (it) selected + source.key else selected - source.key
                }
            }
            item {
                if (state.settings.enabled.isEmpty()) Text("Enable at least one source to begin.")
                TextButton(onClick = onSources) { Text("Change enabled sources") }
                Button(onClick = { station.startObservation(minutes.toInt(), selected.intersect(state.settings.enabled)) }, enabled = !state.busy && selected.intersect(state.settings.enabled).isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Start observation session") }
                Text("No automatic restart after reboot or force-stop. Watch readings are available only while its existing Signal Station connection is open.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { HorizontalDivider(); LearningHeading("Past sessions") }
        if (state.sessions.isEmpty()) item { Text("Completed and interrupted sessions will appear here.") }
        items(state.sessions.filter { it.id != state.observationSession?.id }, key = { it.id }) { session ->
            Text(signalDateTime(session.startedAt), style = MaterialTheme.typography.labelLarge)
            Text("${session.state.replace('_', ' ')} · ${signalCount(session.captures, "capture")}")
            Text(session.status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SignalLearningControls(state: SignalState, station: SignalStation) {
    LearningHeading("Learning and memory")
    if (state.settings.learningEnabled) {
        SignalToggle("Learn from my observations", true, !state.busy,
            "Process eligible saved and future observations locally. Background collection remains a separate choice.", station::enableLearning)
    } else {
        Text("Find patterns in eligible saved and future observations on this phone. Review a suggestion before it becomes remembered knowledge. You can inspect the evidence, correct it, or forget it.")
        Button(onClick = { station.enableLearning(true) }, enabled = !state.busy) { Text("Enable local learning") }
    }
    Text(state.learningStatus, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
    Text("Disabled sources are excluded from learning and model context. Their saved history stays available until you delete it.", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun SignalHealthControls(state: SignalState, station: SignalStation) {
    var days by remember(state.settings.healthHistoryDays) { mutableStateOf(state.settings.healthHistoryDays.toString()) }
    var background by remember { mutableStateOf(false) }
    val pendingPeriod = days.toInt() != state.settings.healthHistoryDays
    LearningHeading("Health Connect")
    Text("Import only the health data types you enable in Collection sources. Imported readings retain their origin and measurement period; overlapping watch and Health Connect data stay distinct.")
    SignalChoice("Import period", days, listOf("7" to "Last 7 days", "30" to "Last 30 days", "90" to "Last 90 days"), !state.busy) { days = it }
    if (days == "90") Text("A 90-day import requests extended health history access when supported.", style = MaterialTheme.typography.bodySmall)
    SignalToggle("Include background access in this request", background, !state.busy,
        "Requests permission for selected health sources during observation sessions. This does not start a session or revoke previously granted access.") { background = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { station.requestHealthPermissions(days.toInt(), background) }, enabled = !state.busy) { Text("Review health access") }
        Button(onClick = station::importHealth, enabled = !state.busy && !pendingPeriod && state.settings.enabled.any { it.startsWith("healthconnect.") }) { Text("Import last ${state.settings.healthHistoryDays} days") }
    }
    if (pendingPeriod) Text("Review health access to save the selected import period before importing.", style = MaterialTheme.typography.bodySmall)
    Text(state.healthStatus, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun SignalMemoryPage(state: SignalState, station: SignalStation, onEvidence: (String) -> Unit, onCapture: () -> Unit, onNearby: () -> Unit, onSources: () -> Unit) {
    var tab by remember { mutableStateOf("review") }
    var detail by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<SignalMemory?>(null) }
    var noteOpen by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val visible = state.memories.filter { when (tab) {
        "review" -> it.state == "proposed" || it.needsReview
        "remembered" -> it.state == "confirmed" && !it.needsReview
        "notes" -> it.state == "note"
        else -> it.state == "rejected"
    } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Memory", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Text("What this phone remembers, with the evidence and your corrections.")
            if (!state.settings.learningEnabled) SignalLearningControls(state, station)
            else Text(state.learningStatus, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("review" to "Review", "remembered" to "Remembered", "notes" to "Notes", "rejected" to "Dismissed").forEach { (key, label) ->
                    FilterChip(selected = tab == key, onClick = { tab = key }, label = { Text(label) })
                }
            }
            OutlinedButton(onClick = { edit = null; text = ""; noteOpen = true }, enabled = !state.busy) { Text("Write a personal note") }
        }
        if (visible.isEmpty()) item {
            Text(when (tab) {
                "review" -> "No suggestions to review. Independent observations across several days are needed before a pattern is proposed."
                "remembered" -> "Confirmed patterns will appear here. Nothing is established without your review."
                "notes" -> "Save something you want to remember in your own words."
                else -> "Dismissed suggestions stay out of review until you restore them."
            })
            if (tab == "review") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCapture) { Text("Capture observations") }
                TextButton(onClick = onNearby) { Text("Name places and devices") }
            }
        }
        items(visible, key = { it.id }) { memory ->
            HorizontalDivider()
            Text(if (memory.needsReview) "New evidence to review" else memory.kind.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge)
            Text(memory.text, style = MaterialTheme.typography.titleMedium)
            if (memory.needsReview && memory.proposedText.isNotBlank()) Text("Updated observation: ${memory.proposedText}")
            if (memory.coverage.isNotBlank()) Text(memory.coverage, style = MaterialTheme.typography.bodySmall)
            if (memory.state in setOf("confirmed", "note") && !SignalLearning.eligible(memory, state.settings)) {
                Text(if (!state.settings.learningEnabled) "Learning is paused; this memory will not be sent." else "Not available for model context while its source is disabled or its evidence needs review.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onSources) { Text("Review settings") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { detail = memory.id }) { Text("Why this?") }
                if (memory.state == "proposed" || memory.needsReview) {
                    Button(onClick = { station.reviewMemory(memory.id, "confirm") }, enabled = !state.busy && state.settings.learningEnabled) { Text(if (memory.needsReview) "Use updated pattern" else "Confirm memory") }
                    if (memory.needsReview) TextButton(onClick = { station.reviewMemory(memory.id, "correct", memory.text) }, enabled = !state.busy) { Text("Keep current wording") }
                    else TextButton(onClick = { station.reviewMemory(memory.id, "reject") }, enabled = !state.busy) { Text("Dismiss suggestion") }
                }
                if (memory.state == "rejected") TextButton(onClick = { station.reviewMemory(memory.id, "restore") }, enabled = !state.busy) { Text("Restore to review") }
                else TextButton(onClick = { edit = memory; text = memory.text; noteOpen = true }, enabled = !state.busy) { Text("Edit wording") }
                TextButton(onClick = { station.previewForgetMemory(memory.id) }, enabled = !state.busy && state.historyReady) { Text("Forget…") }
            }
        }
    }
    state.memories.firstOrNull { it.id == detail }?.let { memory ->
        SignalMemoryEvidence(memory, SignalLearning.eligible(memory, state.settings), onDismiss = { detail = null }, onEvidence = { detail = null; onEvidence(it) })
    }
    if (noteOpen) AlertDialog(onDismissRequest = { noteOpen = false }, title = { Text(if (edit == null) "Personal note" else "Correct the wording") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (edit == null) "Write what you want to remember. This will be identified as your note, without an inferred observation behind it." else "Your correction will be preserved with this memory and its evidence.")
            OutlinedTextField(text, { text = it.take(2000) }, label = { Text("Your words") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = {
        val memory = edit
        if (memory == null) station.saveMemoryNote(text.trim()) else station.reviewMemory(memory.id, "correct", text.trim())
        noteOpen = false
    }, enabled = text.isNotBlank() && !state.busy) { Text("Save") } }, dismissButton = { TextButton(onClick = { noteOpen = false }) { Text("Cancel") } })
}

@Composable
private fun SignalMemoryEvidence(memory: SignalMemory, eligible: Boolean, onDismiss: () -> Unit, onEvidence: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Why this?") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(memory.text)
            Text(if (eligible) "Available when selected as question context." else "Not currently available for model context.", style = MaterialTheme.typography.bodySmall)
            Text(if (memory.state == "note") "Personal note · written or explicitly retained by you" else "${memory.state.replace('_', ' ')} · revision ${memory.revision}", style = MaterialTheme.typography.labelLarge)
            if (memory.coverage.isNotBlank()) Text(memory.coverage)
            Text("Last evaluated ${signalDateTime(memory.evaluatedAt)}", style = MaterialTheme.typography.bodySmall)
            memory.confirmedAt?.let { Text("Confirmed ${signalDateTime(it)}", style = MaterialTheme.typography.bodySmall) }
            if (memory.needsReview) {
                LearningHeading("Proposed update")
                Text(memory.proposedText.ifBlank { "New evidence is ready for review." })
                if (memory.proposedEvidence.isEmpty()) Text("No new supporting readings are attached.")
                memory.proposedEvidence.forEach { evidence ->
                    HorizontalDivider()
                    Text(evidence.description)
                    TextButton(onClick = { onEvidence(evidence.recordId) }) { Text("Inspect proposed evidence · ${signalDateTime(evidence.collectedAt)}") }
                }
                LearningHeading("Previously confirmed evidence")
            }
            if (memory.evidence.isEmpty()) Text("No observation evidence is attached.")
            memory.evidence.forEach { evidence ->
                HorizontalDivider()
                Text(evidence.description)
                TextButton(onClick = { onEvidence(evidence.recordId) }) { Text("Inspect ${signalDateTime(evidence.collectedAt)}") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
internal fun SignalSuggestedMemoryContext(state: SignalState, station: SignalStation, onEvidence: (String) -> Unit) {
    var preview by remember { mutableStateOf(false) }
    val suggestions = state.memorySuggestions.filter { SignalLearning.eligible(it, state.settings) }
    if (!state.settings.learningEnabled && state.memories.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SignalToggle("Use memory", state.useMemory, !state.busy,
            if (state.useMemory) "${suggestions.size} relevant ${if (suggestions.size == 1) "memory" else "memories"} selected for this question." else "No memories will be included in this question.", station::setUseMemory)
        if (suggestions.isNotEmpty()) {
            suggestions.forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { preview = true }) { Text("Review suggested context") }
        } else Text("Suggestions are found on this phone. No provider request is made until you send.", style = MaterialTheme.typography.bodySmall)
    }
    if (preview) AlertDialog(onDismissRequest = { preview = false }, title = { Text("Suggested context") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (state.useMemory) "These memories will accompany your question when you tap Send." else "Use memory is off. These suggestions will not be sent.")
            suggestions.forEach { memory ->
                HorizontalDivider()
                Text(memory.text)
                if (memory.coverage.isNotBlank()) Text(memory.coverage, style = MaterialTheme.typography.bodySmall)
                Text("${memory.state} · evaluated ${signalDateTime(memory.evaluatedAt)}", style = MaterialTheme.typography.bodySmall)
                memory.evidence.forEach { evidence ->
                    TextButton(onClick = { preview = false; onEvidence(evidence.recordId) }) { Text("Evidence · ${signalDateTime(evidence.collectedAt)}") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { preview = false }) { Text("Close") } })
}

@Composable
internal fun SignalDeletionDialog(state: SignalState, station: SignalStation) {
    val preview = state.deletionPreview ?: return
    var retained by remember(preview) { mutableStateOf(emptySet<String>()) }
    AlertDialog(onDismissRequest = station::dismissDeletion, title = { Text(if (preview.memoryId == null) "Review deletion" else "Forget this memory?") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${signalCount(preview.recordCount, "record")} and ${preview.memories.size} dependent ${if (preview.memories.size == 1) "memory" else "memories"} will be removed. This includes dependent analysis and cached context. This cannot be undone.")
            if (preview.memories.isNotEmpty()) {
                if (preview.memories.any { it.state in setOf("confirmed", "note") }) Text("You can explicitly keep confirmed wording as a personal note. A retained note will no longer claim support from deleted evidence.")
                preview.memories.forEach { memory ->
                    if (memory.state in setOf("confirmed", "note")) SignalToggle(memory.text, memory.id in retained, !state.busy, "Keep wording as a personal note") {
                        retained = if (it) retained + memory.id else retained - memory.id
                    } else Text(memory.text)
                }
            }
            Text("Other personal notes, provider keys and settings remain.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { station.applyDeletion(retained) }, enabled = !state.busy) { Text("Delete selected data") } }, dismissButton = { TextButton(onClick = station::dismissDeletion) { Text("Cancel") } })
}

internal fun signalStorageLabel(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes bytes"
}
