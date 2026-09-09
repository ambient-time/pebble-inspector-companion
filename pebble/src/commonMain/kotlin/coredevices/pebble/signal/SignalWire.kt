package coredevices.pebble.signal

import kotlinx.serialization.json.*

/** The C snapshot uses native JSON values; history deliberately keeps a readable value string. */
object SignalWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun observations(payload: JsonElement, received: Long): List<SignalObservation>? {
        val array = payload as? JsonArray ?: return null
        if (array.size > 12) return null
        return runCatching {
            array.map { item ->
                val fields = (item as? JsonObject)?.toMutableMap() ?: return null
                val raw = fields["value"]
                val text = when (raw) {
                    null, JsonNull -> ""
                    is JsonPrimitive -> raw.content
                    else -> raw.toString()
                }
                if (text.encodeToByteArray().size > 1000) return null
                fields["value"] = JsonPrimitive(text)
                fields["collectedAt"] = JsonPrimitive(received)
                if ((fields["status"] as? JsonPrimitive)?.contentOrNull == "timestamp_unknown") fields["measuredAt"] = JsonNull
                json.decodeFromJsonElement<SignalObservation>(JsonObject(fields))
            }
        }.getOrNull()
    }
}
