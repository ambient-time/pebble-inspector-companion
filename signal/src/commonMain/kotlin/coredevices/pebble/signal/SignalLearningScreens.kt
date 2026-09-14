package coredevices.pebble.signal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType

internal enum class SignalStartDestination { Capture, Observe, Ask, Answers, Devices }

@Composable
private fun LearningHeading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
internal fun SignalLearningSetup(state: SignalState, station: SignalStation, onComplete: (SignalStartDestination) -> Unit) {
    var destination by signalUiState<SignalStartDestination?>("onboarding.destination") { null }
    var selected by signalUiState("onboarding.sources") { state.settings.enabled }
    var sourcesSaved by signalUiState("onboarding.saved") { false }
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
            Text("Chat on your phone or Pebble, collect readings, and ask about what changed.", style = MaterialTheme.typography.titleMedium)
        }
        if (destination == null) {
            item {
                Text("Start with something real", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                Text("Save a moment, observe changes over time, or ask a question. Your phone is enough; a watch is optional.")
            }
            item {
                Button(onClick = { destination = SignalStartDestination.Capture }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Capture now") }
                Text("Choose readings to save on this phone. No provider key is needed.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { destination = SignalStartDestination.Observe }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Around me") }
                Text("View temporary Wi-Fi and Bluetooth readings. Save a snapshot when useful.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { complete(if (state.settings.provider in state.configuredProviders && state.settings.model.isNotBlank()) SignalStartDestination.Ask else SignalStartDestination.Answers) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (state.settings.provider in state.configuredProviders && state.settings.model.isNotBlank()) "Ask a question" else "Set up answers")
                }
                Text("Choose a provider and model, then save your provider key. Chat works without selecting any sensor readings. Saving your key does not send a question.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LearningHeading("Add a Pebble when ready")
                    Text("This Android app keeps your full replies and handles requests to your chosen model. The optional watch app lets you ask from your wrist and read a compact reply.")
                    Text("1. Pair your watch in its usual Pebble app.\n2. Install the Signal Station watch app from the download guide.\n3. Open Connected devices here, choose your Pebble app and watch, then tap Check connection.")
                    OutlinedButton(onClick = { complete(SignalStartDestination.Devices) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Connect a Pebble") }
                    Text("Your existing pairing stays in the Pebble app. Phone features work without a watch.", style = MaterialTheme.typography.bodySmall)
                } }
            }
            item { TextButton(onClick = { complete(SignalStartDestination.Capture) }) { Text("Skip to app") }; Text("History stays here until you delete it. Learning and background observation are separate choices. A model receives your question and selected context only when you send a request.", style = MaterialTheme.typography.bodySmall) }
        } else if (!sourcesSaved) {
            item {
                LearningHeading("Choose your sources")
                SignalChoice("Start with", "", listOf("" to "Individual choices") + SignalContextPresets.all.map { it.id to it.name }) { id -> selected = SignalContextPresets.all.firstOrNull { it.id == id }?.keys ?: selected }
                SignalSourcePreview(state.sources, selected, "Proposed sources")
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
                    Text(if (destination == SignalStartDestination.Observe) "Open Around me" else "Open Now")
                }
                TextButton(onClick = { sourcesSaved = false }) { Text("Change sources") }
            }
        }
    }
}

