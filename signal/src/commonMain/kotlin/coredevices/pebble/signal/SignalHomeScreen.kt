package coredevices.pebble.signal

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.time.Clock

fun homeStatusLabel(status: HomeActionStatus): String = when (status) {
    HomeActionStatus.AWAITING_CONFIRMATION -> "Awaiting confirmation"
    HomeActionStatus.READY -> "Ready to send"
    HomeActionStatus.SENDING -> "Sending"
    HomeActionStatus.ACCEPTED -> "Hub accepted"
    HomeActionStatus.OBSERVED -> "Matching state observed"
    HomeActionStatus.FAILED -> "Failed"
    HomeActionStatus.UNKNOWN -> "Outcome unknown"
    HomeActionStatus.CANCELLED -> "Cancelled before dispatch"
    HomeActionStatus.EXPIRED -> "Confirmation expired"
}

@Composable
internal fun SignalHomePage(state: SignalState, station: SignalStation) {
    if (!state.homeReady) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Home", style = MaterialTheme.typography.headlineMedium)
            Text(state.homeStatus.ifBlank { "Opening Home safety records…" }, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        return
    }
    var section by signalUiState("home.section") { "grid" }
    var query by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<HomeTile?>(null) }
    var connectionDraft by remember { mutableStateOf<HomeConnection?>(null) }
    LaunchedEffect(state.homePreview) { if (state.homePreview != null) connectionDraft = null }
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { while (true) { now = Clock.System.now().toEpochMilliseconds(); delay(30_000) } }
    val columns = GridCells.Adaptive((168 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp)
    LazyVerticalGrid(columns, Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Home", style = MaterialTheme.typography.headlineMedium)
            Text(state.homeStatus, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("grid" to "Favorites", "catalog" to "All devices", "connections" to "Connections", "activity" to "Activity & permissions").forEach { (key,label) -> FilterChip(section == key, { section = key }, label = { Text(label) }) }
                TextButton(station::refreshHome, enabled = !state.homeBusy) { Text("Refresh") }
            }
            if (state.homeBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (section == "grid" || section == "catalog") SignalChoice("Temperature units", state.home.temperatureUnit.name,
                listOf(HomeTemperatureUnit.SOURCE.name to "Source units", HomeTemperatureUnit.CELSIUS.name to "°C", HomeTemperatureUnit.FAHRENHEIT.name to "°F")) { station.setHomeTemperatureUnit(HomeTemperatureUnit.valueOf(it)) }
        } }
        when (section) {
            "connections" -> {
                item(span = { GridItemSpan(maxLineSpan) }) { Column {
                    Text("Connect Home Assistant, openHAB, Geepers or an MQTT broker. Readings work on this phone without a watch. Credentials stay encrypted here; server-side permissions still apply.")
                    Button({ connectionDraft = HomeConnection("", "", HomeConnectorKind.HOME_ASSISTANT, "") }) { Text("Add connection") }
                } }
                items(state.home.connections, key = { "connection:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { c -> Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium); Text("${c.kind} · ${c.baseUrl}")
                    state.home.snapshot.errors[c.id]?.let { Text(it) }
                    FlowRow { TextButton({ connectionDraft = c }) { Text("Replace credentials or connection") }; TextButton({ station.removeHomeConnection(c.id) }) { Text("Remove ${c.name}") } }
                } } }
                state.homePreview?.let { preview -> item(span = { GridItemSpan(maxLineSpan) }) { Card { Column(Modifier.padding(16.dp)) {
                    Text("Catalog preview · ${preview.name}", style = MaterialTheme.typography.titleMedium)
                    Text("${state.homePreviewEntities.size} devices; ${state.homePreviewEntities.count { it.capabilities.isNotEmpty() }} have supported controls.")
                    state.homePreviewEntities.take(12).forEach { entity ->
                        Text(entity.name, style = MaterialTheme.typography.titleMedium)
                        HomeReading(entity, state.home.temperatureUnit, now)
                    }
                    if (state.homePreviewEntities.size > 12) Text("The complete catalog will be available in All devices after saving.")
                    Button(station::saveHomeConnection) { Text("Save connection") }
                } } } }
            }
            "catalog" -> {
                item(span = { GridItemSpan(maxLineSpan) }) { Column {
                    Text("Catalog access, capture selection and shortcuts are independent. Select readings here, then enable Home readings in capture sources.")
                    OutlinedTextField(query, { query = it }, label = { Text("Find devices") }, modifier = Modifier.fillMaxWidth())
                } }
                items(state.home.snapshot.entities.filter { query.isBlank() || it.name.contains(query,true) || it.id.contains(query,true) }, key = { "entity:${homeTargetKey(it.connectionId,it.id)}" }, span = { GridItemSpan(maxLineSpan) }) { entity ->
                    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entity.name, style = MaterialTheme.typography.titleMedium)
                        Text("${state.home.connections.firstOrNull { it.id == entity.connectionId }?.name} · ${entity.id}")
                        HomeReading(entity, state.home.temperatureUnit, now)
                        if (entity.capabilities.isEmpty()) Text("Read only · controls unsupported or not declared by this system.")
                        SignalToggle("Include ${entity.name} in captures", state.home.captureTargets.any { it.connectionId == entity.connectionId && it.entityId == entity.id }) { station.selectHomeCapture(entity.connectionId, entity.id, it) }
                        Button({ editing = HomeTile("",entity.connectionId,entity.id,entity.name.take(100),position = state.home.tiles.size) }) { Text("Add shortcut") }
                    } }
                }
            }
            "activity" -> {
                item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Permissions name an exact connection, device, action and parameters. Revoking a grant prevents further dispatch; it does not undo a sent action.")
                    Text("Up to 128 recent actions and active reviews are shown. ${state.home.archivedIntents} older actions are retained in the encrypted archive. Export includes complete action history, targets and parameters, but not saved connection or provider credentials.")
                    TextButton(station::exportHomeActivity, enabled = !state.homeBusy) { Text("Export action history") }
                } }
                items(state.home.grants, key = { "grant:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { g -> Card { Column(Modifier.padding(16.dp)) {
                    Text("Allowed in action-enabled questions", style = MaterialTheme.typography.titleMedium)
                    Text("${state.home.connections.firstOrNull { it.id == g.connectionId }?.name}\n${g.entityId}\n${g.capabilityId}\n${g.constraints}")
                    TextButton({ station.revokeHomeGrant(g.id) }) { Text("Revoke permission") }
                } } }
                items(state.home.ledger.asReversed(), key = { "intent:${it.action.id}" }, span = { GridItemSpan(maxLineSpan) }) { e -> Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(homeStatusLabel(e.status), style = MaterialTheme.typography.titleMedium)
                    Text("${e.action.entityId} · ${e.action.capabilityId}\n${e.action.parameters}"); Text(e.message)
                    if (e.status in setOf(HomeActionStatus.READY, HomeActionStatus.AWAITING_CONFIRMATION)) TextButton({ station.cancelHomeAction(e.action.id) }) { Text("Cancel action") }
                } } }
            }
            else -> {
                item(span = { GridItemSpan(maxLineSpan) }) { Column {
                    if (state.home.tiles.isEmpty()) { Text("Choose devices and scenes for this grid. Mark favorites to put them on the watch."); Button({ section = if (state.home.connections.isEmpty()) "connections" else "catalog" }) { Text(if (state.home.connections.isEmpty()) "Connect a system" else "Choose devices") } }
                    else SignalChoice("Room", room, listOf("" to "All rooms") + state.home.tiles.map { it.room }.filter { it.isNotBlank() }.distinct().sorted().map { it to it }) { room = it }
                } }
                items(state.home.tiles.sortedBy { it.position }.filter { room.isEmpty() || it.room == room }, key = { "tile:${it.id}" }) { tile ->
                    val entity = state.home.snapshot.entities.firstOrNull { it.connectionId == tile.connectionId && it.id == tile.entityId }
                    var dragged by remember(tile.id) { mutableFloatStateOf(0f) }
                    Card(Modifier.fillMaxWidth().pointerInput(tile.id) { detectDragGesturesAfterLongPress(onDragStart = { dragged = 0f }, onDrag = { change, amount -> change.consume(); dragged += amount.y + amount.x }, onDragEnd = { if (kotlin.math.abs(dragged) > 30f) station.moveHomeTile(tile.id, if (dragged > 0) 1 else -1) }) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tile.title, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(tile.room, state.home.connections.firstOrNull { it.id == tile.connectionId }?.name.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            if (entity != null) HomeReading(entity, state.home.temperatureUnit, now) else Text("Not currently available")
                            if (tile.watchFavorite) Text("Watch favorite", style = MaterialTheme.typography.labelMedium)
                            if (tile.capabilityId != null) {
                                val cap = entity?.capabilities?.firstOrNull { it.id == tile.capabilityId }
                                Button({ station.requestHomeAction(tile.connectionId,tile.entityId,tile.capabilityId,tile.parameters) }, enabled = entity?.let { homeEntityAvailable(it, now) } == true && cap != null, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics { contentDescription = "${cap?.name ?: "Unavailable control"} ${tile.title}" }) { Text(cap?.name ?: "Control unavailable") }
                                if (tile.parameters.isNotEmpty()) Text(tile.parameters.entries.joinToString { "${it.key}: ${it.value}" })
                            }
                            TextButton({ editing = tile }) { Text("Edit ${tile.title}") }
                            FlowRow {
                                TextButton({ station.moveHomeTile(tile.id,-1) }, enabled = tile.position > 0, modifier = Modifier.semantics { contentDescription = "Move ${tile.title} earlier" }) { Text("Move earlier") }
                                TextButton({ station.moveHomeTile(tile.id,1) }, enabled = tile.position < state.home.tiles.lastIndex, modifier = Modifier.semantics { contentDescription = "Move ${tile.title} later" }) { Text("Move later") }
                            }
                        }
                    }
                }
            }
        }
    }
    connectionDraft?.let { c -> HomeConnectionDialog(c, state.homeBusy, state.homeStatus, { connectionDraft = null }) { value, token -> station.testHomeConnection(value,token) } }
    editing?.let { tile -> HomeTileDialog(tile, state.home.snapshot.entities.firstOrNull { it.connectionId == tile.connectionId && it.id == tile.entityId }, { editing = null }, { station.removeHomeTile(tile.id); editing = null }) { station.saveHomeTile(it); editing = null } }
}

