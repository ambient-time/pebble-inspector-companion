package com.lukesteuber.signalstation

import coredevices.pebble.signal.AndroidSignalStation
import kotlinx.serialization.json.*

internal sealed interface GarminInbound {
    val session: Int
    data class Hello(override val session: Int) : GarminInbound
    data class Bye(override val session: Int) : GarminInbound
    data class Request(override val session: Int, val id: Int, val route: String, val body: JsonObject) : GarminInbound
}

/** Garmin Connect mailbox messages for the Signal Station watch app (garmin/signal-station). */
internal object GarminWire {
    const val VERSION = 1
    private val methods = mapOf("capabilities" to "GET", "history" to "GET", "home" to "POST", "clear" to "POST")
    private const val MAX_FIELDS = 16
    private const val MAX_TEXT = 1024

    fun parse(message: Any?): GarminInbound? {
        val map = message as? Map<*, *> ?: return null
        if (number(map["v"]) != VERSION) return null
        val session = number(map["s"])?.takeIf { it > 0 } ?: return null
        return when (map["t"]) {
            "hello" -> GarminInbound.Hello(session)
            "bye" -> GarminInbound.Bye(session)
            "req" -> {
                val id = number(map["id"])?.takeIf { it > 0 } ?: return null
                val route = (map["route"] as? String)?.takeIf { it in methods } ?: return null
                val body = body(map["body"]) ?: return null
                GarminInbound.Request(session, id, route, body)
            }
            else -> null
        }
    }

    fun method(route: String): String = methods.getValue(route)

    fun url(request: GarminInbound.Request): String {
        // The station accepts a query only on history; capabilities must have none.
        val query = if (request.route == "history") {
            (request.body["request_id"] as? JsonPrimitive)?.intOrNull?.let { "?request_id=$it" }.orEmpty()
        } else ""
        return AndroidSignalStation.PREFIX + request.route + query
    }

    fun response(session: Int, id: Int, status: Int, result: String): Map<String, Any> {
        val data = runCatching { Json.parseToJsonElement(result) }.getOrNull() as? JsonObject ?: JsonObject(emptyMap())
        return mapOf("v" to VERSION, "s" to session, "t" to "res", "id" to id, "status" to status, "data" to (monkey(data) ?: emptyMap<String, Any>()))
    }

    fun reset(session: Int): Map<String, Any> = mapOf("v" to VERSION, "s" to session, "t" to "reset")

    /** The watch sends flat dictionaries of strings, numbers and booleans. */
    private fun body(value: Any?): JsonObject? {
        val map = value as? Map<*, *> ?: return null
        if (map.size > MAX_FIELDS) return null
        return buildJsonObject {
            for ((key, item) in map) {
                if (key !is String || key.length > 64) return null
                when (item) {
                    is String -> if (item.length > MAX_TEXT) return null else put(key, item)
                    is Boolean -> put(key, item)
                    is Int, is Long -> put(key, (item as Number).toLong())
                    else -> return null
                }
            }
        }
    }

    private fun number(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
        else -> null
    }

    /** Monkey C has no null in phone messages; nulls are dropped. */
    private fun monkey(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.entries.mapNotNull { (key, value) -> monkey(value)?.let { key to it } }.toMap(LinkedHashMap())
        is JsonArray -> element.mapNotNull(::monkey)
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.boolean
            element.intOrNull != null -> element.int
            element.longOrNull != null -> element.long
            else -> element.doubleOrNull
        }
    }
}
