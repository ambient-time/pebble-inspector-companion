package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.math.round

data class SignalLookupRequest(val review: SignalLookupReview, val sourceKeys: Set<String>, val fix: SignalPresenceFix? = null)

/** Explicit reviewed queries only. No upload endpoint, credentials, redirect or automatic fallback. */
class SignalLookupProviders(http: HttpClient) {
    private val client = HttpClient(http.engine) { followRedirects = false; expectSuccess = false }
    fun close() = client.close()
    suspend fun lookup(request: SignalLookupRequest, now: Long): List<SignalObservation> = withTimeoutOrNull(12_000) {
        val review = request.review
        client.preparePost(review.endpoint) {
            header("User-Agent", "SignalStation/0.3 (https://dr.eamer.dev/downloads/apps/signal-station/)")
            contentType(if (review.kind == "radio_location") ContentType.Application.Json else ContentType.Text.Plain)
            setBody(review.payload)
        }.execute { response ->
            if (response.status.value == 429) throw SignalProviderException("Lookup rate limited. Try later; no automatic retry was made.")
            if (response.status.value == 404) return@execute missing(review, "not_found", now)
            if (response.status.value !in 200..299) throw SignalProviderException("Lookup service unavailable (${response.status.value}). Your capture is still saved.")
            val channel = response.bodyAsChannel()
            val bytes = ByteArray(512 * 1024 + 1)
            var count = 0
            while (count < bytes.size) {
                val read = channel.readAvailable(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            if (count > 512 * 1024) throw SignalProviderException("Lookup response exceeded the size limit. Your capture is still saved.")
            val data = Json.parseToJsonElement(bytes.decodeToString(0, count)) as? JsonObject
                ?: throw SignalProviderException("Lookup returned an unreadable result.")
            if (review.kind == "radio_location") parseLocation(data, review, now) else parsePlaces(data, request, now)
        }
    } ?: throw SignalProviderException("Lookup timed out. Your capture is still saved; no retry was made.")

    companion object {
        fun cacheIdentity(review: SignalLookupReview): String {
            val payload = if (review.kind != "radio_location") review.payload else runCatching {
                val root = Json.parseToJsonElement(review.payload).jsonObject
                JsonObject(root.mapValues { (key, value) ->
                    if (key == "wifiAccessPoints") JsonArray(value.jsonArray.map { JsonObject(it.jsonObject - "age") }) else value
                }).toString()
            }.getOrDefault(review.payload)
            return review.kind + "\n" + review.endpoint + "\n" + payload
        }
        fun key(kind: String) = if (kind == "radio_location") "location.radio" else "places.nearby"
        fun endpointValid(value: String): Boolean = runCatching {
            val url = Url(value)
            url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() &&
                url.fragment.isEmpty() && url.parameters.isEmpty() && "geosubmit" !in url.encodedPath.lowercase()
        }.getOrDefault(false)
        fun prepare(kind: String, record: SignalRecord, settings: SignalSettings, now: Long): SignalLookupRequest {
            if (kind !in setOf("nearby", "address", "radio_location")) throw SignalProviderException("Choose a supported lookup.")
            if (record.state != "ready" || !SignalHistory.allowed(record, settings.enabled)) throw SignalProviderException("Capture the enabled sources again before looking them up.")
            val enabled = settings.lookups
            if (key(kind) !in settings.enabled || if (kind == "radio_location") !enabled.radioLocation else !enabled.nearbyPlaces)
                throw SignalProviderException("Enable this external lookup separately before reviewing a request.")
            val endpoint = when (kind) { "nearby" -> enabled.nearbyEndpoint; "address" -> enabled.addressEndpoint; else -> enabled.radioEndpoint }
            if (!endpointValid(endpoint)) throw SignalProviderException("Use an HTTPS lookup endpoint without embedded credentials or query parameters.")
            if (kind == "radio_location") return radioRequest(record, settings, now, endpoint)
            val fix = SignalContext.fix(record, now) ?: throw SignalProviderException("Capture a phone location first. The lookup needs a measured fix less than 15 minutes old.")
            if ("location" !in settings.enabled) throw SignalProviderException("Enable phone location for this lookup.")
            if (fix.accuracyMeters > 1000) throw SignalProviderException("This location is too imprecise for nearby places. Capture again with a better fix.")
            val lat = round(fix.latitude * 10000) / 10000
            val lon = round(fix.longitude * 10000) / 10000
            val radius = enabled.radiusMeters.coerceIn(100, 1000)
            val filter = if (kind == "address") "[\"addr:street\"]" else "[\"name\"][~\"^(amenity|shop|tourism|leisure|office)$\"~\".\"]"
            val query = "[out:json][timeout:8][maxsize:524288];nwr(around:$radius,$lat,$lon)$filter;out center 100;"
            val disclosure = "Send the captured location rounded to four decimal places ($lat, $lon) and a $radius m search radius to ${Url(endpoint).host}. Phone accuracy: ${fix.accuracyMeters.toInt()} m; measured ${signalDateTime(fix.measuredAt)}. Map results are candidates to review. The service also sees your IP address. This does not send a model request."
            return SignalLookupRequest(SignalLookupReview(kind, record.id, endpoint, disclosure, query), setOf("location", "places.nearby"), fix.copy(latitude = lat, longitude = lon))
        }

        private fun radioRequest(record: SignalRecord, settings: SignalSettings, now: Long, endpoint: String): SignalLookupRequest {
            fun fresh(row: SignalObservation) = row.key in settings.enabled && row.status in setOf("fresh", "available", "cached") && row.measuredAt?.let { now - it in 0..300_000 } == true
            val rows = record.observations.filter(::fresh)
            val used = mutableSetOf("location.radio")
            val wifi = rows.filter { it.key == "wifi.identifiers" }.mapNotNull { identity ->
                val slot = identity.fields["slot"] ?: return@mapNotNull null
                val address = identity.fields["bssid"]?.takeIf { it.matches(Regex("[a-fA-F0-9]{2}(:[a-fA-F0-9]{2}){5}")) } ?: return@mapNotNull null
                // The provider's privacy contract excludes hidden and _nomap networks.
                val name = rows.firstOrNull { it.key == "wifi.names" && it.fields["slot"] == slot }?.fields?.get("ssid")
                    ?.takeIf { it.isNotBlank() && !it.endsWith("_nomap", ignoreCase = true) } ?: return@mapNotNull null
                val signal = rows.firstOrNull { it.key == "wifi" && it.fields["slot"] == slot }
                used += setOf("wifi.identifiers", "wifi.names")
                buildJsonObject {
                    put("macAddress", address.lowercase()); put("ssid", name.take(100)); put("age", now - identity.measuredAt!!)
                    signal?.number?.takeIf { it.isFinite() && it in -150.0..0.0 }?.let { put("signalStrength", it); used += "wifi" }
                }
            }.distinctBy { it["macAddress"] }.take(32)
            val cells = rows.filter { it.key == "cellular.identifiers" }.mapNotNull { row ->
                val f = row.fields
                val radio = f["radioType"]?.takeIf { it in setOf("gsm", "wcdma", "lte") } ?: return@mapNotNull null
                val mcc = f["mcc"]?.toIntOrNull()?.takeIf { it in 1..999 } ?: return@mapNotNull null
                val mnc = f["mnc"]?.toIntOrNull()?.takeIf { it in 0..999 } ?: return@mapNotNull null
                val area = f["locationAreaCode"]?.toLongOrNull()?.takeIf { it in 0..65535 } ?: return@mapNotNull null
                val id = f["cellId"]?.toLongOrNull()?.takeIf { it in 0..268435455 } ?: return@mapNotNull null
                used += "cellular.identifiers"
                buildJsonObject { put("radioType", radio); put("mobileCountryCode", mcc); put("mobileNetworkCode", mnc); put("locationAreaCode", area); put("cellId", id) }
            }.distinct().take(16)
            if (wifi.size < 2 && cells.isEmpty()) throw SignalProviderException("Capture at least two visible Wi-Fi networks with names and identifiers, or a supported cell tower. Hidden and _nomap networks are excluded.")
            val body = buildJsonObject {
                put("considerIp", false); put("fallbacks", buildJsonObject { put("ipf", false); put("lacf", true) })
                if (wifi.size >= 2) put("wifiAccessPoints", JsonArray(wifi)) else used.removeAll(setOf("wifi", "wifi.names", "wifi.identifiers"))
                if (cells.isNotEmpty()) put("cellTowers", JsonArray(cells))
            }.toString()
            val disclosure = "Send ${if (wifi.size >= 2) wifi.size else 0} Wi-Fi identifiers and network names${if ("wifi" in used) " with signal levels" else ""}, and ${cells.size} cell tower identities/network codes to ${Url(endpoint).host}. GPS coordinates, Bluetooth and health are excluded. This asks for an estimate, not a database contribution. The service sees your IP address; IP location fallback is requested off. Cell-area fallback may be approximate."
            return SignalLookupRequest(SignalLookupReview("radio_location", record.id, endpoint, disclosure, body), used)
        }

        private fun JsonObject.number(name: String) = (this[name] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull?.take(300).orEmpty()
        fun missing(review: SignalLookupReview, status: String, now: Long) = listOf(SignalObservation(key(review.kind), Url(review.endpoint).host,
            "No usable lookup result. Original capture is preserved.", collectedAt = now, status = status,
            fields = mapOf("provider" to Url(review.endpoint).host, "retrievedAt" to now.toString(), "expiresAt" to now.toString())))
        fun parseLocation(data: JsonObject, review: SignalLookupReview, now: Long): List<SignalObservation> {
            val location = data["location"] as? JsonObject ?: return missing(review, "invalid_result", now)
            val lat = location.number("lat") ?: return missing(review, "invalid_result", now)
            val lon = location.number("lng") ?: return missing(review, "invalid_result", now)
            val accuracy = data.number("accuracy") ?: return missing(review, "invalid_result", now)
            if (!SignalContext.validCoordinate(lat, lon) || accuracy !in 0.0..20_000_000.0) return missing(review, "invalid_result", now)
            val fallback = data.text("fallback")
            val method = when (fallback) { "ipf" -> "IP fallback returned despite disabled request"; "lacf" -> "cell area fallback"; "" -> "method unspecified by provider"; else -> "unrecognized fallback" }
            return listOf(SignalObservation("location.radio", Url(review.endpoint).host, "$lat, $lon; provider accuracy $accuracy m; $method. Separate estimate; phone location is unchanged.",
                collectedAt = now, measuredAt = now, status = if (fallback == "ipf" || accuracy > 1000) "coarse_estimate" else "estimate",
                fields = mapOf("latitude" to lat.toString(), "longitude" to lon.toString(), "accuracyMeters" to accuracy.toString(), "method" to method,
                    "provider" to Url(review.endpoint).host, "retrievedAt" to now.toString(), "expiresAt" to (now + 300_000).toString())))
        }
        fun parsePlaces(data: JsonObject, request: SignalLookupRequest, now: Long): List<SignalObservation> {
            val fix = request.fix ?: return missing(request.review, "invalid_result", now)
            val elements = data["elements"] as? JsonArray ?: return missing(request.review, "invalid_result", now)
            val rows = elements.take(100).mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val tags = row["tags"] as? JsonObject ?: return@mapNotNull null
                val coordinate = row["center"] as? JsonObject ?: row
                val lat = coordinate.number("lat") ?: return@mapNotNull null
                val lon = coordinate.number("lon") ?: return@mapNotNull null
                if (!SignalContext.validCoordinate(lat, lon)) return@mapNotNull null
                val name = if (request.review.kind == "address") listOf(tags.text("addr:housenumber"), tags.text("addr:street"), tags.text("addr:city")).filter { it.isNotBlank() }.joinToString(" ") else tags.text("name")
                if (name.isBlank()) return@mapNotNull null
                val distance = SignalContext.distance(fix.latitude, fix.longitude, lat, lon)
                if (distance > 2000) return@mapNotNull null
                val category = listOf("amenity", "shop", "tourism", "leisure", "office").map { tags.text(it) }.firstOrNull { it.isNotBlank() } ?: "address"
                val sourceId = "${row.text("type")}:${row.text("id")}".takeIf { it != ":" } ?: return@mapNotNull null
                SignalObservation("places.nearby", Url(request.review.endpoint).host, "$name · $category · approximately ${distance.toInt()} m from captured fix. Candidate; confirm before saving.",
                    collectedAt = now, measuredAt = fix.measuredAt, status = "candidate", identity = "osm:$sourceId",
                    fields = mapOf("name" to name.take(300), "category" to category, "latitude" to lat.toString(), "longitude" to lon.toString(),
                        "distanceMeters" to distance.toInt().toString(), "accuracyMeters" to fix.accuracyMeters.toString(), "provider" to Url(request.review.endpoint).host,
                        "retrievedAt" to now.toString(), "expiresAt" to (now + 3_600_000).toString(), "attribution" to "© OpenStreetMap contributors", "lookupKind" to request.review.kind))
            }.distinctBy { it.identity }.sortedBy { it.fields["distanceMeters"]?.toIntOrNull() }.take(20)
            if (rows.isEmpty()) return missing(request.review, "not_found", now)
            return rows + SignalObservation("places.nearby", Url(request.review.endpoint).host, "${rows.size} nearby candidates retained from a bounded map query; map coverage can be incomplete.", collectedAt = now,
                status = "partial", metric = "coverage", fields = mapOf("omitted" to (elements.size - rows.size).coerceAtLeast(0).toString()))
        }
    }
}