@Composable
private fun HomeReading(entity: HomeEntity, unit: HomeTemperatureUnit, now: Long) {
    Text(homeReadingStatus(entity, now), style = MaterialTheme.typography.labelLarge)
    homeDisplayReadings(entity, unit).take(8).forEach { value ->
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(value.label, style = MaterialTheme.typography.labelMedium)
            Text("${value.value} ${value.unit}".trim(), style = if (value.prominent) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyLarge)
        }
    }
    Text(homeReadingAge(entity, now), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun HomeConnectionDialog(initial: HomeConnection, busy: Boolean, status: String, dismiss: () -> Unit, test: (HomeConnection,String) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }; var url by remember { mutableStateOf(initial.baseUrl) }
    var kind by remember { mutableStateOf(initial.kind) }; var token by remember { mutableStateOf("") }; var local by remember { mutableStateOf(initial.allowPrivateHttp) }
    var mqtt by remember { mutableStateOf(initial.mqtt ?: HomeMqttConfig(listOf(HomeMqttTopic()))) }
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var anonymous by remember { mutableStateOf(true) }
    var attempted by remember { mutableStateOf(false) }
    val candidate = initial.copy(name = name.trim(), baseUrl = url.trim(), kind = kind, allowPrivateHttp = local, mqtt = if (kind == HomeConnectorKind.MQTT) mqtt else null)
    val mqttError = if (kind == HomeConnectorKind.MQTT) runCatching { validateMqttConnection(candidate) }.exceptionOrNull()?.message else null
    AlertDialog(onDismissRequest = dismiss, title = { Text("Home connection") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (busy) {
            Text("Testing connection and reading selected topics…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
        if (attempted) Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        OutlinedTextField(name,{ name = it.take(100) },label = { Text("Connection name") })
        SignalChoice("System",kind.name,HomeConnectorKind.entries.map { it.name to when(it) { HomeConnectorKind.HOME_ASSISTANT -> "Home Assistant"; HomeConnectorKind.OPENHAB -> "openHAB"; HomeConnectorKind.GEEPERS -> "Geepers"; HomeConnectorKind.MQTT -> "MQTT over WebSockets" } }) { kind = HomeConnectorKind.valueOf(it) }
        OutlinedTextField(url,{ url = it.take(2048) },label = { Text(if (kind == HomeConnectorKind.MQTT) "Broker URL" else "Server URL") },singleLine = true)
        if (kind == HomeConnectorKind.MQTT) {
            Text("MQTT 5 · read only. Use a wss:// WebSocket listener. Raw mqtt:// ports are unsupported. Only the topics below are subscribed; no device commands are published.")
            SignalToggle("Anonymous broker", anonymous, description = "Turn off to enter broker credentials. Use a read-only broker account limited to your selected topics.") { anonymous = it }
            if (!anonymous) {
                OutlinedTextField(username, { username = it.take(256) }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(password, { password = it.take(1024) }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            }
            SignalToggle("Trust private unencrypted connection", local, description = "Allows ws:// only on a trusted private address. WSS keeps certificate validation enabled.") { local = it }
            HomeMqttFields(mqtt) { mqtt = it }
            if (url.isNotBlank() && mqttError != null) Text(mqttError, style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedTextField(token,{ token = it.take(8192) },label = { Text(if (kind == HomeConnectorKind.HOME_ASSISTANT) "Long-lived access token" else "API / phone token") },visualTransformation = PasswordVisualTransformation(),singleLine = true)
            SignalToggle("Trust local HTTP for this connection",local,description = "Use only a trusted private address. HTTPS and VPN connections are supported without this option.") { local = it }
        }
        }
    } }, confirmButton = { TextButton({
        val credential = if (kind == HomeConnectorKind.MQTT) Json.encodeToString(if (anonymous) HomeMqttCredentials() else HomeMqttCredentials(username, password)) else token.trim()
        attempted = true
        test(candidate, credential)
    }, enabled = !busy && name.isNotBlank() && url.isNotBlank() && if (kind == HomeConnectorKind.MQTT) mqttError == null && (anonymous || username.isNotBlank()) else token.isNotBlank()) { Text("Test & preview") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@Composable
private fun HomeTileDialog(initial: HomeTile, entity: HomeEntity?, dismiss: () -> Unit, remove: () -> Unit, save: (HomeTile) -> Unit) {
    var title by remember { mutableStateOf(initial.title) }; var room by remember { mutableStateOf(initial.room) }; var favorite by remember { mutableStateOf(initial.watchFavorite) }
    var action by remember { mutableStateOf(initial.capabilityId.orEmpty()) }; var params by remember { mutableStateOf(initial.parameters) }
    val cap = entity?.capabilities?.firstOrNull { it.id == action }
    val valid = title.isNotBlank() && (action.isEmpty() || cap != null && runCatching { normalizeHomeParameters(cap,params) }.isSuccess)
    AlertDialog(onDismissRequest = dismiss, title = { Text("Configure shortcut") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(entity?.id ?: initial.entityId)
        OutlinedTextField(title,{ title = it.take(100) },label = { Text("Label") })
        OutlinedTextField(room,{ room = it.take(100) },label = { Text("Room group") })
        SignalToggle("Show on watch",favorite) { favorite = it }
        SignalChoice("Shortcut action", action, listOf("" to "Reading only") + entity?.capabilities.orEmpty().map { it.id to it.name }) { action = it; params = emptyMap() }
        cap?.parameters?.forEach { p ->
            val value = params[p.name].orEmpty()
            val change: (String) -> Unit = { params = if (it.isEmpty() && !p.required) params - p.name else params + (p.name to it) }
            when {
                p.options.isNotEmpty() -> SignalChoice(p.name,value,listOf("" to "Choose a value") + p.options.map { it to it },onChange = change)
                p.type == "boolean" -> SignalChoice(p.name,value,listOf("" to "Choose a value", "true" to "True", "false" to "False"),onChange = change)
                else -> {
                    OutlinedTextField(value,change,label = { Text(p.name + if (p.required) "" else " (optional)") })
                    if (p.minimum != null || p.maximum != null) Text("Range: ${p.minimum ?: "unbounded"} to ${p.maximum ?: "unbounded"}")
                    if (p.minimum != null && p.maximum != null && p.minimum < p.maximum) Slider(value.toFloatOrNull()?.coerceIn(p.minimum.toFloat(),p.maximum.toFloat()) ?: p.minimum.toFloat(), { change(if (p.type == "integer") it.toInt().toString() else it.toString()) }, valueRange = p.minimum.toFloat()..p.maximum.toFloat(), modifier = Modifier.semantics { contentDescription = p.name })
                }
            }
        }
        Text("Saving a shortcut does not grant execution permission.")
        if (initial.id.isNotEmpty()) TextButton(remove) { Text("Remove shortcut") }
    } },confirmButton = { TextButton({ save(initial.copy(title = title.trim(),room = room.trim(),watchFavorite = favorite,capabilityId = action.ifEmpty { null },parameters = if(cap == null) emptyMap() else normalizeHomeParameters(cap,params))) },enabled = valid) { Text("Save shortcut") } },dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@Composable
internal fun SignalHomeAccessSelector(state: SignalState, station: SignalStation) {
    val supported = signalSupportsHomeTools(state.settings)
    SignalToggle("Read Home for this question",state.homeAccess.isNotEmpty(),supported && state.homeReady && state.home.connections.any { it.enabled },"Read the full catalog of selected systems for this question only. Retrieved data goes to ${providerLabel(state.settings.provider)}. Reading does not allow actions.") {
        station.setHomeAccess(if (it) state.home.connections.filter { c -> c.enabled }.map { c -> c.id }.toSet() else emptySet())
    }
    if (!supported) Text("Agent controls are disabled for this model. Ordinary chat and attached Home readings still work.")
    if (state.homeAccess.isNotEmpty()) state.home.connections.filter { it.enabled }.forEach { c -> SignalToggle(c.name,c.id in state.homeAccess) { enabled -> station.setHomeAccess(if (enabled) state.homeAccess + c.id else state.homeAccess - c.id) } }
    if (state.homeAccess.isNotEmpty()) SignalToggle("Allow actions for this question", state.homeActionsAllowed, supported && state.homeReady, "Exact saved permissions may execute immediately, including locks and scenes. Other actions require review. This permission ends with this question; follow-ups start with Home off.", station::setHomeActionsAllowed)
}

@Composable
internal fun SignalHomeConfirmationDialog(state: SignalState, station: SignalStation) {
    if (!state.homeReady) return
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(state.home.ledger) { while (true) { now = Clock.System.now().toEpochMilliseconds(); delay(500) } }
    val entry = state.home.ledger.firstOrNull { it.status == HomeActionStatus.AWAITING_CONFIRMATION } ?: return
    var allow by remember(entry.action.id) { mutableStateOf(false) }
    val reviewScroll = key(entry.action.id) { rememberScrollState() }
    val connection = state.home.connections.firstOrNull { it.id == entry.action.connectionId }?.name ?: entry.action.connectionId
    val target = state.home.snapshot.entities.firstOrNull { it.connectionId == entry.action.connectionId && it.id == entry.action.entityId }?.name ?: entry.action.entityId
    AlertDialog(onDismissRequest = { station.cancelHomeAction(entry.action.id) }, title = { Text("Confirm Home action") }, text = { Column(Modifier.verticalScroll(reviewScroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("System: $connection\nTarget: $target\nDevice: ${entry.action.entityId}\nAction: ${entry.action.capabilityId}")
        Text("Parameters", style = MaterialTheme.typography.titleSmall)
        if (entry.action.parameters.isEmpty()) Text("None")
        else entry.action.parameters.entries.sortedBy { it.key }.forEach { (name,value) -> Text("$name: $value") }
        Text("Expires in ${((entry.confirmationExpiresAt-now)/1000).coerceAtLeast(0)} seconds. Confirmation can be used once.")
        SignalToggle("Save permission for this exact action",allow,description = "Allows this system, device, action and these parameter values during future questions where you explicitly allow actions. Direct phone and watch controls still require confirmation. You can revoke it in Home permissions.") { allow = it }
        if (entry.action.capabilityId.contains("scene") || entry.action.entityId.startsWith("scene.") || entry.action.entityId.startsWith("script.")) Text("A scene invokes the server's current definition. Detectable identity or definition changes revoke permission; hidden server edits may not be detectable.")
        // Confirmation is in the reading/scroll order after every parameter, not
        // in an always-visible footer or gated on visual scrolling (TalkBack).
        if (now < entry.confirmationExpiresAt) Button({ station.confirmHomeAction(entry.action.id,allow) }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text(if(allow) "Save permission & send once" else "Send once") }
        else {
            Text("This review expired. Nothing was sent. Review again to read current action details; this does not send the action.")
            TextButton({
                station.cancelHomeAction(entry.action.id)
                station.requestHomeAction(entry.action.connectionId, entry.action.entityId, entry.action.capabilityId, entry.action.parameters)
            }) { Text("Review again") }
        }
    } },confirmButton = {},dismissButton = { TextButton({ station.cancelHomeAction(entry.action.id) }) { Text("Cancel") } })
}

@Composable
internal fun SignalHomeConversationActivity(record: SignalRecord, state: SignalState) {
    record.homeActivity.forEach { activity ->
        val receipt = state.home.ledger.firstOrNull { it.action.id == activity.intentId }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Home · ${activity.kind.removePrefix("home_").replace('_',' ')}",style = MaterialTheme.typography.labelLarge)
            Text(activity.request,style = MaterialTheme.typography.bodySmall)
            Text(if (receipt == null) activity.result else "${homeStatusLabel(receipt.status)}. ${receipt.message}",style = MaterialTheme.typography.bodySmall)
        }
    }
}
