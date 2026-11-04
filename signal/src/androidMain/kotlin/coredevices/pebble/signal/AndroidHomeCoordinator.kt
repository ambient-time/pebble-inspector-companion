package coredevices.pebble.signal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID
import java.io.File
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider

/** The phone is the single permission and execution owner for grid, chat and watch. */
internal class AndroidHomeCoordinator(
    private val store: SignalStore,
    private val scope: CoroutineScope,
    private val state: () -> SignalState,
    private val update: ((SignalState) -> SignalState) -> Unit,
    private val foreground: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    providedClient: io.ktor.client.HttpClient? = null,
    private val context: Context? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = providedClient ?: createSignalHomeHttpClient()
    private val factory = SignalHomeConnectorFactory(http, store, clock)
    private val homeStore = SignalHomeStore(store, clock) { saved -> update { it.copy(home = saved) } }
    private val engine = SignalHomeEngine(object : HomePersistence {
        override suspend fun load(): HomeState = withContext(Dispatchers.IO) { homeStore.load() }
        override suspend fun save(state: HomeState) = withContext(Dispatchers.IO) { homeStore.save(state) }
        override suspend fun archived(actionId: String) = withContext(Dispatchers.IO) { homeStore.archived(actionId) }
        override fun pin(actionId: String) = homeStore.pin(actionId)
        override fun unpin(actionId: String) = homeStore.unpin(actionId)
    }, factory::create, clock, ::id)
    private var visibleJob: Job? = null
    private var refreshJob: Job? = null
    private var previewJob: Job? = null
    private var previewToken = ""
    private var previewGeneration = 0L
    private var visible = false
    private val watchMutex = Mutex()
    private val replay = SignalHomeReplay(
        get = { key -> withContext(Dispatchers.IO) { store.documentLarge(key) } },
        put = { key, value -> withContext(Dispatchers.IO) { store.document(key, "home_replay", value) } },
    )
    private val watchRequests = SignalHomeRequestCache(clock)
    private val initialized = CompletableDeferred<Boolean>()
    private var ready = false
    private val turnIntents = mutableMapOf<String, MutableSet<String>>()
    private val turnPermissions = mutableMapOf<String, () -> Boolean>()
    private val watchPermissions = mutableMapOf<String, suspend () -> Boolean>()

    suspend fun initialize() {
        try {
            engine.recoverInterrupted()
            replay.retireLegacyJournal()
            val current = engine.currentState()
            ready = true
            update { it.copy(home = current, homeReady = true) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            update { it.copy(homeReady = false, homeBusy = false, homeStatus = "Home safety records could not be opened. Controls are disabled; stored records have not been erased.") }
        } finally { initialized.complete(ready) }
    }
    fun close() { ready = false; visibleJob?.cancel(); refreshJob?.cancel(); previewJob?.cancel(); http.close() }
    private suspend fun requireReady() { check(initialized.await() && ready) { "Home safety records are unavailable." } }
    private fun id() = UUID.randomUUID().toString()
    private fun launch(block: suspend () -> Unit) = scope.launch {
        try { requireReady(); block() } catch (_: TimeoutCancellationException) { update { it.copy(homeBusy = false, homeStatus = "Home request timed out. Check current state before requesting an action again.") } }
        catch (e: CancellationException) { throw e }
        catch (e: HomeException) { update { it.copy(homeBusy = false, homeStatus = e.message.orEmpty()) } }
        catch (_: Exception) { update { it.copy(homeBusy = false, homeStatus = "Home request failed. Check the connection, credentials and current device state.") } }
    }
    private suspend fun mutate(block: (HomeState) -> HomeState) = engine.mutateState(block)
    private suspend fun connection(id: String): HomeConnection = engine.currentState().connections.firstOrNull { it.id == id && it.enabled } ?: throw HomeException("Connection is unavailable.")
    private suspend fun read(connectionId: String, entityId: String): HomeEntity {
        val connection = connection(connectionId)
        val connector = factory.create(connection)
        return try { withTimeout(12_000) { connector.read(entityId) } ?: throw HomeException("Entity is unavailable.") } finally { connector.close() }
    }
    private suspend fun remember(entity: HomeEntity) = mutate { current ->
        val old = current.snapshot.entities.firstOrNull { it.connectionId == entity.connectionId && it.id == entity.id }
        current.copy(snapshot = current.snapshot.copy(entities = current.snapshot.entities.filterNot { it.connectionId == entity.connectionId && it.id == entity.id } + if (old != null && old.observedAt > entity.observedAt) old else entity),
            grants = if (old != null && old.identity != entity.identity) current.grants.filterNot { it.connectionId == entity.connectionId && it.entityId == entity.id } else current.grants)
    }

    fun setVisible(value: Boolean) {
        visible = value; visibleJob?.cancel(); visibleJob = null
        if (!value) { refreshJob?.cancel(); cancelPreview(); return }
        refresh()
        visibleJob = scope.launch {
            if (!initialized.await() || !ready) return@launch
            while (isActive && visible) {
                if (!foreground()) { delay(500); continue }
                val connections = engine.currentState().connections.filter { it.enabled }
                val listeners = connections.map { connection -> launch {
                    val connector = factory.create(connection)
                    try { while (isActive && visible && foreground()) {
                        try { connector.foregroundEvents().collect { entity ->
                            if (foreground() && engine.currentState().connections.any { homeConnectionBinding(it) == homeConnectionBinding(connection) }) remember(entity)
                        } } catch (e: CancellationException) { throw e } catch (_: Exception) {
                            if (connection.kind == HomeConnectorKind.MQTT) mutate { current ->
                                if (current.connections.none { homeConnectionBinding(it) == homeConnectionBinding(connection) }) current
                                else current.copy(snapshot = current.snapshot.copy(entities = current.snapshot.entities.map { entity ->
                                    if (entity.connectionId == connection.id) entity.copy(available = false, state = "Connection offline") else entity
                                }))
                            }
                            update { it.copy(homeStatus = "Live updates disconnected. Reconnecting while Home is visible.") }
                        }
                        delay(5_000)
                    } } finally { connector.close() }
                } }
                try { while (isActive && visible && foreground()) delay(500) } finally { listeners.forEach { it.cancel() } }
            }
        }
    }
    fun refresh() { refreshJob?.cancel(); refreshJob = launch { refreshAll() } }
    fun temperatureUnit(unit: HomeTemperatureUnit) = launch { mutate { it.copy(temperatureUnit = unit) } }
    private suspend fun <T> foregroundRead(block: suspend () -> T): T = coroutineScope {
        check(foreground()) { "Open the phone to read public environmental data." }
        val request = async { block() }
        val watcher = this.launch { while (isActive) { if (!foreground()) { request.cancel(); break }; delay(100) } }
        try { request.await() }
        catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!foreground()) throw HomeException("Public data preview stopped when the phone left the foreground. Open it and try again.")
            throw e
        } finally { watcher.cancel() }
    }
    private suspend fun refreshAll() {
        update { it.copy(homeBusy = true, homeStatus = "Reading connected systems…") }
        val current = engine.currentState()
        val entities = mutableListOf<HomeEntity>(); val errors = mutableMapOf<String, String>()
        for (connection in current.connections.filter { it.enabled }) {
            currentCoroutineContext().ensureActive()
            val connector = factory.create(connection)
            try { entities += withTimeout(20_000) { if (connection.kind == HomeConnectorKind.PUBLIC_ENVIRONMENT) foregroundRead { connector.catalog() } else connector.catalog() }; if (connector.warnings.isNotEmpty()) errors[connection.id] = connector.warnings.joinToString(" ").take(400) }
            catch (_: TimeoutCancellationException) { errors[connection.id] = "Connection timed out. Saved readings may be stale."; entities += current.snapshot.entities.filter { it.connectionId == connection.id }.map { it.copy(available = false) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { errors[connection.id] = "Connection unavailable. Saved readings may be stale."; entities += current.snapshot.entities.filter { it.connectionId == connection.id }.map { it.copy(available = false) } }
            finally { connector.close() }
        }
        mutate { latest ->
            val valid = entities.filter { e -> latest.connections.any { c -> c.id == e.connectionId && current.connections.any { it == c } } }.map { e ->
                val live = latest.snapshot.entities.firstOrNull { it.connectionId == e.connectionId && it.id == e.id }
                if (live != null && live.observedAt > e.observedAt) live else e
            }
            latest.copy(snapshot = HomeSnapshot(valid, clock(), errors), grants = latest.grants.filter { grant -> valid.none { it.connectionId == grant.connectionId && it.id == grant.entityId && it.identity != grant.identity } })
        }
        update { it.copy(homeBusy = false, homeStatus = "${entities.size} devices. ${errors.size} connections need attention.") }
    }
    fun cancelPreview() {
        previewGeneration++; previewJob?.cancel(); previewJob = null; previewToken = ""
        update { it.copy(homeBusy = false, homePreview = null, homePreviewEntities = emptyList()) }
    }
    fun testConnection(draft: HomeConnection, token: String, publicDisclosureAccepted: Boolean = false) {
        previewJob?.cancel()
        val generation = ++previewGeneration
        update { it.copy(homeBusy = true, homePreview = null, homePreviewEntities = emptyList(), homeStatus = "Testing connection…") }
        previewJob = launch {
            val public = draft.kind == HomeConnectorKind.PUBLIC_ENVIRONMENT
            require(draft.name.isNotBlank() && draft.name.length <= 100)
            if (public) { require(publicDisclosureAccepted && token.isEmpty()); validateOutdoorConnection(draft) }
            else require(token.isNotBlank() && token.length <= 8192)
            val prior = engine.currentState().connections.firstOrNull { it.id == draft.id }
            require(prior != null || engine.currentState().connections.size < 8) { "At most eight connections are supported." }
            val candidate = draft.copy(id = draft.id.ifBlank { id() }, revision = (prior?.revision ?: 0) + 1).let { it.copy(credentialKey = if (public) "" else "home:${it.id}:${it.revision}") }
            val secrets = object : SignalSecrets { override suspend fun get(provider: String) = token; override suspend fun put(provider: String, key: String) = Unit }
            val connector = SignalHomeConnectorFactory(http, secrets, clock).create(candidate)
            val catalog = try { withTimeout(20_000) { if (public) foregroundRead { connector.catalog() } else connector.catalog() } } finally { connector.close() }
            if (generation != previewGeneration) return@launch
            previewToken = token
            update { it.copy(homeBusy = false, homePreview = candidate, homePreviewEntities = catalog, homeStatus = if (public) "Preview ready. Check coverage and availability before saving." else "Connection test passed. Review the catalog, then save.") }
        }
    }
    fun saveConnection() = launch {
        val candidate = state().homePreview ?: return@launch
        val token = previewToken; val entities = state().homePreviewEntities
        if (candidate.kind == HomeConnectorKind.PUBLIC_ENVIRONMENT) validateOutdoorConnection(candidate) else require(token.isNotBlank())
        previewToken = ""
        update { it.copy(homePreview = null, homePreviewEntities = emptyList()) }
        if (candidate.kind != HomeConnectorKind.PUBLIC_ENVIRONMENT) store.put(candidate.credentialKey, token)
        val old = engine.currentState().connections.firstOrNull { it.id == candidate.id }
        mutate { it.copy(connections = it.connections.filterNot { c -> c.id == candidate.id } + candidate,
            grants = it.grants.filterNot { g -> g.connectionId == candidate.id },
            captureTargets = if (old?.outdoor != candidate.outdoor) it.captureTargets.filterNot { t -> t.connectionId == candidate.id } else it.captureTargets,
            ledger = it.ledger.map { entry -> if (entry.action.connectionId == candidate.id && entry.status in pending) entry.copy(status = HomeActionStatus.CANCELLED, message = "Connection replaced; review again.") else entry },
            snapshot = it.snapshot.copy(entities = it.snapshot.entities.filterNot { e -> e.connectionId == candidate.id } + entities)) }
        old?.takeIf { it.kind != HomeConnectorKind.PUBLIC_ENVIRONMENT && it.credentialKey != candidate.credentialKey }?.let { store.put(it.credentialKey, "") }
        previewToken = ""
        update { it.copy(homePreview = null, homePreviewEntities = emptyList(), homeAccess = it.homeAccess - candidate.id, homeActionsAllowed = false, homeStatus = "Connection saved. No action permissions were granted.") }
        if (visible) setVisible(true)
    }
    fun removeConnection(id: String) = launch {
        val old = engine.currentState().connections.firstOrNull { it.id == id }
        mutate { it.copy(connections = it.connections.filterNot { c -> c.id == id }, grants = it.grants.filterNot { g -> g.connectionId == id }, tiles = it.tiles.filterNot { t -> t.connectionId == id }, captureTargets = it.captureTargets.filterNot { t -> t.connectionId == id }, snapshot = it.snapshot.copy(entities = it.snapshot.entities.filterNot { e -> e.connectionId == id }), ledger = it.ledger.map { e -> if (e.action.connectionId == id && e.status in pending) e.copy(status = HomeActionStatus.CANCELLED) else e }) }
        old?.takeIf { it.kind != HomeConnectorKind.PUBLIC_ENVIRONMENT }?.let { store.put(it.credentialKey, "") }
        update { it.copy(homeAccess = it.homeAccess - id, homeActionsAllowed = false, homeStatus = "Connection removed and grants revoked.") }
        if (visible) setVisible(true)
    }
    fun tile(value: HomeTile) = launch {
        require(value.title.isNotBlank() && value.title.length <= 100 && value.room.length <= 100)
        connection(value.connectionId)
        mutate { current -> val tile = value.copy(id = value.id.ifBlank { id() }); current.copy(tiles = current.tiles.filterNot { it.id == tile.id } + tile) }
    }
    fun removeTile(id: String) = launch { mutate { it.copy(tiles = it.tiles.filterNot { t -> t.id == id }) } }
    fun moveTile(id: String, offset: Int) = launch { mutate { state ->
        val tiles = state.tiles.sortedBy { it.position }.toMutableList(); val before = tiles.indexOfFirst { it.id == id }
        if (before >= 0) { val after = (before + offset).coerceIn(0, tiles.lastIndex); tiles.add(after, tiles.removeAt(before)) }
        state.copy(tiles = tiles.mapIndexed { i,t -> t.copy(position = i) })
    } }
    fun selectCapture(connectionId: String, entityId: String, selected: Boolean) = launch { mutate {
        val target = HomeTarget(connectionId, entityId); it.copy(captureTargets = (it.captureTargets.filterNot { t -> t == target } + if (selected) listOf(target) else emptyList()).take(200))
    } }
    suspend fun capture(): List<SignalObservation> {
        requireReady()
        val targets = engine.currentState().captureTargets
        val collected = mutableListOf<SignalObservation>()
        withTimeoutOrNull(25_000) {
            for (chunk in targets.chunked(4)) coroutineScope {
                chunk.map { target -> async {
                    try {
                        val c = connection(target.connectionId)
                        val entity = if (c.kind == HomeConnectorKind.PUBLIC_ENVIRONMENT) foregroundRead { read(target.connectionId, target.entityId) } else read(target.connectionId, target.entityId)
                        remember(entity); observations(entity)
                    }
                    catch (_: TimeoutCancellationException) { emptyList() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { emptyList() }
                } }.awaitAll().forEach { collected.addAll(it) }
            }
        }
        targets.filter { t -> collected.none { it.fields["connection_id"] == t.connectionId && it.fields["entity_id"] == t.entityId } }.forEach { target -> collected += SignalObservation("home.readings", "home:${target.connectionId}", collectedAt = clock(), status = "unavailable", identity = homeTargetKey(target.connectionId,target.entityId)) }
        return collected
    }
    private fun observations(entity: HomeEntity): List<SignalObservation> = if (entity.outdoor != null) outdoorObservations(entity, clock()) else homeReadings(entity).map { value ->
        SignalObservation("home.readings", "home:${entity.connectionId}", value = value.value, unit = value.unit.orEmpty(), collectedAt = clock(), measuredAt = value.measuredAt, status = if (homeEntityAvailable(entity, clock())) "available" else "unavailable", identity = homeTargetKey(entity.connectionId,entity.id) + ":" + value.key, number = value.value.toDoubleOrNull(), metric = value.key, fields = mapOf("connection_id" to entity.connectionId,"entity_id" to entity.id,"name" to entity.name,"source_received_at" to entity.observedAt.takeIf { it > 0 }?.toString().orEmpty(),"reported_update_at" to entity.updatedAt?.toString().orEmpty(), "observation_basis" to entity.observationBasis, "retained" to entity.retained.toString()))
    }

    private suspend fun prepare(connectionId: String, entityId: String, actionId: String, parameters: Map<String, String>): HomeLedgerEntry {
        val entity = read(connectionId, entityId); remember(entity)
        return engine.prepare(connection(connectionId), entity, actionId, parameters)
    }
    private suspend fun dispatch(entry: HomeLedgerEntry, allowed: suspend () -> Boolean = { true }): HomeLedgerEntry {
        if (entry.status != HomeActionStatus.READY) return entry
        val turn = entry.authorizationOwner.takeIf { entry.authorizationMode == HomeAuthorizationMode.MODEL_TURN }
        val sent = engine.dispatch(entry.action.id, modelTurn = turn) {
            ready && (turn == null || turnPermissions[turn]?.invoke() == true) &&
                (entry.authorizationMode != HomeAuthorizationMode.EXPLICIT_CONFIRMATION || entry.authorizationOwner.isEmpty() || watchPermissions[entry.authorizationOwner]?.invoke() == true) && allowed()
        }
        if (sent.status in setOf(HomeActionStatus.ACCEPTED, HomeActionStatus.UNKNOWN)) launch { engine.reconcile(sent.action.id) }
        return sent
    }
    fun request(connectionId: String, entityId: String, actionId: String, parameters: Map<String, String>) = launch { prepare(connectionId, entityId, actionId, parameters) }
    fun confirm(id: String, allowExact: Boolean) = launch {
        val entry = engine.currentState().ledger.firstOrNull { it.action.id == id } ?: return@launch
        require(entry.status == HomeActionStatus.AWAITING_CONFIRMATION && clock() < entry.confirmationExpiresAt)
        require(entry.authorizationMode != HomeAuthorizationMode.MODEL_TURN || turnPermissions[entry.authorizationOwner]?.invoke() == true) { "This question's action permission ended. Review a new action." }
        require(entry.authorizationMode != HomeAuthorizationMode.EXPLICIT_CONFIRMATION || entry.authorizationOwner.isEmpty() || watchPermissions[entry.authorizationOwner]?.invoke() == true) { "This watch session ended. Review a new action." }
        val confirmed = engine.confirm(id)
        require(confirmed.status == HomeActionStatus.READY)
        if (allowExact) {
            val connection = connection(entry.action.connectionId); val entity = read(connection.id, entry.action.entityId)
            require(entity.identity == entry.action.identity && homeConnectionBinding(connection) == entry.action.connectionBinding && clock() < entry.confirmationExpiresAt)
            val action = entry.action
            mutate { it.copy(grants = it.grants + HomeGrant(id(), action.connectionId, action.entityId, action.capabilityId, action.parameters, clock(), identity = action.identity, connectionBinding = action.connectionBinding, capabilityBinding = action.capabilityBinding)) }
        }
        dispatch(confirmed)
    }
    fun cancel(id: String) = launch { engine.cancel(id) }
    fun revokeGrant(id: String) = launch { mutate { it.copy(grants = it.grants.filterNot { g -> g.id == id }) } }
    fun exportActivity() = launch {
        val context = context ?: throw HomeException("Home action export is unavailable in this runtime.")
        var file: File? = null
        try {
            update { it.copy(homeBusy = true, homeStatus = "Preparing complete Home action history…") }
            val count = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "signal-export").apply { mkdirs() }
                file = File(directory, "signal-home-actions-${id()}.json")
                file!!.bufferedWriter().use { homeStore.export(it) }
            }
            val exported = checkNotNull(file)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.signal-export", exported)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export Home action history").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            update { it.copy(homeBusy = false, homeStatus = "Export prepared: $count Home actions, including archived activity. Saved connection and provider credentials are not included.") }
            scope.launch { delay(10 * 60_000L); withContext(Dispatchers.IO) { exported.delete() } }
        } catch (e: Exception) { withContext(NonCancellable + Dispatchers.IO) { file?.delete() }; throw e }
    }
    fun cancelTurn(turn: String) { turnPermissions.remove(turn); launch { turnIntents.remove(turn)?.forEach { engine.cancel(it) } } }
    fun endTurn(turn: String) {
        turnPermissions.remove(turn)
        launch {
            val owned = turnIntents.remove(turn).orEmpty()
            engine.currentState().ledger.filter { it.action.id in owned && it.status in pending }.forEach { engine.cancel(it.action.id) }
        }
    }
    fun cancelAgentTurns() { turnPermissions.clear(); launch { val intents = turnIntents.values.flatten().toSet(); turnIntents.clear(); intents.forEach { engine.cancel(it) } } }

    fun session(turn: String, selected: Set<String>, allowActions: Boolean, valid: () -> Boolean, activity: suspend (SignalHomeActivity) -> Unit): SignalToolSession {
        require(turn.isNotBlank())
        val selectedConnections = selected.toSet()
        val bindings = selectedConnections.associateWith { selectedId ->
            homeConnectionBinding(state().home.connections.firstOrNull { it.id == selectedId && it.enabled } ?: throw HomeException("Selected connection is unavailable."))
        }
        fun scopeValid() = ready && valid() && bindings.all { (id,binding) -> state().home.connections.any { it.id == id && it.enabled && homeConnectionBinding(it) == binding } }
        suspend fun readSelected(connection: HomeConnection, entityId: String): HomeEntity {
            val connector = factory.create(connection)
            return try { withTimeout(12_000) { connector.read(entityId) } ?: throw HomeException("Entity is unavailable.") } finally { connector.close() }
        }
        turnPermissions.entries.removeAll { !it.value() }
        turnPermissions[turn] = { allowActions && scopeValid() }
        val catalogs = mutableMapOf<String, List<HomeEntity>>()
        val intents = mutableMapOf<String, String>()
        return object : SignalToolSession {
            override fun checkActive() { if (!scopeValid()) throw CancellationException("Home access ended or the connection changed.") }
            override suspend fun execute(call: SignalToolCall): JsonObject {
                checkActive(); SignalToolDialogue.validateArguments(call.name, call.arguments)
                if (call.name == "home_request_action" && !allowActions) return buildJsonObject { put("error", "This question has read-only Home access. No action was prepared or sent.") }
                val cid = call.arguments["connection_id"]!!.jsonPrimitive.content
                require(cid in selectedConnections) { "Connection was not selected for this turn." }
                val c = connection(cid)
                require(homeConnectionBinding(c) == bindings[cid]) { "Connection changed. Review this question again." }
                val aid = "$turn:${call.id}"
                val request = "${c.name} · ${call.name} · ${call.arguments}"
                activity(SignalHomeActivity(aid, call.name, request, "Querying…", clock()))
                var intentId: String? = null
                val result = try {
                    when (call.name) {
                        "home_search" -> {
                            val catalog = catalogs.getOrPut(cid) { emptyList() }.ifEmpty {
                                val connector = factory.create(c)
                                try { withTimeout(20_000) { connector.catalog() }.also { catalogs[cid] = it } } finally { connector.close() }
                            }
                            checkActive()
                            val query = call.arguments["query"]!!.jsonPrimitive.content.trim()
                            val cursor = call.arguments["cursor"]!!.jsonPrimitive.content
                            val offset = if (cursor.isEmpty()) 0 else cursor.toIntOrNull() ?: throw HomeException("Invalid page cursor.")
                            val filtered = catalog.filter { query.isEmpty() || it.name.contains(query, true) || it.id.contains(query, true) || it.domain.contains(query, true) }.sortedBy { it.id }
                            require(offset in 0..filtered.size)
                            val page = filtered.drop(offset).take(20)
                            buildJsonObject { put("connection_id", cid); put("data_is_untrusted", true); put("total", filtered.size); put("next_cursor", if (offset + page.size < filtered.size) (offset + page.size).toString() else ""); put("entities", JsonArray(page.map { e -> buildJsonObject { put("id", e.id); put("name", e.name.take(200)); put("domain", e.domain); put("available", e.available); put("supported_actions", e.capabilities.size) } })) }
                        }
                        "home_state", "home_actions" -> {
                            val entity = readSelected(c, call.arguments["entity_id"]!!.jsonPrimitive.content); checkActive(); remember(entity)
                            if (call.name == "home_state") buildJsonObject { put("data_is_untrusted", true); put("readings", json.encodeToJsonElement(observations(entity))) }
                            else buildJsonObject { put("connection_id", cid); put("entity_id", entity.id); put("identity", entity.identity); put("actions", json.encodeToJsonElement(entity.capabilities)); put("unsupported", entity.capabilities.isEmpty()) }
                        }
                        else -> {
                            val entityId = call.arguments["entity_id"]!!.jsonPrimitive.content; val actionId = call.arguments["action_id"]!!.jsonPrimitive.content
                            val parameters = call.arguments["parameters"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
                            val entity = readSelected(c, entityId); checkActive(); remember(entity)
                            val cap = entity.capabilities.firstOrNull { it.id == actionId } ?: throw HomeException("Unsupported action.")
                            val normalized = normalizeHomeParameters(cap, parameters)
                            val key = homeTargetKey(cid, entityId) + "|" + actionId + "|" + json.encodeToString(normalized)
                            val prior = intents[key]
                            val entry = if (prior != null) engine.currentState().ledger.first { it.action.id == prior } else {
                                checkActive()
                                engine.prepare(c, entity, actionId, normalized, HomeAuthorizationMode.MODEL_TURN, turn).also { intents[key] = it.action.id; turnIntents.getOrPut(turn) { mutableSetOf() }.add(it.action.id) }
                            }
                            intentId = entry.action.id; checkActive()
                            // Link the durable intent into conversation history before any mutation.
                            activity(SignalHomeActivity(aid, call.name, request, homeStatusLabel(entry.status), clock(), entry.action.id))
                            val sent = dispatch(entry) { allowActions && scopeValid() }
                            buildJsonObject { put("intent_id", sent.action.id); put("status", sent.status.name.lowercase()); put("message", sent.message.ifEmpty { "Review the exact action on the phone. No action was sent." }); put("parameters", json.encodeToJsonElement(sent.action.parameters)) }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { buildJsonObject { put("error", "Home request failed or the target/action is unavailable. Use exact identifiers and declared arguments; do not guess or retry a mutation.") } }
                activity(SignalHomeActivity(aid, call.name, request, result.toString(), clock(), intentId))
                return result
            }
        }
    }

    suspend fun watch(data: JsonObject, owner: String, valid: suspend () -> Boolean): JsonObject {
        requireReady()
        require(valid())
        for ((session, permitted) in watchPermissions.toMap()) if (!permitted()) watchPermissions.remove(session)
        watchPermissions[owner] = valid
        val kind = (data["kind"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val requestId = data["request_id"]?.jsonPrimitive?.intOrNull ?: 0
        if (kind == "home-cancel") {
            // The watch may cancel while catalog lookup is pending, before it knows an intent.
            watchRequests.cancel(owner, requestId)
            val intent = (data["intent_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (intent.isNotEmpty()) {
                val favorite = (data["favorite_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val action = (data["action_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val current = engine.currentState().ledger.firstOrNull { it.action.id == intent } ?: throw HomeException("Intent unavailable.")
                require(current.authorizationMode == HomeAuthorizationMode.EXPLICIT_CONFIRMATION && current.authorizationOwner == owner && current.favoriteId == favorite && current.action.capabilityId == action)
                engine.cancel(intent)
            } else {
                // The reply can be lost after prepare has already committed. Bind
                // cancellation to that native intent even when the response cache
                // was evicted and the watch never learned its opaque identifier.
                engine.currentState().ledger.filter { it.authorizationMode == HomeAuthorizationMode.EXPLICIT_CONFIRMATION && it.authorizationOwner == owner && it.watchRequestId == requestId && it.status in pending }.forEach { engine.cancel(it.action.id) }
            }
            return buildJsonObject { put("mode", "result"); put("favorite_id", data["favorite_id"] ?: JsonPrimitive("")); put("text", "Further dispatch cancelled. An action already sent may have taken effect; check Home activity.") }
        }
        return watchRequests.run(owner, requestId, kind, data) { active -> watchRequest(data, owner) { ready && active() && valid() } }
    }
    private suspend fun watchRequest(data: JsonObject, owner: String, valid: suspend () -> Boolean): JsonObject = watchMutex.withLock {
        require(valid())
        fun text(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val kind = text("kind"); val favoriteId = text("favorite_id")
        val favorites = engine.currentState().tiles.filter { it.watchFavorite }.sortedBy { it.position }
        fun reply(mode: String, body: String = "", block: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("mode", mode); put("favorite_id", favoriteId); put("text", body); block() }
        val result = when (kind) {
            "home-list" -> {
                val pages = maxOf(1, (favorites.size + 3) / 4); val page = text("page").toIntOrNull() ?: 0; require(page in 0 until pages)
                val pageItems = favorites.drop(page * 4).take(4)
                if (pageItems.any { it.title.toByteArray().size > 100 || it.title.any { c -> c.code < 32 } }) reply("handoff", "A favorite label needs the phone. Open Home to review it.")
                else reply("list") { put("page", page); put("pages", pages); putJsonArray("items") { pageItems.forEach { t -> add(buildJsonObject { put("id", t.id); put("label", t.title) }) } } }
            }
            "home-open", "home-review" -> {
                val tile = favorites.firstOrNull { it.id == favoriteId } ?: throw HomeException("Favorite unavailable.")
                val c = connection(tile.connectionId); val entity = read(c.id, tile.entityId); require(valid()); remember(entity)
                if (kind == "home-open") {
                    val readings = homeDisplayReadings(entity, engine.currentState().temperatureUnit).joinToString("\n") { "${it.label}: ${it.value} ${it.unit}" }
                    val detail = entity.outdoor?.let { outdoorWatchDetail(it, engine.currentState().temperatureUnit, clock()) }
                        ?: "${c.name}\n${tile.title}\n${entity.id}\n${homeReadingStatus(entity, clock())}\n$readings\n${homeReadingAge(entity, clock())}"
                    reply(if (detail.toByteArray().size <= 900) "detail" else "handoff", if (detail.toByteArray().size <= 900) detail else "Open the full device details on the phone.") { tile.capabilityId?.let { put("action_id", it) } }
                } else {
                    require(text("action_id") == tile.capabilityId)
                    val entry = withContext(NonCancellable) { engine.prepare(c, entity, tile.capabilityId ?: throw HomeException("Read-only favorite."), tile.parameters, HomeAuthorizationMode.EXPLICIT_CONFIRMATION, owner, tile.id, text("request_id").toInt()) }
                    try { currentCoroutineContext().ensureActive(); require(valid()) }
                    catch (e: Exception) { withContext(NonCancellable) { engine.cancel(entry.action.id) }; throw e }
                    val detail = reviewText(entry, c.name, tile.title)
                    if (detail.toByteArray().size > 900) reply("handoff", "Review the complete action on the phone.") { put("intent_id", entry.action.id) }
                    else reply("review", detail) { put("intent_id", entry.action.id); put("action_id", entry.action.capabilityId); put("expires_at", entry.confirmationExpiresAt / 1000) }
                }
            }
            "home-confirm" -> {
                val intent = text("intent_id")
                require(valid())
                val current = engine.currentState().ledger.firstOrNull { it.action.id == intent } ?: throw HomeException("Intent unavailable.")
                require(current.authorizationMode == HomeAuthorizationMode.EXPLICIT_CONFIRMATION && current.authorizationOwner == owner && current.favoriteId == favoriteId && current.action.capabilityId == text("action_id")) { "Action belongs to another session." }
                // A duplicate confirmation may report the original outcome, but never
                // confirm READY or reconstruct a missing/expired/pruned intent.
                if (current.status != HomeActionStatus.AWAITING_CONFIRMATION) return@withLock reply("result", "${homeStatusLabel(current.status)}. ${current.message}") { put("intent_id", intent) }
                val tile = favorites.firstOrNull { it.id == favoriteId && it.connectionId == current.action.connectionId && it.entityId == current.action.entityId && it.capabilityId == current.action.capabilityId } ?: throw HomeException("Favorite changed.")
                val cap = state().home.snapshot.entities.firstOrNull { it.connectionId == tile.connectionId && it.id == tile.entityId }?.capabilities?.firstOrNull { it.id == tile.capabilityId } ?: throw HomeException("Action unavailable.")
                require(normalizeHomeParameters(cap,tile.parameters) == current.action.parameters)
                val entry = dispatch(engine.confirm(intent)) { valid() && state().home.tiles.any { it == tile && it.watchFavorite } }
                reply("result", "${homeStatusLabel(entry.status)}. ${entry.message}") { put("intent_id", intent) }
            }
            "home-phone" -> {
                require(favoriteId.isEmpty() || favorites.any { it.id == favoriteId })
                update { it.copy(homeHandoff = favoriteId) }; reply("result", "Home is ready to review on the phone.")
            }
            else -> throw HomeException("Unknown Home request.")
        }
        result
    }
    companion object {
        val pending = setOf(HomeActionStatus.AWAITING_CONFIRMATION, HomeActionStatus.READY)
        fun reviewText(entry: HomeLedgerEntry, connection: String, target: String) = "$connection\n$target\nDevice: ${entry.action.entityId}\nAction: ${entry.action.capabilityId}\nParameters: ${entry.action.parameters.ifEmpty { mapOf("none" to "") }}\nConfirmation expires in two minutes."
    }
}
