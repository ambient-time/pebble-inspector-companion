package coredevices.pebble.signal

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
data class SignalPresenceTarget(val id: String, val radio: String, val address: String, val label: String, val enabled: Boolean = true, val beaconId: String = "")
@Serializable
data class SignalPlaceFence(val id: String, val label: String, val latitude: Double, val longitude: Double, val radiusMeters: Int = 150, val wifiSsid: String = "", val enabled: Boolean = true)
data class SignalRadioCandidate(val radio: String, val address: String, val name: String, val rssi: Int, val measuredAt: Long?, val status: String, val security: String = "", val beaconId: String = "", val metadata: String = "", val sampleCount: Int = 1, val medianRssi: Int? = null)
data class SignalPresenceResult(val observations: List<SignalObservation>, val candidates: List<SignalRadioCandidate>)
data class SignalPlaceLookup(val place: SignalPlace, val description: String)
data class SignalPresenceFix(val latitude: Double, val longitude: Double, val accuracyMeters: Double, val measuredAt: Long)

object SignalPresence {
    val sources = listOf(
        SignalSource("presence.bluetooth", "Enrolled Bluetooth devices", "Presence"),
        SignalSource("presence.wifi", "Enrolled Wi-Fi access points", "Presence"),
        SignalSource("presence.places", "Saved place boundaries", "Presence"),
    )
    fun matches(target: SignalPresenceTarget, sample: SignalRadioCandidate): Boolean = target.radio == sample.radio &&
        if (target.beaconId.isNotBlank()) SignalBeacon.validIdentity(target.beaconId) && target.beaconId == sample.beaconId
        else target.address.equals(sample.address, true)
    fun fresh(sample: SignalRadioCandidate, now: Long): Boolean = sample.status == "fresh" && sample.measuredAt?.let { now - it in 0..15_000 } == true
    fun observations(enabled: Set<String>, targets: List<SignalPresenceTarget>, fences: List<SignalPlaceFence>, samples: List<SignalRadioCandidate>, coverage: Map<String, String>, fix: SignalPresenceFix?, now: Long): List<SignalObservation> = buildList {
        for (radio in listOf("bluetooth", "wifi")) {
            val key = "presence.$radio"
            if (key !in enabled) continue
            val rows = samples.filter { it.radio == radio }
            val status = coverage[radio] ?: "unavailable"
            add(SignalObservation(key, "phone", "Fresh retained radio observations=${rows.count { fresh(it, now) }}; scan=$status; strongest 64 retained at most; partial coverage, not a total device or people count", collectedAt = now, status = status))
            targets.filter { it.enabled && it.radio == radio }.take(32).forEach { target ->
                val matches = rows.filter { matches(target, it) }
                val sample = matches.maxByOrNull { it.measuredAt ?: 0 }
                val ambiguous = target.beaconId.isNotBlank() && matches.filter { fresh(it, now) }.distinctBy { it.address.lowercase() }.size > 1
                val state = when { ambiguous -> "ambiguous"; sample != null && fresh(sample, now) -> "observed"; sample != null -> "cached"; status == "fresh" -> "not_observed"; else -> "unknown" }
                add(SignalObservation(key, "phone", "${target.label.take(100)}: $state${sample?.let { "; rssi=${it.rssi} dBm${it.medianRssi?.let { median -> "; scan median=$median dBm; fresh samples=${it.sampleCount}" }.orEmpty()}" }.orEmpty()}; observation does not establish a person's presence or distance", collectedAt = now, measuredAt = sample?.measuredAt, status = state, identity = "target:${target.id}"))
            }
        }
        if ("presence.places" in enabled && fences.none { it.enabled }) add(SignalObservation("presence.places", "phone", "No enabled saved places; place coverage unknown", collectedAt = now, status = "unknown"))
        if ("presence.places" in enabled) fences.filter { it.enabled }.take(16).forEach { fence ->
            val state = fenceState(fence, fix, now)
            val wifi = if (fence.wifiSsid.isNotBlank() && "presence.wifi" in enabled) {
                val matches = samples.filter { it.radio == "wifi" && it.name == fence.wifiSsid && fresh(it, now) }.distinctBy { it.address }
                when { matches.size > 1 -> "; Wi-Fi name ambiguous"; matches.size == 1 -> "; chosen Wi-Fi name observed (name alone does not verify a place)"; else -> "; chosen Wi-Fi name not observed" }
            } else ""
            add(SignalObservation("presence.places", "phone", "${fence.label.take(100)}: $state", collectedAt = now, measuredAt = fix?.measuredAt, status = state, identity = "fence:${fence.id}"))
            if (wifi.isNotEmpty()) add(SignalObservation("presence.wifi", "phone", "${fence.label.take(100)}$wifi", collectedAt = now, status = "unknown", identity = "fence:${fence.id}:wifi"))
        }
    }
    fun wifiSecurity(capabilities: String): String {
        val value = capabilities.uppercase()
        if (listOf("WPA", "WEP", "RSN", "EAP", "SAE", "OWE", "WAPI", "DPP").any { it in value }) return "security advertised"
        val flags = Regex("\\[([^]]+)\\]").findAll(value).map { it.groupValues[1] }.toList()
        return if (flags.isNotEmpty() && flags.all { it in setOf("ESS", "WPS") } && flags.joinToString("") { "[$it]" } == value)
            "open advertised; internet and captive portal unknown" else "unknown"
    }
    fun fenceState(fence: SignalPlaceFence, fix: SignalPresenceFix?, now: Long): String {
        if (fix == null || now - fix.measuredAt !in 0..30_000 || !fix.accuracyMeters.isFinite() || fix.accuracyMeters < 0 || !fix.latitude.isFinite() || !fix.longitude.isFinite() || abs(fix.latitude) > 90 || abs(fix.longitude) > 180 || !fence.latitude.isFinite() || !fence.longitude.isFinite() || abs(fence.latitude) > 90 || abs(fence.longitude) > 180 || fence.radiusMeters !in 25..10000) return "unknown"
        val lat = (fix.latitude - fence.latitude) * PI / 180
        val lon = (fix.longitude - fence.longitude) * PI / 180
        val a = sin(lat / 2).pow(2) + cos(fix.latitude * PI / 180) * cos(fence.latitude * PI / 180) * sin(lon / 2).pow(2)
        val distance = 6371000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        return when { distance + fix.accuracyMeters <= fence.radiusMeters -> "inside"; distance - fix.accuracyMeters > fence.radiusMeters -> "outside"; else -> "boundary_uncertain" }
    }
}
