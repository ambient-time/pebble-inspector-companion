package coredevices.pebble.signal

import android.content.Context
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

class SignalPresenceCollector(private val context: Context, private val collectors: SignalCollectors = SignalCollectors(context)) {
    private val lookupLock = Mutex()
    private var lastLookupAt = -1000L
    private val lookupCache = linkedMapOf<String, Pair<Long, SignalPlaceLookup>>()
    suspend fun collect(settings: SignalSettings): SignalPresenceResult {
        val acquisition = collectors.acquire(settings.copy(enabled = settings.enabled.filter { it.startsWith("presence.") }.toSet()))
        return SignalPresenceResult(acquisition.observations, acquisition.candidates)
    }

    suspend fun locatePlace(): SignalPlaceLookup = lookupLock.withLock {
        val fix = collectors.locateFix()?.takeIf { System.currentTimeMillis() - it.measuredAt in 0..30_000 } ?: throw SignalProviderException("A fresh location is unavailable. Check location permission and try outdoors.")
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
