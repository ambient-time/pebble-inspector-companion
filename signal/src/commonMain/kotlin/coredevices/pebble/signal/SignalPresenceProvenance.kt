package coredevices.pebble.signal

/** Repairs the source dependency omitted from legacy saved-place Wi-Fi clues. */
object SignalPresenceProvenance {
    fun repair(records: List<SignalRecord>): List<SignalRecord> {
        val dependents = mutableMapOf<String, MutableSet<String>>()
        val affected = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        records.forEach { record ->
            record.references.forEach { reference -> dependents.getOrPut(reference) { mutableSetOf() }.add(record.id) }
            val wifiDependent = "presence.wifi" in record.sourceKeys || record.observations.any {
                it.key == "presence.wifi" || (it.key == "presence.places" && it.identity.isEmpty() && it.value.contains("Wi-Fi", ignoreCase = true))
            }
            if (wifiDependent && affected.add(record.id)) queue.addLast(record.id)
        }
        // Each ID and reference edge is visited at most once; cycles cannot loop.
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            dependents[id].orEmpty().forEach { dependent -> if (affected.add(dependent)) queue.addLast(dependent) }
        }
        return records.map { record ->
            if (record.id in affected && "presence.wifi" !in record.sourceKeys)
                record.copy(sourceKeys = record.sourceKeys + "presence.wifi") else record
        }
    }
}