@Composable
internal fun SignalTodayPage(state: SignalState, station: SignalStation, onAsk: () -> Unit, onCapture: () -> Unit,
    onCaptureTools: () -> Unit, onAnalyze: (String) -> Unit,
    onObserve: () -> Unit, onMemory: () -> Unit, onDetail: (String) -> Unit, onSources: () -> Unit, onNearby: () -> Unit, onLive: () -> Unit) {
    val latest = state.records.filter { signalSupportsLocalChanges(it) && it.observations.isNotEmpty() && it.state == "ready" }.maxByOrNull { it.createdAt }
    val sourceIssues = state.sourceStatus.filter { it.key in state.settings.enabled && signalSourceNeedsAttention(it) }
    val proposals = state.memories.filter { it.state == "proposed" || it.needsReview }
    var sourceIssuesOpen by signalUiState("today.issues") { false }
    var trendsOpen by signalUiState("today.trends") { false }
    LazyColumn(Modifier.fillMaxSize(), state = signalListState("today"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        state.observationSession?.takeIf { it.state in setOf("running", "paused") }?.let { session -> item {
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LearningHeading("Recording in progress")
                Text("${signalCount(session.captures, "capture")} saved · ${signalObservationEnd(session)}")
                Text(session.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = station::stopObservation) { Text("Stop recording") }
                    TextButton(onClick = onObserve) { Text("Session details") }
                }
            } }
        } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Right now", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Text("Save an observation. Ask about what changed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCapture, enabled = !state.busy && state.settings.enabled.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Capture now") }
                OutlinedButton(onClick = onLive, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Around me") }
                Text("${signalCount(state.settings.enabled.size, "source")} selected · saved on this phone", style = MaterialTheme.typography.bodySmall)
                if (state.settings.enabled.isEmpty()) Text("Choose at least one source to begin.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onObserve) { Text("Record over time") }
                    TextButton(onClick = onSources) { Text("Choose sources") }
                }
            }
        }
        if (sourceIssues.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { sourceIssuesOpen = !sourceIssuesOpen }, modifier = Modifier.semantics { stateDescription = if (sourceIssuesOpen) "Expanded" else "Collapsed" }) {
                Text("${signalCount(sourceIssues.size, "source")} ${if (sourceIssues.size == 1) "needs" else "need"} review")
            }
            if (sourceIssuesOpen) {
                Text("Latest reported outcomes; a new capture may differ. Other sources can still be captured.", style = MaterialTheme.typography.bodySmall)
                sourceIssues.forEach { SignalSourceStatusRow(it, state, station, onSources) }
            }
            }
        }
        if (latest != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LearningHeading("Latest observation")
                    Text("Saved ${signalDateTime(latest.createdAt)}", style = MaterialTheme.typography.labelLarge)
                    Text(signalObservationCoverage(latest))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAnalyze(latest.id) }, enabled = !state.busy) { Text("Ask about this") }
                        TextButton(onClick = { onDetail(latest.id) }) { Text("Inspect readings") }
                        TextButton(onClick = { station.summarizeChanges(latest.id) }, enabled = !state.busy && SignalChanges.baseline(latest, state.records, state.settings.enabled) != null) { Text("Compare with previous") }
                    }
                }
            }
            TextButton(onClick = { trendsOpen = !trendsOpen }, modifier = Modifier.semantics { stateDescription = if (trendsOpen) "Expanded" else "Collapsed" }) {
                Text(if (trendsOpen) "Hide changes and numeric history" else "Changes and numeric history")
            }
            }
        } else item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your first observation will appear here. Capturing works without a provider key.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAsk) { Text("Ask without a capture") }
            }
        }
        if (latest != null && trendsOpen) {
            item { SignalLocalChangesAction(latest, state) { station.summarizeChanges(latest.id) } }
            item { SignalTrendPanel(latest, state, onDetail) }
        }

        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                OutlinedButton(onClick = onCaptureTools) { Text("Collection presets") }
                TextButton(onClick = onMemory) { Text(if (proposals.isEmpty()) "Patterns" else "Patterns · ${proposals.size} to review") }
            }
            Text("Presets change selected sources only. Collection and sending are separate actions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

    }
}

