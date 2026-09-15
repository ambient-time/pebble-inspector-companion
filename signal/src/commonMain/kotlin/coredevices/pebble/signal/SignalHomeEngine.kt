package coredevices.pebble.signal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Single owner per persistence store. No offline queue: restart never dispatches outstanding work. */
class SignalHomeEngine(private val persistence: HomePersistence, private val connector: (HomeConnection) -> HomeConnector, private val clock: () -> Long, private val id: () -> String) {
    private val mutex = Mutex()
    suspend fun currentState(): HomeState = mutex.withLock { persistence.load() }
    /** Route every UI/settings/catalog write through this method to prevent lost updates. */
    suspend fun mutateState(transform: (HomeState) -> HomeState): HomeState = mutex.withLock {
        val next = transform(persistence.load())
        require(next.connections.size <= 20 && next.tiles.size <= 200 && next.grants.size <= 500 && next.captureTargets.size <= 500)
        require(next.connections.map { it.id }.distinct().size == next.connections.size)
        persistence.save(next); next
    }
    suspend fun prepare(connection: HomeConnection, entity: HomeEntity, capabilityId: String, parameters: Map<String, String> = emptyMap()): HomeLedgerEntry = mutex.withLock {
        val state = persistence.load()
        val current = state.connections.firstOrNull { it.id == connection.id } ?: throw HomeException("Connection was removed.")
        require(current == connection && current.enabled && entity.connectionId == current.id)
        val capability = entity.capabilities.firstOrNull { it.id == capabilityId } ?: throw HomeException("Action is not supported.")
        val normalized = normalizeHomeParameters(capability, parameters)
        val now = clock()
        val action = HomeAction(id(), current.id, entity.id, capabilityId, normalized, now, entity.identity, homeConnectionBinding(current), capability.expectedValues.mapValues { (_, value) -> if (value.startsWith("$")) normalized[value.drop(1)].orEmpty() else value }, homeCapabilityBinding(capability))
        require(state.ledger.none { it.action.id == action.id }) { "Duplicate action identifier." }
        val grant = state.grants.firstOrNull { matches(it, action, now) }
        val entry = HomeLedgerEntry(action, if (grant == null) HomeActionStatus.AWAITING_CONFIRMATION else HomeActionStatus.READY, now, now + 120_000, grantId = grant?.id)
        persistence.save(state.copy(ledger = state.ledger + entry)); entry
    }
    suspend fun confirm(actionId: String): HomeLedgerEntry = change(actionId) { entry ->
        require(entry.status == HomeActionStatus.AWAITING_CONFIRMATION && entry.confirmedAt == null) { "Confirmation already consumed." }
        if (clock() >= entry.confirmationExpiresAt) entry.copy(status = HomeActionStatus.EXPIRED, updatedAt = clock())
        else entry.copy(status = HomeActionStatus.READY, confirmedAt = clock(), updatedAt = clock())
    }
    suspend fun cancel(actionId: String): HomeLedgerEntry = change(actionId) { entry ->
        if (entry.status in setOf(HomeActionStatus.AWAITING_CONFIRMATION, HomeActionStatus.READY)) entry.copy(status = HomeActionStatus.CANCELLED, cancelRequested = true, updatedAt = clock())
        else entry.copy(cancelRequested = true, updatedAt = clock(), message = "Further processing cancelled. A sent action may already have taken effect.")
    }
    /** The final gate runs under the state mutex; it must not call back into this engine. */
    suspend fun dispatch(actionId: String, allowed: suspend () -> Boolean = { true }): HomeLedgerEntry {
        // Resolve metadata first; the final authorization and durable intent occur after this read.
        val initial = mutex.withLock { persistence.load() }
        val entry = initial.ledger.firstOrNull { it.action.id == actionId } ?: throw HomeException("Action not found.")
        if (entry.status != HomeActionStatus.READY) return entry
        val connection = initial.connections.firstOrNull { it.id == entry.action.connectionId } ?: throw HomeException("Connection removed.")
        val transport = connector(connection)
        try {
            val device = withTimeout(10_000) { transport.read(entry.action.entityId) } ?: throw HomeException("Device unavailable.")
            val authorized = mutex.withLock {
                val state = persistence.load(); val current = state.ledger.first { it.action.id == actionId }
                if (current.status != HomeActionStatus.READY || current.cancelRequested) return@withLock null
                val conn = state.connections.firstOrNull { it.id == connection.id }
                require(conn != null && conn.enabled && homeConnectionBinding(conn) == current.action.connectionBinding) { "Connection changed; review again." }
                require(device.identity == current.action.identity && device.available) { "Device identity or availability changed." }
                val cap = device.capabilities.firstOrNull { it.id == current.action.capabilityId } ?: throw HomeException("Action no longer supported.")
                require(homeCapabilityBinding(cap) == current.action.capabilityBinding) { "Capability changed; review again." }
                require(normalizeHomeParameters(cap, current.action.parameters) == current.action.parameters)
                if (!allowed()) {
                    val cancelled = current.copy(status = HomeActionStatus.CANCELLED, cancelRequested = true, updatedAt = clock(), message = "Action permission was withdrawn before sending.")
                    persistence.save(state.replace(cancelled)); return@withLock null
                }
                val now = clock()
                val granted = current.grantId?.let { gid -> state.grants.any { it.id == gid && matches(it, current.action, now) } } == true
                val confirmed = current.confirmedAt != null && now < current.confirmationExpiresAt
                if (!granted && !confirmed) {
                    val expired = current.copy(status = HomeActionStatus.EXPIRED, updatedAt = now, message = "Permission expired or was revoked. Review again.")
                    persistence.save(state.replace(expired)); return@withLock null
                }
                val sending = current.copy(status = HomeActionStatus.SENDING, sentAt = now, updatedAt = now, receiptId = if (conn.kind == HomeConnectorKind.GEEPERS) current.action.id else current.receiptId)
                persistence.save(state.replace(sending)); sending
            } ?: return mutex.withLock { persistence.load().ledger.first { it.action.id == actionId } }
            val result = try { withTimeout(15_000) { transport.execute(authorized.action) } }
            catch (e: CancellationException) {
                withContext(NonCancellable) { change(actionId) { it.copy(status = HomeActionStatus.UNKNOWN, updatedAt = clock(), message = "Interrupted after durable intent. Do not retry automatically.") } }; throw e
            } catch (_: Exception) { HomeDispatchResult(HomeActionStatus.UNKNOWN, message = "Delivery outcome is unknown. No automatic retry.") }
            return change(actionId) { it.copy(status = result.status.takeIf { s -> s in setOf(HomeActionStatus.ACCEPTED, HomeActionStatus.OBSERVED, HomeActionStatus.FAILED, HomeActionStatus.UNKNOWN) } ?: HomeActionStatus.UNKNOWN, receiptId = result.receiptId ?: it.receiptId, updatedAt = clock(), message = result.message) }
        } finally { transport.close() }
    }
    /** At most thirty seconds, read-only; never sends the original action again. */
    suspend fun reconcile(actionId: String): HomeLedgerEntry {
        val start = clock()
        try { withTimeoutOrNull(30_000) {
            while (true) {
                val state = mutex.withLock { persistence.load() }; val entry = state.ledger.first { it.action.id == actionId }
                if (entry.cancelRequested || entry.status !in setOf(HomeActionStatus.ACCEPTED, HomeActionStatus.UNKNOWN)) return@withTimeoutOrNull
                val connection = state.connections.firstOrNull { it.id == entry.action.connectionId && it.enabled && homeConnectionBinding(it) == entry.action.connectionBinding } ?: return@withTimeoutOrNull
                val transport = connector(connection)
                try {
                    val result = entry.receiptId?.let { transport.receipt(it) }
                    if (result != null && result.status in setOf(HomeActionStatus.OBSERVED, HomeActionStatus.FAILED)) {
                        change(actionId) { it.copy(status = result.status, updatedAt = clock(), message = result.message) }; return@withTimeoutOrNull
                    }
                    if (result != null && result.status == HomeActionStatus.ACCEPTED && result.message != entry.message) {
                        change(actionId) { it.copy(message = result.message, updatedAt = clock()) }
                    }
                    val observed = transport.read(entry.action.entityId)
                    if (observed != null && observed.identity == entry.action.identity && observed.available && observed.observedAt >= (entry.sentAt ?: Long.MAX_VALUE) && entry.action.expectedValues.isNotEmpty() && entry.action.expectedValues.all { (key,value) -> (if(key == "state") observed.state else observed.attributes[key]) == value }) {
                        change(actionId) { it.copy(status = HomeActionStatus.OBSERVED, updatedAt = clock(), message = "Controller reports the requested state. Physical outcome is unverified.") }; return@withTimeoutOrNull
                    }
                } finally { transport.close() }
                if (clock() - start >= 29_000) return@withTimeoutOrNull
                delay(1_000)
            }
        } } catch (e: CancellationException) { throw e } catch (_: Exception) { /* retain truthful accepted/unknown */ }
        return mutex.withLock { persistence.load().ledger.first { it.action.id == actionId } }
    }
    suspend fun recoverInterrupted() = mutex.withLock {
        val state = persistence.load(); val now = clock()
        persistence.save(state.copy(ledger = state.ledger.map { entry -> when {
            entry.status == HomeActionStatus.SENDING -> entry.copy(status = HomeActionStatus.UNKNOWN, updatedAt = now, message = "Interrupted after intent. Reconcile; do not resend.")
            entry.status in setOf(HomeActionStatus.READY, HomeActionStatus.AWAITING_CONFIRMATION) -> entry.copy(status = HomeActionStatus.EXPIRED, updatedAt = now, message = "Session ended. Review again before sending.")
            else -> entry
        } }))
    }
    private fun matches(grant: HomeGrant, action: HomeAction, now: Long) = grant.enabled && grant.connectionId == action.connectionId && grant.entityId == action.entityId && grant.identity == action.identity && grant.capabilityId == action.capabilityId && grant.connectionBinding == action.connectionBinding && grant.constraints == action.parameters && grant.capabilityBinding == action.capabilityBinding && (grant.expiresAt == null || now < grant.expiresAt)
    private suspend fun change(actionId: String, transform: (HomeLedgerEntry) -> HomeLedgerEntry): HomeLedgerEntry = mutex.withLock {
        val state = persistence.load(); val entry = transform(state.ledger.firstOrNull { it.action.id == actionId } ?: throw HomeException("Action not found.")); persistence.save(state.replace(entry)); entry
    }
    private fun HomeState.replace(entry: HomeLedgerEntry) = copy(ledger = ledger.map { if (it.action.id == entry.action.id) entry else it })
}
