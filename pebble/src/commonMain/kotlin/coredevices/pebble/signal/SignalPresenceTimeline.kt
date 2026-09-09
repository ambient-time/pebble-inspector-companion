package coredevices.pebble.signal

data class SignalPresenceEvidence(
    val targetId: String,
    val label: String,
    val state: String,
    val lastSeenAt: Long?,
    val freshCaptures: Int,
)

object SignalPresenceTimeline {
    fun entries(settings: SignalSettings, records: List<SignalRecord>, now: Long): List<SignalPresenceEvidence> =
        settings.presenceTargets.filter { it.enabled && "presence.${it.radio}" in settings.enabled }.map { target ->
            val rows = records.asSequence()
                .filter { it.state == "ready" && it.references.isEmpty() && SignalHistory.allowed(it, settings.enabled) }
                .distinctBy { it.id }
                .mapNotNull { record -> record.observations.filter {
                    it.identity == "target:${target.id}" && it.key == "presence.${target.radio}" && it.source == "phone" && it.collectedAt <= now
                }.maxByOrNull { it.collectedAt } }
                .sortedByDescending { it.collectedAt }.toList()
            val sightings = rows.filter { row -> row.status == "observed" && row.measuredAt?.let { row.collectedAt - it in 0..15_000 } == true }
            val recent = sightings.mapNotNull { it.measuredAt }.filter { now - it in 0..300_000 }.distinct()
            val latest = rows.firstOrNull()
            val state = when {
                latest == null || now - latest.collectedAt !in 0..15_000 -> "unknown"
                latest.status == "observed" && latest.measuredAt?.let { now - it in 0..15_000 } == true -> if (recent.size >= 2) "observed_repeatedly" else "observed_once"
                latest.status == "not_observed" -> "not_observed"
                latest.status == "ambiguous" -> "ambiguous"
                else -> "unknown"
            }
            SignalPresenceEvidence(target.id, target.label, state, sightings.mapNotNull { it.measuredAt }.maxOrNull(), recent.size)
        }
}
