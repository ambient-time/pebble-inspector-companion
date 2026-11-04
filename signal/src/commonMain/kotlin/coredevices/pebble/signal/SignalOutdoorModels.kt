package coredevices.pebble.signal

import kotlinx.serialization.Serializable

@Serializable
data class OutdoorConfig(
    val place: SignalPlace,
    val sources: Set<String> = emptySet(),
    val tideStation: String = "",
    val earthquakeRadiusKm: Int = 100,
)

@Serializable enum class OutdoorStatus { AVAILABLE, EMPTY, PARTIAL, STALE, UNSUPPORTED, UNAVAILABLE }
@Serializable enum class OutdoorBasis { MODEL, FORECAST, CALCULATED, OFFICIAL_ALERT, PREDICTION, EVENT, REPORT }

@Serializable
data class OutdoorValue(val key: String, val label: String, val value: String? = null, val unit: String = "", val validAt: Long? = null)

@Serializable
data class OutdoorReading(
    val id: String,
    val sourceKey: String,
    val title: String,
    val provider: String,
    val attributionUrl: String,
    val basis: OutdoorBasis,
    val status: OutdoorStatus,
    val fetchedAt: Long,
    val expiresAt: Long,
    val place: String,
    val values: List<OutdoorValue> = emptyList(),
    val sourceAt: Long? = null,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val details: String = "",
    val messageExpiresAt: Long? = null,
    val queriedPlace: SignalPlace? = null,
)

object SignalOutdoorSources {
    val sources get() = SignalWeather.sources + listOf(
        SignalSource("environment.alerts", "Official weather alerts · NWS coverage", "Environment"),
        SignalSource("environment.tides", "Tide predictions · chosen NOAA station", "Environment"),
        SignalSource("environment.earthquakes", "Recent earthquakes · USGS", "Environment"),
    )
    val keys get() = sources.map { it.key }.toSet()
    fun validate(config: OutdoorConfig) {
        require(SignalWeather.validPlace(config.place) && config.place.name.length <= 300) { "Choose a valid place." }
        require(config.sources.all { it in keys }) { "Unsupported environmental source." }
        require(config.earthquakeRadiusKm in 10..500) { "Choose an earthquake radius between 10 and 500 km." }
        require(config.tideStation.isEmpty() || config.tideStation.matches(Regex("[0-9]{7}"))) { "Use a seven-digit NOAA station ID." }
    }
}
