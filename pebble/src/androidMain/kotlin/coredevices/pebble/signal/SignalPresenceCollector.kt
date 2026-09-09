package coredevices.pebble.signal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class SignalPresenceCollector(private val context: Context) {
    private val lookupLock = Mutex()
    private var lastLookupAt = -1000L
    private val lookupCache = linkedMapOf<String, Pair<Long, SignalPlaceLookup>>()
    private val collectors by lazy { SignalCollectors(context) }
    suspend fun collect(settings: SignalSettings): SignalPresenceResult = coroutineScope {
        val keys = buildSet {
            if ("presence.bluetooth" in settings.enabled) addAll(listOf("bluetooth", "bluetooth.names", "bluetooth.identifiers", "bluetooth.services"))
            if ("presence.wifi" in settings.enabled) addAll(listOf("wifi", "wifi.names", "wifi.identifiers"))
        }
        val location = async { if ("presence.places" in settings.enabled) locate() else null }
        val readings = if (keys.isEmpty()) emptyList() else collectors.collect(settings.copy(enabled = keys))
        val candidates = listOf("bluetooth", "wifi").flatMap { radio ->
            val rows = readings.filter { it.key == radio || it.key.startsWith("$radio.") }.groupBy { Regex("^observation=(\\d+);").find(it.value)?.groupValues?.get(1) }
            rows.filterKeys { it != null }.values.mapNotNull { group ->
                val signal = group.firstOrNull { it.key == radio } ?: return@mapNotNull null
                val identifierRow = group.firstOrNull { it.key == "$radio.identifiers" }?.value.orEmpty()
                val identifier = identifierRow.substringAfter(if (radio == "wifi") "; bssid=" else "; address=", "").substringBefore(";")
                if (identifier.isBlank()) return@mapNotNull null
                val name = group.firstOrNull { it.key == "$radio.names" }?.value?.substringAfter(if (radio == "wifi") "; ssid=" else "; name=", "").orEmpty()
                val rssi = Regex("; rssi=(-?\\d+)").find(signal.value)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                val capabilities = signal.value.substringAfter("; capabilities=", "")
                val security = if (radio == "wifi") SignalPresence.wifiSecurity(capabilities) else ""
                val beaconId = identifierRow.substringAfter("; beacon=", "").takeIf { SignalBeacon.validIdentity(it) }.orEmpty()
                val metadata = group.firstOrNull { it.key == "$radio.services" }?.value?.substringAfter("; metadata=", "").orEmpty()
                val count = Regex("; samples=(\\d+)").find(signal.value)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val median = Regex("; medianRssi=(-?\\d+)").find(signal.value)?.groupValues?.get(1)?.toIntOrNull()
                SignalRadioCandidate(radio, identifier, name, rssi, signal.measuredAt, signal.status, security, beaconId, metadata, count, median)
            }
        }
        val coverage = listOf("bluetooth", "wifi").associateWith { radio ->
            val rows = readings.filter { it.key == radio }
            when { rows.isEmpty() -> "disabled"; rows.any { it.status == "fresh" } -> "fresh"; else -> rows.first().status }
        }
        val fix = location.await()
        val now = System.currentTimeMillis()
        SignalPresenceResult(SignalPresence.observations(settings.enabled, settings.presenceTargets, settings.placeFences, candidates, coverage, fix, now), candidates)
    }

    private suspend fun locate(): SignalPresenceFix? = withContext(Dispatchers.Main) {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return@withContext null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = manager.getProviders(true).filter { it == LocationManager.NETWORK_PROVIDER || (fine && it == LocationManager.GPS_PROVIDER) }
        if (providers.isEmpty()) return@withContext null
        val result = CompletableDeferred<Location>()
        val listener = object : LocationListener { override fun onLocationChanged(location: Location) { result.complete(location) } }
        try {
            providers.forEach { manager.requestLocationUpdates(it, 1000, 0f, listener, Looper.getMainLooper()) }
            val value = withTimeoutOrNull(8000) { result.await() } ?: return@withContext null
            val age = (SystemClock.elapsedRealtimeNanos() - value.elapsedRealtimeNanos) / 1_000_000
            if (age !in 0..30_000 || !value.hasAccuracy()) return@withContext null
            SignalPresenceFix(value.latitude, value.longitude, value.accuracy.toDouble(), System.currentTimeMillis() - age)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        finally { runCatching { manager.removeUpdates(listener) } }
    }

    suspend fun locatePlace(): SignalPlaceLookup = lookupLock.withLock {
        val fix = locate() ?: throw SignalProviderException("A fresh location is unavailable. Check location permission and try outdoors.")
        val cacheKey = "${kotlin.math.round(fix.latitude * 10000) / 10000},${kotlin.math.round(fix.longitude * 10000) / 10000}"
        val elapsed = SystemClock.elapsedRealtime()
        lookupCache[cacheKey]?.takeIf { elapsed - it.first in 0..300_000 }?.let { return@withLock it.second }
        delay((1000 - (elapsed - lastLookupAt)).coerceAtLeast(0))
        lastLookupAt = SystemClock.elapsedRealtime()
        val client = HttpClient(OkHttp) { followRedirects = false; expectSuccess = false }
        try {
            val result = withTimeoutOrNull(10_000) {
                client.prepareGet("https://nominatim.openstreetmap.org/reverse") {
                    header("User-Agent", "SignalStation/1.0 (https://dr.eamer.dev/downloads/apps/signal-station/)")
                    parameter("format", "jsonv2"); parameter("lat", fix.latitude); parameter("lon", fix.longitude); parameter("zoom", 18)
                }.execute { response ->
                    if (response.status.value != 200) throw SignalProviderException("Place lookup is unavailable. Try again later.")
                    val channel = response.bodyAsChannel()
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = channel.readAvailable(buffer)
                        if (count == -1) break
                        if (output.size() + count > 64 * 1024) throw SignalProviderException("Place lookup returned too much data.")
                        output.write(buffer, 0, count)
                    }
                    val json = Json.parseToJsonElement(output.toString("UTF-8")).jsonObject
                    val name = (json["display_name"] as? JsonPrimitive)?.contentOrNull?.take(500)?.takeIf { it.isNotBlank() }
                        ?: throw SignalProviderException("No address was found for this location.")
                    SignalPlaceLookup(SignalPlace(name, fix.latitude, fix.longitude), "© OpenStreetMap contributors. Nearby address from phone location; not inferred from a Wi-Fi name. Location accuracy ${fix.accuracyMeters.toInt()} m. Confirm before saving.")
                }
            } ?: throw SignalProviderException("Place lookup timed out. No automatic retry was made.")
            if (lookupCache.size >= 32) lookupCache.remove(lookupCache.keys.first())
            lookupCache[cacheKey] = SystemClock.elapsedRealtime() to result
            result
        } catch (e: CancellationException) { throw e }
        catch (e: SignalProviderException) { throw e }
        catch (_: Exception) { throw SignalProviderException("Place lookup could not complete. Check your connection.") }
        finally { client.close() }
    }
}
