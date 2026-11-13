package coredevices.pebble.signal

/** Radio comparison joins only explicitly retained identifiers, never scan positions across captures. */
object SignalRadioChanges {
    fun describe(before: SignalRecord, after: SignalRecord): List<String> = buildList {
        for (radio in listOf("wifi", "bluetooth", "cellular")) {
            val label = when (radio) { "wifi" -> "Wi-Fi"; "bluetooth" -> "Bluetooth"; else -> "Cell" }
            fun signals(record: SignalRecord) = record.observations.filter { it.key == radio && it.metric in setOf("rssi", "signal") && SignalLearning.fresh(it) && it.number?.isFinite() == true }
            val old = signals(before); val next = signals(after)
            if (old.isEmpty() || next.isEmpty()) continue
            if (next.maxOf { it.measuredAt!! } <= old.maxOf { it.measuredAt!! }) {
                add("$label: no newer scan to compare. Cached or repeated readings are not a change.")
                continue
            }
            add("$label: ${old.size} → ${next.size} signals in range. Reception and scan coverage can change this count.")
            fun identified(record: SignalRecord, signals: List<SignalObservation>): Map<String, SignalObservation> = record.observations
                .filter { it.key == "$radio.identifiers" && SignalLearning.fresh(it) }
                .mapNotNull { id ->
                    val slot = id.fields["slot"] ?: return@mapNotNull null
                    val signal = signals.singleOrNull { it.fields["slot"] == slot } ?: return@mapNotNull null
                    val identity = when (radio) {
                        "wifi" -> id.fields["bssid"]?.lowercase()
                        "bluetooth" -> id.fields["beacon"]?.takeIf { it.isNotBlank() } ?: id.fields["address"]?.lowercase()
                        else -> listOf("radioType", "mcc", "mnc", "locationAreaCode", "cellId").map { id.fields[it] }.takeIf { it.all { value -> !value.isNullOrBlank() } }?.joinToString(":")
                    } ?: return@mapNotNull null
                    identity to signal
                }.groupBy { it.first }.mapNotNull { (id, rows) -> rows.singleOrNull()?.let { id to it.second } }.toMap()
            val a = identified(before, old); val b = identified(after, next)
            val pairs = a.keys.intersect(b.keys).mapNotNull { id ->
                val left = a.getValue(id); val right = b.getValue(id)
                if (right.measuredAt!! <= left.measuredAt!!) null else left to right
            }
            if (pairs.isEmpty()) add("$label: no signal could be matched across the two captures, so none is compared one to one.")
            else {
                val deltas = pairs.map { it.second.number!! - it.first.number!! }.sorted()
                val median = if (deltas.size % 2 == 0) (deltas[deltas.size / 2 - 1] + deltas[deltas.size / 2]) / 2 else deltas[deltas.size / 2]
                add("$label: ${pairs.size} matched in both captures; median signal difference ${SignalDisplay.number(median)} dB. Signal strength is not distance or proof of a person's presence.")
            }
        }
    }
}
