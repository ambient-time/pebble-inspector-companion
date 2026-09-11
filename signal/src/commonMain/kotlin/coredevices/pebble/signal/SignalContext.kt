package coredevices.pebble.signal

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Network disclosure is independent of source-group and preset selection. */
@Serializable
data class SignalLookupSettings(
    val nearbyPlaces: Boolean = false,
    val radioLocation: Boolean = false,
    val nearbyEndpoint: String = "https://overpass-api.de/api/interpreter",
    val addressEndpoint: String = "https://overpass-api.de/api/interpreter",
    val radioEndpoint: String = "https://api.beacondb.net/v1/geolocate",
    val radiusMeters: Int = 300,
)

data class SignalLookupReview(val kind: String, val recordId: String, val endpoint: String,
    val disclosure: String, val payload: String)

@Serializable
data class SignalLookupCache(val id: String, val record: SignalRecord, val expiresAt: Long)

object SignalContext {
    val sources = listOf(
        SignalSource("cellular", "Cellular network and signal", "Radio"),
        SignalSource("cellular.identifiers", "Cell tower identifiers", "Radio"),
        SignalSource("places.nearby", "Nearby map places", "External lookups"),
        SignalSource("location.radio", "Radio location estimate", "External lookups"),
    )
    fun fix(record: SignalRecord, now: Long): SignalPresenceFix? {
        val row = record.observations.lastOrNull { it.key == "location" && it.status in setOf("fresh", "available", "cached") } ?: return null
        val measured = row.measuredAt?.takeIf { now - it in 0..900_000 } ?: return null
        val lat = row.fields["latitude"]?.toDoubleOrNull() ?: return null
        val lon = row.fields["longitude"]?.toDoubleOrNull() ?: return null
        val accuracy = row.fields["accuracyMeters"]?.toDoubleOrNull() ?: return null
        return if (validCoordinate(lat, lon) && accuracy.isFinite() && accuracy >= 0) SignalPresenceFix(lat, lon, accuracy, measured) else null
    }
    fun validCoordinate(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    fun distance(lat: Double, lon: Double, otherLat: Double, otherLon: Double): Double {
        val a = sin((otherLat - lat) * PI / 360).pow(2) + cos(lat * PI / 180) * cos(otherLat * PI / 180) * sin((otherLon - lon) * PI / 360).pow(2)
        return 6_371_000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

object SignalWireless {
    fun band(frequency: Int): String = when (frequency) {
        in 2412..2484 -> "2.4 GHz"
        in 5000..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        in 58320..70200 -> "60 GHz"
        else -> "unknown"
    }
    fun channel(frequency: Int): Int? = when {
        frequency == 2484 -> 14
        frequency in 2412..2472 && (frequency - 2407) % 5 == 0 -> (frequency - 2407) / 5
        frequency in 5000..5895 && frequency % 5 == 0 -> (frequency - 5000) / 5
        frequency == 5935 -> 2
        frequency in 5955..7115 && (frequency - 5950) % 5 == 0 -> (frequency - 5950) / 5
        frequency in 58320..70200 && (frequency - 56160) % 2160 == 0 -> (frequency - 56160) / 2160
        else -> null
    }
    fun security(flags: String): String = when {
        flags.isBlank() || flags.length > 1024 -> "unknown"
        "OWE" in flags.uppercase() -> "OWE encryption advertised; access and internet unknown"
        else -> SignalPresence.wifiSecurity(flags)
    }
    fun summary(rows: List<SignalObservation>): List<String> {
        val signals = rows.filter { it.key == "wifi" && it.metric == "rssi" }
        if (signals.isEmpty()) return listOf("No retained access-point measurements in this record.")
        val counts = signals.groupingBy { it.fields["band"] ?: "unknown band" }.eachCount()
        val channels = signals.groupingBy { "${it.fields["band"] ?: "unknown"} channel ${it.fields["channel"] ?: "unknown"}" }.eachCount().entries.sortedByDescending { it.value }.take(6)
        return listOf("${signals.size} retained access-point observations · ${counts.entries.joinToString { "${it.key}: ${it.value}" }}",
            "Observed channel counts: ${channels.joinToString { "${it.key}: ${it.value}" }}",
            "Counts describe received advertisements, not channel utilization or people.")
    }
}
