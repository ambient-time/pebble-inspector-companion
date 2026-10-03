package coredevices.pebble.signal

import kotlinx.serialization.json.Json

/** Pure projection: audit evidence moves out of the executable intent set, never
 * back into it. Limits apply to current work, not a lifetime request counter. */
object SignalHomeRetention {
    const val MAX_LIVE = 64
    const val MAX_RECENT = 128
    const val MAX_LEDGER_BYTES = 384 * 1024
    const val MAX_STATE_BYTES = 768 * 1024
    const val MAX_ENTRY_BYTES = 48 * 1024
    private val json = Json { encodeDefaults = true }
    data class Projection(val state: HomeState, val archived: List<HomeLedgerEntry>)

    fun project(state: HomeState, now: Long, pinned: Set<String> = emptySet()): Projection {
        val normalized = state.ledger.map { entry ->
            if (entry.status in setOf(HomeActionStatus.AWAITING_CONFIRMATION, HomeActionStatus.READY) && now >= entry.confirmationExpiresAt)
                entry.copy(status = HomeActionStatus.EXPIRED, updatedAt = now, message = "Confirmation expired. Review a new action; nothing was sent.")
            else entry
        }
        require(normalized.map { it.action.id }.distinct().size == normalized.size) { "Duplicate native intent identifier." }
        val sizes = normalized.associate { it.action.id to json.encodeToString(HomeLedgerEntry.serializer(), it).encodeToByteArray().size }
        if (sizes.values.any { it > MAX_ENTRY_BYTES }) throw HomeException("Home action details exceed the safe storage limit.")
        val live = normalized.filter { it.action.id in pinned || it.status in setOf(HomeActionStatus.AWAITING_CONFIRMATION, HomeActionStatus.READY, HomeActionStatus.SENDING) }
        if (live.size > MAX_LIVE) throw HomeException("Too many Home actions are still active. Cancel or finish a review, then try again.")
        val kept = live.map { it.action.id }.toMutableSet()
        var bytes = live.sumOf { sizes.getValue(it.action.id) }
        if (bytes > MAX_LEDGER_BYTES) throw HomeException("Active Home reviews are too large. Cancel or finish a review, then try again.")
        var recent = 0
        normalized.asReversed().filter { it.action.id !in kept }.forEach { entry ->
            val size = sizes.getValue(entry.action.id)
            if (recent < MAX_RECENT && bytes + size <= MAX_LEDGER_BYTES) { kept += entry.action.id; bytes += size; recent++ }
        }
        val archived = normalized.filter { it.action.id !in kept }
        val retained = state.copy(ledger = normalized.filter { it.action.id in kept }, archivedIntents = state.archivedIntents + archived.size)
        if (json.encodeToString(HomeState.serializer(), retained).encodeToByteArray().size > MAX_STATE_BYTES) throw HomeException("Home settings and catalog exceed the safe storage limit. Reduce their size before retrying.")
        return Projection(retained, archived)
    }
}
