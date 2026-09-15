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
    var section by signalUiState("home.section") { "grid" }
    var query by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<HomeTile?>(null) }
    var connectionDraft by remember { mutableStateOf<HomeConnection?>(null) }
    val columns = GridCells.Adaptive((168 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp)
    LazyVerticalGrid(columns, Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Home", style = MaterialTheme.typography.headlineMedium)
            Text(state.homeStatus, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("grid" to "Controls", "catalog" to "All devices", "connections" to "Connections", "activity" to "Activity & permissions").forEach { (key,label) -> FilterChip(section == key, { section = key }, label = { Text(label) }) }
                TextButton(station::refreshHome, enabled = !state.homeBusy) { Text("Refresh") }
            }
            if (state.homeBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }
        when (section) {
            "connections" -> {
                item(span = { GridItemSpan(maxLineSpan) }) { Column {
                    Text("Connect directly to Home Assistant, openHAB or Geepers. Credentials stay encrypted on this phone. Server token permissions still apply; selections here do not scope the token on the server.")
                    Button({ connectionDraft = HomeConnection("", "", HomeConnectorKind.GEEPERS, "") }) { Text("Add connection") }
                } }
                items(state.home.connections, key = { "connection:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { c -> Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium); Text("${c.kind} · ${c.baseUrl}")
                    state.home.snapshot.errors[c.id]?.let { Text(it) }
                    FlowRow { TextButton({ connectionDraft = c }) { Text("Replace credentials or connection") }; TextButton({ station.removeHomeConnection(c.id) }) { Text("Remove ${c.name}") } }
                } } }
                state.homePreview?.let { preview -> item(span = { GridItemSpan(maxLineSpan) }) { Card { Column(Modifier.padding(16.dp)) {
                    Text("Catalog preview · ${preview.name}", style = MaterialTheme.typography.titleMedium)
                    Text("${state.homePreviewEntities.size} devices; ${state.homePreviewEntities.count { it.capabilities.isNotEmpty() }} have supported controls.")
                    state.homePreviewEntities.take(12).forEach { Text("${it.name} · ${it.id} · ${if(it.available) it.state else "unavailable"}") }
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
                        HomeReading(entity)
                        if (entity.capabilities.isEmpty()) Text("Read only · controls unsupported or not declared by this system.")
                        SignalToggle("Include ${entity.name} in captures", state.home.captureTargets.any { it.connectionId == entity.connectionId && it.entityId == entity.id }) { station.selectHomeCapture(entity.connectionId, entity.id, it) }
                        Button({ editing = HomeTile("",entity.connectionId,entity.id,entity.name.take(100),position = state.home.tiles.size) }) { Text("Add shortcut") }
                    } }
                }
            }
            "activity" -> {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("Permissions name an exact connection, device, action and parameters. Revoking a grant prevents further dispatch; it does not undo a sent action.") }
                items(state.home.grants, key = { "grant:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { g -> Card { Column(Modifier.padding(16.dp)) {
                    Text("Allowed without confirmation", style = MaterialTheme.typography.titleMedium)
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
                            if (entity != null) HomeReading(entity) else Text("Not currently available")
                            if (tile.watchFavorite) Text("Watch favorite", style = MaterialTheme.typography.labelMedium)
                            if (tile.capabilityId != null) {
                                val cap = entity?.capabilities?.firstOrNull { it.id == tile.capabilityId }
                                Button({ station.requestHomeAction(tile.connectionId,tile.entityId,tile.capabilityId,tile.parameters) }, enabled = entity?.available == true && cap != null, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics { contentDescription = "${cap?.name ?: "Unavailable control"} ${tile.title}" }) { Text(cap?.name ?: "Control unavailable") }
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
    connectionDraft?.let { c -> HomeConnectionDialog(c, state.homeBusy, { connectionDraft = null }) { value, token -> station.testHomeConnection(value,token); connectionDraft = null } }
    editing?.let { tile -> HomeTileDialog(tile, state.home.snapshot.entities.firstOrNull { it.connectionId == tile.connectionId && it.id == tile.entityId }, { editing = null }, { station.removeHomeTile(tile.id); editing = null }) { station.saveHomeTile(it); editing = null } }
}

@Composable
private fun HomeReading(entity: HomeEntity) {
    Text(if (entity.available) entity.state else "Unavailable · last reported ${entity.state}")
    entity.values.take(8).forEach { value -> Text("${value.key}: ${value.value} ${value.unit.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
    Text("Received by source ${signalDateTime(entity.observedAt)}", style = MaterialTheme.typography.bodySmall)
    val times = entity.values.mapNotNull { it.measuredAt }.distinct()
    Text(if (times.isEmpty()) "Measurement time unknown" else "Measurement times ${times.joinToString { signalDateTime(it) }}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun HomeConnectionDialog(initial: HomeConnection, busy: Boolean, dismiss: () -> Unit, test: (HomeConnection,String) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }; var url by remember { mutableStateOf(initial.baseUrl) }
    var kind by remember { mutableStateOf(initial.kind) }; var token by remember { mutableStateOf("") }; var local by remember { mutableStateOf(initial.allowPrivateHttp) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Home connection") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name,{ name = it.take(100) },label = { Text("Connection name") })
        SignalChoice("System",kind.name,HomeConnectorKind.entries.map { it.name to when(it) { HomeConnectorKind.HOME_ASSISTANT -> "Home Assistant"; HomeConnectorKind.OPENHAB -> "openHAB"; else -> "Geepers" } }) { kind = HomeConnectorKind.valueOf(it) }
        OutlinedTextField(url,{ url = it },label = { Text("Server URL") },singleLine = true)
        OutlinedTextField(token,{ token = it },label = { Text(if (kind == HomeConnectorKind.HOME_ASSISTANT) "Long-lived access token" else "API / phone token") },visualTransformation = PasswordVisualTransformation(),singleLine = true)
        SignalToggle("Trust local HTTP for this connection",local,description = "Use only a trusted private address. HTTPS and VPN connections are supported without this option.") { local = it }
    } }, confirmButton = { TextButton({ test(initial.copy(name = name.trim(),baseUrl = url.trim(),kind = kind,allowPrivateHttp = local),token.trim()) }, enabled = !busy && name.isNotBlank() && url.isNotBlank() && token.isNotBlank()) { Text("Test & preview") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
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
    SignalToggle("Home access",state.homeAccess.isNotEmpty(),supported && state.home.connections.any { it.enabled },"During this question, query the full catalog of selected systems and request supported actions. Retrieved data goes to ${providerLabel(state.settings.provider)}. Actions use your saved permissions or require confirmation.") {
        station.setHomeAccess(if (it) state.home.connections.filter { c -> c.enabled }.map { c -> c.id }.toSet() else emptySet())
    }
    if (!supported) Text("Agent controls are disabled for this model. Ordinary chat and attached Home readings still work.")
    if (state.homeAccess.isNotEmpty()) state.home.connections.filter { it.enabled }.forEach { c -> SignalToggle(c.name,c.id in state.homeAccess) { enabled -> station.setHomeAccess(if (enabled) state.homeAccess + c.id else state.homeAccess - c.id) } }
}

@Composable
internal fun SignalHomeConfirmationDialog(state: SignalState, station: SignalStation) {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(state.home.ledger) { while (true) { now = Clock.System.now().toEpochMilliseconds(); delay(500) } }
    val entry = state.home.ledger.firstOrNull { it.status == HomeActionStatus.AWAITING_CONFIRMATION && it.confirmationExpiresAt > now } ?: return
    var allow by remember(entry.action.id) { mutableStateOf(false) }
    val connection = state.home.connections.firstOrNull { it.id == entry.action.connectionId }?.name ?: entry.action.connectionId
    val target = state.home.snapshot.entities.firstOrNull { it.connectionId == entry.action.connectionId && it.id == entry.action.entityId }?.name ?: entry.action.entityId
    AlertDialog(onDismissRequest = { station.cancelHomeAction(entry.action.id) }, title = { Text("Confirm Home action") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("System: $connection\nTarget: $target\nDevice: ${entry.action.entityId}\nAction: ${entry.action.capabilityId}\nParameters: ${entry.action.parameters.ifEmpty { mapOf("none" to "") }}")
        Text("Expires in ${((entry.confirmationExpiresAt-now)/1000).coerceAtLeast(0)} seconds. Confirmation can be used once.")
        SignalToggle("Allow this exact action without future confirmation",allow,description = "Applies to this system, device, action and these parameter values, including locks and scenes if selected. You can revoke it in Home permissions.") { allow = it }
        if (entry.action.capabilityId.contains("scene") || entry.action.entityId.startsWith("scene.") || entry.action.entityId.startsWith("script.")) Text("A scene invokes the server's current definition. Detectable identity or definition changes revoke permission; hidden server edits may not be detectable.")
    } },confirmButton = { TextButton({ station.confirmHomeAction(entry.action.id,allow) },enabled = now < entry.confirmationExpiresAt) { Text(if(allow) "Allow & send" else "Send once") } },dismissButton = { TextButton({ station.cancelHomeAction(entry.action.id) }) { Text("Cancel") } })
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
