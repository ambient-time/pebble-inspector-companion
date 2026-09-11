package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class SignalContextTest {
    private val now = 1_800_000_000_000L
    private fun location(accuracy: String = "10") = SignalObservation("location", "phone", collectedAt = now, measuredAt = now, status = "fresh",
        fields = mapOf("latitude" to "45.500012", "longitude" to "-122.600012", "accuracyMeters" to accuracy))
    private fun record(rows: List<SignalObservation>) = SignalRecord("capture", "thread", now, "Capture", provider = "local", model = "", state = "ready", kind = "capture", observations = rows, sourceKeys = rows.map { it.key }.toSet())
    private fun settings(record: SignalRecord) = SignalSettings(enabled = record.sourceKeys + setOf("places.nearby", "location.radio"), lookups = SignalLookupSettings(nearbyPlaces = true, radioLocation = true))
    private fun wifi(slot: Int, name: String) = listOf(
        SignalObservation("wifi.identifiers", "phone", collectedAt = now, measuredAt = now, status = "fresh", fields = mapOf("slot" to "$slot", "bssid" to "00:11:22:33:44:${slot.toString().padStart(2, '0')}")),
        SignalObservation("wifi.names", "phone", collectedAt = now, measuredAt = now, status = "fresh", fields = mapOf("slot" to "$slot", "ssid" to name)),
        SignalObservation("wifi", "phone", collectedAt = now, measuredAt = now, status = "fresh", number = -50.0, fields = mapOf("slot" to "$slot")),
    )
    @Test fun oldSettingsAndGroupSelectionCannotEnableDisclosure() {
        val old = Json.decodeFromString<SignalSettings>("""{"enabled":["location","places.nearby"]}""")
        assertFalse(old.lookups.nearbyPlaces)
        assertFailsWith<SignalProviderException> { SignalLookupProviders.prepare("nearby", record(listOf(location())), old, now) }
    }
    @Test fun coordinateLookupUsesOnlyReviewedRoundedPositionAndBoundedRadius() {
        val record = record(listOf(location()))
        val prepared = SignalLookupProviders.prepare("nearby", record, settings(record).copy(lookups = SignalLookupSettings(nearbyPlaces = true, radiusMeters = 99999)), now)
        assertTrue(prepared.review.payload.contains("around:1000,45.5,-122.6"))
        assertFalse(prepared.review.payload.contains("45.500012"))
        assertEquals(setOf("location", "places.nearby"), prepared.sourceKeys)
        assertEquals("capture", prepared.review.recordId)
        assertTrue(prepared.review.disclosure.contains("IP address"))
    }
    @Test fun staleAndImpreciseFixesDoNotBecomeVenueGuesses() {
        for (row in listOf(location("2000"), location("NaN"), location().copy(measuredAt = now - 900001))) {
            val record = record(listOf(row))
            assertFailsWith<SignalProviderException> { SignalLookupProviders.prepare("nearby", record, settings(record), now) }
        }
    }
    @Test fun radioPayloadExcludesHiddenNomapGpsAndUnselectedCategories() {
        val record = record(wifi(1, "Cafe") + wifi(2, "Library") + wifi(3, "Private_nomap") + wifi(4, "") + location())
        val request = SignalLookupProviders.prepare("radio_location", record, settings(record), now)
        val body = Json.parseToJsonElement(request.review.payload).jsonObject
        assertEquals(2, body["wifiAccessPoints"]!!.jsonArray.size)
        assertFalse(request.review.payload.contains("Private_nomap"))
        assertFalse(request.review.payload.contains("latitude"))
        assertFalse(request.sourceKeys.contains("location"))
        assertEquals(JsonPrimitive(false), body["considerIp"])
        assertEquals(JsonPrimitive(false), body["fallbacks"]!!.jsonObject["ipf"])
        val withoutNames = record.copy(observations = record.observations.filterNot { it.key == "wifi.names" }, sourceKeys = record.sourceKeys - "wifi.names")
        assertFailsWith<SignalProviderException> { SignalLookupProviders.prepare("radio_location", withoutNames, settings(withoutNames), now) }
    }
    @Test fun oneNetworkOrOldRadioCannotTriggerLocationLookup() {
        for (rows in listOf(wifi(1, "Cafe"), (wifi(1, "Cafe") + wifi(2, "Library")).map { it.copy(measuredAt = now - 300001) })) {
            val record = record(rows)
            assertFailsWith<SignalProviderException> { SignalLookupProviders.prepare("radio_location", record, settings(record), now) }
        }
    }
    @Test fun unsupportedAndSentinelCellIdentifiersAreRejected() {
        for (type in listOf("nr", "lte")) {
            val row = SignalObservation("cellular.identifiers", "phone", collectedAt = now, measuredAt = now, status = "fresh",
                fields = mapOf("radioType" to type, "mcc" to "310", "mnc" to "260", "locationAreaCode" to "100", "cellId" to Int.MAX_VALUE.toString()))
            val record = record(listOf(row))
            assertFailsWith<SignalProviderException> { SignalLookupProviders.prepare("radio_location", record, settings(record), now) }
        }
    }
    @Test fun mapResponseDeduplicatesCandidatesAndRetainsProvenance() {
        val record = record(listOf(location()))
        val request = SignalLookupProviders.prepare("nearby", record, settings(record), now)
        val data = Json.parseToJsonElement("""{"elements":[{"type":"node","id":1,"lat":45.5001,"lon":-122.6,"tags":{"name":"Cafe","amenity":"cafe"}},{"type":"node","id":1,"lat":45.5001,"lon":-122.6,"tags":{"name":"Cafe","amenity":"cafe"}},{"type":"node","id":2,"lat":90,"lon":0,"tags":{"name":"Far"}}]}""").jsonObject
        val rows = SignalLookupProviders.parsePlaces(data, request, now)
        val candidate = rows.single { it.status == "candidate" }
        assertEquals("osm:node:1", candidate.identity)
        assertEquals("Cafe", candidate.fields["name"])
        assertEquals("© OpenStreetMap contributors", candidate.fields["attribution"])
        assertTrue(candidate.fields["expiresAt"]!!.toLong() > now)
    }
    @Test fun estimatesPreserveCoarseFallbackAndRejectBadCoordinates() {
        val review = SignalLookupReview("radio_location", "capture", "https://api.beacondb.net/v1/geolocate", "", "{}")
        val result = SignalLookupProviders.parseLocation(Json.parseToJsonElement("""{"location":{"lat":45,"lng":-122},"accuracy":50000,"fallback":"ipf"}""").jsonObject, review, now).single()
        assertEquals("coarse_estimate", result.status)
        assertTrue(result.fields["method"]!!.contains("despite disabled"))
        assertEquals("invalid_result", SignalLookupProviders.parseLocation(Json.parseToJsonElement("""{"location":{"lat":145,"lng":-122},"accuracy":10}""").jsonObject, review, now).single().status)
    }
    @Test fun requestIsExactAndDoesNotFollowRedirectsOrSendCredentials() = runBlocking {
        val record = record(listOf(location()))
        val request = SignalLookupProviders.prepare("nearby", record, settings(record), now)
        var calls = 0
        val http = HttpClient(MockEngine { wire ->
            calls++
            assertEquals(request.review.payload, (wire.body as TextContent).text)
            assertEquals(HttpMethod.Post, wire.method)
            assertNull(wire.headers[HttpHeaders.Authorization])
            assertTrue(wire.headers[HttpHeaders.UserAgent]!!.startsWith("SignalStation/"))
            respond("private response", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://unexpected.test"))
        })
        val service = SignalLookupProviders(http)
        try { assertFailsWith<SignalProviderException> { service.lookup(request, now) }; assertEquals(1, calls) }
        finally { service.close(); http.close() }
    }
    @Test fun oversizedAndRateLimitedResponsesDoNotRetry() = runBlocking {
        val record = record(listOf(location()))
        val request = SignalLookupProviders.prepare("nearby", record, settings(record), now)
        for ((body, code) in listOf("x".repeat(512 * 1024 + 2) to HttpStatusCode.OK, "private" to HttpStatusCode.TooManyRequests)) {
            var calls = 0
            val http = HttpClient(MockEngine { calls++; respond(body, code) })
            val service = SignalLookupProviders(http)
            try { val error = assertFailsWith<SignalProviderException> { service.lookup(request, now) }; assertFalse(error.message.orEmpty().contains(body)); assertEquals(1, calls) }
            finally { service.close(); http.close() }
        }
    }
    @Test fun cancellationReachesLookupTransport() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { entered.complete(Unit); try { awaitCancellation() } finally { stopped.complete(Unit) } })
        val service = SignalLookupProviders(http)
        val record = record(listOf(location()))
        try {
            val job = launch { service.lookup(SignalLookupProviders.prepare("nearby", record, settings(record), now), now) }
            entered.await(); job.cancelAndJoin(); stopped.await()
            assertTrue(job.isCancelled)
        } finally { service.close(); http.close() }
    }
    @Test fun endpointRejectsCleartextCredentialsAndUploadRoutes() {
        for (url in listOf("http://example.test", "https://user:password@example.test", "https://example.test/v2/geosubmit", "https://example.test?key=secret")) assertFalse(SignalLookupProviders.endpointValid(url))
        assertTrue(SignalLookupProviders.endpointValid("https://example.test/v1/geolocate"))
    }
    @Test fun channelsAndSecurityDoNotInventInternetAvailability() {
        assertEquals(1, SignalWireless.channel(2412)); assertEquals(14, SignalWireless.channel(2484))
        assertEquals(36, SignalWireless.channel(5180)); assertEquals(1, SignalWireless.channel(5955))
        assertEquals(2, SignalWireless.channel(5935)); assertNull(SignalWireless.channel(1234))
        assertTrue(SignalWireless.security("[OWE][ESS]").contains("OWE encryption"))
        assertTrue(SignalWireless.security("[ESS]").contains("internet and captive portal unknown"))
        assertEquals("unknown", SignalWireless.security("[MADE_UP]"))
    }
}