@Composable
private fun SignalSessionSummary(session: SignalObservationSession, state: SignalState, station: SignalStation, onSources: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LearningHeading("Observation session")
        Text(session.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text("${session.state.replace('_', ' ')} · ${signalObservationModeName(session.mode, session.intervalMinutes)} · ${session.captures} saved captures · ${signalObservationEnd(session)}", style = MaterialTheme.typography.bodySmall)
        Text(if (session.localAnalysis) "Local change analysis after each capture with a previous sample." else "Automatic local analysis is off.", style = MaterialTheme.typography.bodySmall)
        if (session.modelAnalysis) Text("Model analysis every ${session.analysisEveryCaptures} saved captures · ${session.analysisProvider} · ${session.analysisModel}. Each request includes the latest two captures only.", style = MaterialTheme.typography.bodySmall)
        Text(if (session.attempts == 0 && session.captures > 0) "Attempt count was not recorded for this session." else "${session.attempts} collection attempts",
            style = MaterialTheme.typography.bodySmall)
        if (session.sourceKeys.any { it.startsWith("healthconnect.") }) Text("Health imports are recorded separately below; new reads require background health access.", style = MaterialTheme.typography.bodySmall)
        session.lastSuccessAt?.let { Text("Last successful reading ${signalDateTime(it)}", style = MaterialTheme.typography.bodySmall) }
        if (session.sourceKeys.any { it.startsWith("healthconnect.") } && session.state in setOf("running", "paused")) Text(state.healthStatus, style = MaterialTheme.typography.bodySmall)
        if (session.state in setOf("running", "paused")) OutlinedButton(onClick = station::stopObservation) { Text("Stop recording") }
        val outcomes = if (session.state in setOf("running", "paused")) state.sourceStatus.filter { it.key in session.sourceKeys } else emptyList()
        if (outcomes.isNotEmpty()) {
            var expanded by signalUiState("record.outcomes.${session.id}") { false }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }) { Text(if (expanded) "Hide source outcomes" else "Source outcomes · ${outcomes.size}") }
            if (expanded) {
                Text("Latest source outcomes · these are not session totals", style = MaterialTheme.typography.labelLarge)
                outcomes.forEach { source -> SignalSourceStatusRow(source, state, station, onSources) }
            }
        }
    }
}

@Composable
internal fun SignalSessionsPage(state: SignalState, station: SignalStation, onSources: () -> Unit, onDetail: (String) -> Unit = {}, onFieldTest: () -> Unit = {}) {
    var minutes by signalUiState("record.minutes") { "60" }
    var mode by signalUiState("record.mode") { state.settings.observationMode }
    var interval by signalUiState("record.interval") { state.settings.observationIntervalMinutes.toString() }
    var localAnalysis by signalUiState("record.localAnalysis") { state.settings.observationLocalAnalysis }
    var modelAnalysis by signalUiState("record.modelAnalysis") { state.settings.observationModelAnalysis }
    var analysisCadence by signalUiState("record.analysisCadence") { state.settings.observationAnalysisEveryCaptures.toString() }
    var sourcesExpanded by signalUiState("record.sourcesExpanded") { false }
    val sessionKeys = state.settings.enabled - setOf("places.nearby", "location.radio")
    var selected by signalUiState("record.sources") { sessionKeys }
    LaunchedEffect(sessionKeys) { selected = selected.intersect(sessionKeys) }
    val activeSession = state.observationSession?.takeIf { it.state in setOf("running", "paused") }
    val active = activeSession != null
    val intervalValue = interval.toIntOrNull()?.takeIf { it in 1..1440 }
    val analysisCadenceValue = analysisCadence.toIntOrNull()?.takeIf { it in 1..60 }
    val providerReady = state.settings.provider in state.configuredProviders && state.settings.model.isNotBlank()
    val pendingSchedule = mode != state.settings.observationMode ||
        (mode == "fixed" && intervalValue != state.settings.observationIntervalMinutes) || localAnalysis != state.settings.observationLocalAnalysis ||
        modelAnalysis != state.settings.observationModelAnalysis || (modelAnalysis && analysisCadenceValue != state.settings.observationAnalysisEveryCaptures)
    val pastSessions = (listOfNotNull(state.observationSession) + state.sessions).distinctBy { it.id }
        .filter { it.id != activeSession?.id }.sortedByDescending { it.startedAt }
    LazyColumn(Modifier.fillMaxSize(), state = signalListState("recordings"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            LearningHeading("Record over time")
            Text("Collect selected readings on a schedule, for a set time or until you stop. The notification always includes Stop. Android can delay or interrupt individual readings.")
            Text("Learning remains a separate choice. Model requests happen only if you enable scheduled model analysis below.", style = MaterialTheme.typography.bodySmall)
        }
        activeSession?.let { session -> item { SignalSessionSummary(session, state, station, onSources) } }
        if (!active) {
            item {
                SignalChoice("Collection schedule", mode, listOf("fixed" to "Fixed interval", "standard" to "Adaptive", "battery_saver" to "Adaptive battery saver"), !state.busy) { mode = it }
                when (mode) {
                    "fixed" -> {
                        SignalChoice("Quick interval", interval, listOf("1" to "1 minute", "5" to "5 minutes", "15" to "15 minutes", "30" to "30 minutes", "60" to "1 hour"), !state.busy) { interval = it }
                        OutlinedTextField(value = interval, onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) interval = it },
                            label = { Text("Minutes between scans") }, singleLine = true, enabled = !state.busy,
                            isError = intervalValue == null, supportingText = { Text("1–1440 minutes. The interval starts after each scan and any scheduled analysis finish.") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        Text("Motion does not trigger extra scans. Wi-Fi scans are requested at this interval; Android may throttle them. Weather refreshes at most every 30 minutes.", style = MaterialTheme.typography.bodySmall)
                    }
                    "battery_saver" -> Text("Usually 15 minutes between scans; recent motion or network changes can shorten this to 5 minutes.", style = MaterialTheme.typography.bodySmall)
                    else -> Text("Usually 5 minutes between scans; recent motion or network changes can shorten this to 1 minute.", style = MaterialTheme.typography.bodySmall)
                }
                SignalChoice("Session length", minutes, listOf("0" to "Ongoing · until stopped", "15" to "15 minutes", "60" to "1 hour", "240" to "4 hours"), !state.busy) { minutes = it }
            }
            item {
                SignalToggle("Local change analysis", localAnalysis, !state.busy,
                    "After each capture, compare with the previous sample in this session. Saved reports cite both captures. No model request.") { localAnalysis = it }
                SignalToggle("Scheduled model analysis", modelAnalysis, !state.busy,
                    "Automatically send the latest two session captures to your selected provider. This may incur provider charges.") { modelAnalysis = it }
                if (modelAnalysis) {
                    Text("Send to ${state.settings.provider} · ${state.settings.model}", style = MaterialTheme.typography.labelLarge)
                    if (state.settings.endpoint.isNotBlank()) Text(state.settings.endpoint, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = analysisCadence, onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) analysisCadence = it },
                        label = { Text("Analyze every N saved captures") }, singleLine = true, enabled = !state.busy,
                        isError = analysisCadenceValue == null, supportingText = { Text("1–60 captures; always waits for at least two. Failed requests wait another full cadence before retrying.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Text("Starting authorizes repeated requests using only the sources selected for this session. No other history or memories are sent. Requests include dated, size-limited evidence and cite both captures.", style = MaterialTheme.typography.bodySmall)
                    if (!providerReady) Text("Add a key and choose a model in Settings, or turn off scheduled model analysis.", color = MaterialTheme.colorScheme.error)
                } else Text("For a one-time model analysis, review selected captures in Ask before sending.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                if (pendingSchedule) {
                    OutlinedButton(onClick = { station.updateSettings(state.settings.copy(observationMode = mode,
                        observationIntervalMinutes = intervalValue ?: state.settings.observationIntervalMinutes, observationLocalAnalysis = localAnalysis,
                        observationModelAnalysis = modelAnalysis, observationAnalysisEveryCaptures = analysisCadenceValue ?: state.settings.observationAnalysisEveryCaptures)) },
                        enabled = !state.busy && (mode != "fixed" || intervalValue != null) && (!modelAnalysis || analysisCadenceValue != null)) { Text("Save schedule and analysis") }
                    Text("Save these options before starting. Your source choices stay the same.", style = MaterialTheme.typography.bodySmall)
                }
                Text("${selected.intersect(sessionKeys).size} selected sources", style = MaterialTheme.typography.labelLarge)
                if (sessionKeys.isEmpty()) Text("Enable at least one collection source to begin.")
                Button(onClick = { station.startObservation(minutes.toInt(), selected.intersect(sessionKeys)) }, enabled = !state.busy && !pendingSchedule && selected.intersect(sessionKeys).isNotEmpty() && (!modelAnalysis || providerReady),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Start recording") }
                Text("Pauses for low battery, low storage or a hot phone. No automatic restart after reboot or force-stop. Watch readings require its existing Signal Station connection to stay open.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                TextButton(onClick = { sourcesExpanded = !sourcesExpanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    stateDescription = if (sourcesExpanded) "Expanded" else "Collapsed"
                }) { Text(if (sourcesExpanded) "Hide session sources" else "Choose session sources · ${selected.intersect(sessionKeys).size} selected") }
                if (sourcesExpanded) {
                    Text("Select from your enabled sources. Sources you leave off remain available for manual capture.", style = MaterialTheme.typography.bodySmall)
                    if (sessionKeys != state.settings.enabled) Text("External lookups are reviewed separately in Around me; sessions do not send them.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onSources) { Text("Change enabled sources") }
            }
            if (sourcesExpanded) items(state.sources.filter { it.key in sessionKeys }, key = { it.key }) { source ->
                SignalToggle(source.name, source.key in selected, !state.busy, if (source.available) null else "Access or a connected device may be needed") {
                    selected = if (it) selected + source.key else selected - source.key
                }
            }
        }
        item {
            var advanced by signalUiState("record.advanced") { false }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced collection" else "Advanced collection") }
            if (advanced) {
                SignalContextControls(state, station)
                OutlinedButton(onClick = onFieldTest) { Text("20-minute field trial") }
            }
        }
        item { HorizontalDivider(); LearningHeading("Past sessions") }
        if (pastSessions.isEmpty()) item { Text("Completed and interrupted sessions will appear here.") }
        items(pastSessions, key = { it.id }) { session ->
            var expanded by remember(session.id) { mutableStateOf(false) }
            var visibleRecords by remember(session.id) { mutableStateOf(10) }
            Text(signalDateTime(session.startedAt), style = MaterialTheme.typography.labelLarge)
            Text("${session.state.replace('_', ' ')} · ${signalObservationModeName(session.mode, session.intervalMinutes)} · ${session.captures} saved captures")
            Text(if (session.attempts == 0 && session.captures > 0) "Attempt count was not recorded for this session." else "${session.attempts} collection attempts",
                style = MaterialTheme.typography.bodySmall)
            Text(session.status, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }) { Text(if (expanded) "Hide session evidence" else "Inspect session evidence") }
            if (expanded) {
                val records = state.records.filter { it.sessionId == session.id }.sortedBy { it.createdAt }
                Text("${records.size} loaded records from this session. Other records may be outside this history page or deleted.", style = MaterialTheme.typography.bodySmall)
                Text("Coverage counts describe stored rows, including unavailable outcomes. They are not a count of successful measurements or complete session coverage.", style = MaterialTheme.typography.bodySmall)
                records.take(visibleRecords).forEach { record ->
                    HorizontalDivider()
                    Text("${signalDateTime(record.createdAt)} · ${record.state.replace('_', ' ')}", style = MaterialTheme.typography.labelLarge)
                    if (record.coverage.isNotEmpty()) SignalRecordCoverage(record, state.sources)
                    else Text("Detailed coverage was not saved with this record.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onDetail(record.id) }) { Text("Inspect record · ${record.id}") }
                }
                if (records.size > visibleRecords) TextButton(onClick = { visibleRecords += 10 }) {
                    Text("Show more session records · ${records.size - visibleRecords} remaining")
                }
                if (state.historyHasMore) OutlinedButton(onClick = station::loadMoreHistory, enabled = !state.busy && !state.historyLoading) {
                    Text(if (state.historyLoading) "Loading older activity…" else "Load older activity")
                }
            }
        }
    }
}

private fun signalObservationModeName(mode: String, intervalMinutes: Int): String = when (mode) {
    "fixed" -> "$intervalMinutes min between scans"
    "standard" -> "Adaptive"
    "battery_saver" -> "Adaptive battery saver"
    else -> "Unknown mode"
}

private fun signalObservationEnd(session: SignalObservationSession): String =
    session.endsAt?.let { "scheduled end ${signalDateTime(it)}" } ?: "ongoing · no scheduled end"

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
    var tab by signalUiState("patterns.tab") { "review" }
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
    LazyColumn(Modifier.fillMaxSize(), state = signalListState("patterns"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Patterns", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
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
