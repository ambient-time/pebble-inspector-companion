package coredevices.pebble.signal

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.math.round
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Bounded foreground observations. Radio identifiers never appear in the base signal records. */
class SignalCollectors(private val context: Context) {
    private val acquisitionLock = Mutex()
    private val weather by lazy { SignalWeather(HttpClient(OkHttp)) }
    suspend fun searchWeatherPlaces(query: String) = weather.search(query)
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private fun locationEnabled(manager: LocationManager): Boolean = if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    private fun permitted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun sources(): List<SignalSource> = listOf(
        SignalSource("location", "Location", "Phone"),
        SignalSource("device.battery", "Phone battery level", "Phone"),
        SignalSource("device.charging", "Phone charging state", "Phone"),
        SignalSource("device.time", "Local time and timezone", "Phone"),
        SignalSource("device.platform", "Phone operating system", "Phone"),
        SignalSource("device.network", "Phone network status", "Phone"),
        SignalSource("wifi", "Wi-Fi signal and frequency", "Radio"),
        SignalSource("wifi.names", "Wi-Fi network names", "Radio"),
        SignalSource("wifi.identifiers", "Wi-Fi network identifiers", "Radio"),
        SignalSource("bluetooth", "Bluetooth advertisement signal strength", "Radio"),
        SignalSource("bluetooth.names", "Bluetooth advertised device names", "Radio"),
        SignalSource("bluetooth.identifiers", "Bluetooth device identifiers", "Radio"),
        SignalSource("bluetooth.services", "Bluetooth advertised services", "Radio"),
        SignalSource("watch.motion", "Watch motion", "Watch"),
        SignalSource("watch.compass", "Watch compass", "Watch"),
        SignalSource("watch.battery", "Watch battery", "Watch"),
    ) + SignalWeather.sources + SignalPresence.sources + listOf("steps", "active_seconds", "distance", "active_calories", "resting_calories", "sleep", "restful_sleep", "heart_rate", "activity")
        .map { SignalSource("health.$it", it.replace('_', ' ').replaceFirstChar(Char::uppercase), "Health") } +
        sensors.getSensorList(Sensor.TYPE_ALL).distinctBy { it.type }.map { SignalSource("sensor.${it.type}", it.name, "Sensors") }

    suspend fun collect(settings: SignalSettings, activeWifi: Boolean = true): List<SignalObservation> = acquire(settings, activeWifi).observations

    suspend fun acquire(settings: SignalSettings, activeWifi: Boolean = true): SignalAcquisition = acquisitionLock.withLock { supervisorScope {
        val enabled = SignalAcquisitionPlan.required(settings.enabled)
        val locationResult = async { if ("location" in enabled || (settings.weatherLocation == "device" && enabled.any { it in SignalWeather.keys })) locate() else LocationResult(null, "disabled") }
        val bluetooth = async { radioProbe(enabled.filter { it == "bluetooth" || it.startsWith("bluetooth.") }) { bluetooth(enabled) } }
        val wifi = async { radioProbe(enabled.filter { it == "wifi" || it.startsWith("wifi.") }) { wifi(enabled, activeWifi) } }
        val probes = listOf(
            async { probe(enabled.filter { it.startsWith("sensor.") }) { sensorReadings(enabled) } },
            async { bluetooth.await().observations },
            async { wifi.await().observations },
            async { probe(enabled.filter { it == "location" }) { locationObservations(locationResult.await()) } },
            async { environment(settings, locationResult) },
            async { probe(enabled.filter { it == "device" || it.startsWith("device.") }) { device(enabled) } },
        )
        val readings = probes.awaitAll().flatten()
        val candidates = bluetooth.await().candidates + wifi.await().candidates
        val fix = locationResult.await().fix()
        val coverage = listOf("bluetooth", "wifi").associateWith { radio ->
            val rows = readings.filter { it.key == radio }
            if (rows.any { it.status == "fresh" }) "fresh" else rows.firstOrNull()?.status ?: "disabled"
        }
        val presence = SignalPresence.observations(settings.enabled, settings.presenceTargets, settings.placeFences,
            candidates, coverage, fix, System.currentTimeMillis())
        SignalAcquisition(SignalAcquisitionPlan.visible(readings + presence, settings.enabled), candidates, fix)
    } }

    private data class RadioProbe(val observations: List<SignalObservation>, val candidates: List<SignalRadioCandidate> = emptyList())
    private suspend fun radioProbe(keys: List<String>, block: suspend () -> RadioProbe): RadioProbe {
        if (keys.isEmpty()) return RadioProbe(emptyList())
        return try { withTimeoutOrNull(9_500) { block() } ?: RadioProbe(unavailable(keys, "timeout")) }
        catch (error: CancellationException) { throw error }
        catch (_: SecurityException) { RadioProbe(unavailable(keys, "permission_denied")) }
        catch (_: Exception) { RadioProbe(unavailable(keys, "unavailable")) }
    }

    private suspend fun probe(keys: List<String>, block: suspend () -> List<SignalObservation>): List<SignalObservation> {
        if (keys.isEmpty()) return emptyList()
        return try { withTimeoutOrNull(9_500) { block() } ?: unavailable(keys, "timeout") }
        catch (error: CancellationException) { throw error }
        catch (_: SecurityException) { unavailable(keys, "permission_denied") }
        catch (_: Exception) { unavailable(keys, "unavailable") }
    }
    private fun unavailable(keys: Collection<String>, status: String) = keys.map { observation(it, status = status, measuredAt = null) }
    private fun observation(key: String, value: String = "", unit: String = "", status: String = "fresh", measuredAt: Long? = System.currentTimeMillis()) =
        SignalObservation(key, "phone", value, unit, System.currentTimeMillis(), measuredAt, status)
    private fun measured(nanos: Long): Long? = nanos.takeIf { it > 0 && it <= SystemClock.elapsedRealtimeNanos() }
        ?.let { System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - it) / 1_000_000 }

    private fun device(enabled: Set<String>): List<SignalObservation> {
        val result = mutableListOf<SignalObservation>()
        if (enabled.any { it == "device.battery" || it == "device.charging" || it == "device" }) {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if ("device.battery" in enabled) result += if (level >= 0 && scale > 0) observation("device.battery", (level * 100 / scale).toString(), "%") else observation("device.battery", status = "unavailable", measuredAt = null)
            if ("device.charging" in enabled) result += if (battery != null) observation("device.charging", (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0).toString()) else observation("device.charging", status = "unavailable", measuredAt = null)
            // Preserve interpretation of an older opt-in without enabling any new source.
            if ("device" in enabled) result += observation("device", "battery=${if (level >= 0 && scale > 0) level * 100 / scale else "unknown"}; charging=${battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: "unknown"}")
        }
        if ("device.time" in enabled) result += observation("device.time", "local=${java.time.ZonedDateTime.now()}; timezone=${ZoneId.systemDefault()}")
        if ("device.platform" in enabled) result += observation("device.platform", "Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT}")
        if ("device.network" in enabled) {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
            val transports = listOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "cellular", NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet", NetworkCapabilities.TRANSPORT_VPN to "vpn")
                .filter { capabilities?.hasTransport(it.first) == true }.map { it.second }
            result += observation("device.network", "active=${capabilities != null}; transport=${transports.joinToString()}; internetValidated=${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}; metered=${manager.isActiveNetworkMetered}")
        }
        return result
    }

    private suspend fun sensorReadings(enabled: Set<String>): List<SignalObservation> = withContext(Dispatchers.Main) {
        val values = mutableMapOf<Int, MutableList<SignalSensorSample>>()
        val selected = sensors.getSensorList(Sensor.TYPE_ALL).distinctBy { it.type }.filter { "sensor.${it.type}" in enabled }
        val registration = mutableMapOf<Int, String>()
        fun sample(type: Int, data: FloatArray, timestamp: Long, accuracy: Int) {
            val samples = values.getOrPut(type) { mutableListOf() }
            if (samples.size < 100) samples += SignalSensorSample(data.map { it.toDouble() }, measured(timestamp), accuracy)
        }
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(event: SensorEvent) = sample(event.sensor.type, event.values, event.timestamp, event.accuracy)
        }
        val trigger = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent) = sample(event.sensor.type, event.values, event.timestamp, -1)
        }
        try {
            for (sensor in selected) {
                registration[sensor.type] = try {
                    val accepted = if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) sensors.requestTriggerSensor(trigger, sensor)
                    else sensors.registerListener(listener, sensor, 200_000, Handler(Looper.getMainLooper()))
                    if (accepted) "no_samples" else "unavailable"
                } catch (_: SecurityException) { "permission_denied" }
            }
            if (registration.values.any { it == "no_samples" }) delay(5_000)
        } finally {
            sensors.unregisterListener(listener)
            selected.filter { it.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT }.forEach { runCatching { sensors.cancelTriggerSensor(trigger, it) } }
        }
        val output = selected.flatMap { sensor ->
            val samples = values[sensor.type].orEmpty()
            SignalSensorFeatures.observations("sensor.${sensor.type}", sensor.stringType, sensorUnits(sensor.type), samples,
                System.currentTimeMillis(), sensor.type == Sensor.TYPE_STEP_DETECTOR, sensor.type == Sensor.TYPE_STEP_COUNTER)
                .ifEmpty { listOf(observation("sensor.${sensor.type}", status = if (samples.isEmpty()) registration[sensor.type] ?: "unavailable" else "invalid_samples", measuredAt = null)) }
        }
        output + unavailable(enabled.filter { it.startsWith("sensor.") && it !in output.map { row -> row.key } }, "hardware_unavailable")
    }
    private fun sensorUnits(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY -> "m/s²"
        Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "rad/s"
        Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "µT"
        Sensor.TYPE_LIGHT -> "lux"
        Sensor.TYPE_PRESSURE -> "hPa"
        Sensor.TYPE_PROXIMITY -> "cm"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "°C"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
        Sensor.TYPE_HEART_RATE -> "bpm"
        Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR -> "steps"
        else -> "sensor-specific Android SensorEvent units; see sensorType"
    }

    private suspend fun bluetooth(enabled: Set<String>): RadioProbe {
        val keys = enabled.filter { it == "bluetooth" || it.startsWith("bluetooth.") }
        if (!permitted(Manifest.permission.ACCESS_FINE_LOCATION) || (Build.VERSION.SDK_INT >= 31 && (!permitted(Manifest.permission.BLUETOOTH_SCAN) || !permitted(Manifest.permission.BLUETOOTH_CONNECT)))) return RadioProbe(unavailable(keys, "permission_denied"))
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return RadioProbe(unavailable(keys, "hardware_unavailable"))
        if (!adapter.isEnabled) return RadioProbe(unavailable(keys, "radio_disabled"))
        val locations = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationEnabled(locations)) return RadioProbe(unavailable(keys, "location_services_disabled"))
        val scanner = adapter.bluetoothLeScanner ?: return RadioProbe(unavailable(keys, "unavailable"))
        data class Retained(val result: ScanResult, val metadata: SignalBeaconMetadata, val window: SignalRadioWindow, val firstSeenAt: Long?)
        fun metadata(result: ScanResult): SignalBeaconMetadata {
            val data = result.scanRecord?.manufacturerSpecificData
            val manufacturers = buildMap<Int, ByteArray> {
                if (data != null) for (index in 0 until minOf(data.size(), 16)) put(data.keyAt(index), data.valueAt(index))
            }
            return SignalBeacon.describe(manufacturers, result.scanRecord?.serviceUuids.orEmpty().take(12).map { it.toString() })
        }
        val results = ConcurrentHashMap<String, Retained>()
        val failure = AtomicInteger(0)
        val seen = ConcurrentHashMap.newKeySet<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                try {
                    val key = result.device.address
                    // Bound local memory as well as the final model attachment.
                    if (seen.size < 4096) seen.add(key)
                    synchronized(results) {
                        val previous = results[key]
                        if (previous != null && result.timestampNanos <= previous.result.timestampNanos) return
                        val advertised = metadata(result)
                        val window = previous?.takeIf { it.metadata.identity == advertised.identity }?.window ?: SignalRadioWindow()
                        window.add(result.timestampNanos / 1_000_000, result.rssi)
                        val retained = Retained(result, advertised, window, previous?.firstSeenAt ?: measured(result.timestampNanos))
                        if (previous != null || results.size < 64) results[key] = retained
                        else results.minByOrNull { it.value.result.rssi }?.let { weakest -> if (result.rssi > weakest.value.result.rssi) { results.remove(weakest.key); results[key] = retained } }
                    }
                } catch (_: SecurityException) { failure.set(-1) }
            }
            override fun onBatchScanResults(batch: MutableList<ScanResult>) { batch.forEach { onScanResult(0, it) } }
            override fun onScanFailed(code: Int) { failure.set(code) }
        }
        try { scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback); delay(5_000) }
        finally { runCatching { scanner.stopScan(callback) } }
        if (failure.get() != 0) return RadioProbe(unavailable(keys, if (failure.get() == -1) "permission_denied" else "scan_failed_${failure.get()}"))
        val ordered = synchronized(results) { results.values.sortedByDescending { it.result.rssi } }
        if (ordered.isEmpty()) return RadioProbe(keys.map { observation(it, "No advertisements observed in five seconds; this does not prove no devices are nearby.") })
        val candidates = ordered.map { retained ->
            val (count, median) = retained.window.summary(SystemClock.elapsedRealtime())
            SignalRadioCandidate("bluetooth", retained.result.device.address, retained.result.scanRecord?.deviceName.orEmpty().take(100),
                retained.result.rssi, measured(retained.result.timestampNanos), "fresh", beaconId = retained.metadata.identity,
                metadata = retained.metadata.description, sampleCount = count, medianRssi = median, firstSeenAt = retained.firstSeenAt)
        }
        val output = ordered.flatMapIndexed { index, retained ->
            val result = retained.result
            val at = measured(result.timestampNanos)
            val (count, median) = synchronized(results) { retained.window.summary(SystemClock.elapsedRealtime()) }
            buildList {
                if ("bluetooth" in enabled) add(observation("bluetooth", "observation=$index; rssi=${result.rssi}; samples=$count; medianRssi=${median ?: "unknown"}; txPower=${result.scanRecord?.txPowerLevel?.takeUnless { it == Int.MIN_VALUE } ?: "unknown"}", "dBm", measuredAt = at))
                if ("bluetooth.names" in enabled) add(observation("bluetooth.names", "observation=$index; name=${result.scanRecord?.deviceName.orEmpty().take(100)}", measuredAt = at))
                if ("bluetooth.identifiers" in enabled) add(observation("bluetooth.identifiers", "observation=$index; address=${result.device.address}${retained.metadata.identity.takeIf { it.isNotBlank() }?.let { "; beacon=$it" }.orEmpty()}", measuredAt = at))
                if ("bluetooth.services" in enabled) add(observation("bluetooth.services", "observation=$index; services=${result.scanRecord?.serviceUuids.orEmpty().take(12)}; metadata=${retained.metadata.description}", measuredAt = at))
            }
        }
        val omitted = (seen.size - ordered.size).coerceAtLeast(0)
        return RadioProbe(output + keys.map { observation(it, "scanSeconds=5; retained=${ordered.size}; omittedAtLeast=$omitted")
            .copy(metric = "coverage", fields = mapOf("retained" to ordered.size.toString(), "omitted" to omitted.toString())) }, candidates)
    }

    @Suppress("DEPRECATION")
    private suspend fun wifi(enabled: Set<String>, activeScan: Boolean): RadioProbe {
        val keys = enabled.filter { it == "wifi" || it.startsWith("wifi.") }
        if (!permitted(Manifest.permission.ACCESS_FINE_LOCATION)) return RadioProbe(unavailable(keys, "permission_denied"))
        if (!locationEnabled(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)) return RadioProbe(unavailable(keys, "location_services_disabled"))
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!manager.isWifiEnabled && !manager.isScanAlwaysAvailable) return RadioProbe(unavailable(keys, "radio_disabled"))
        val updated = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) updated.complete(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
            }
        }
        var registered = false
        try {
            // This is an Android-protected system action; no app-defined broadcast is accepted.
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            registered = true
            val started = activeScan && manager.startScan()
            val fresh = started && (withTimeoutOrNull(8_000) { updated.await() } == true)
            val all = manager.scanResults.sortedByDescending { it.level }
            val results = all.take(64)
            val scanStatus = if (fresh) "fresh" else if (!activeScan) "passive_cached" else if (!started) "scan_not_started_cached" else "scan_timeout_or_failed_cached"
            if (results.isEmpty()) return RadioProbe(keys.map { observation(it, "No access points returned; scanStatus=$scanStatus", status = "unavailable", measuredAt = null) })
            val candidates = results.map { result ->
                val at = measured(result.timestamp * 1000)
                SignalRadioCandidate("wifi", result.BSSID, result.SSID.take(100), result.level, at,
                    if (at != null && System.currentTimeMillis() - at in 0..15_000) "fresh" else "cached",
                    security = SignalPresence.wifiSecurity(result.capabilities), frequencyMHz = result.frequency, firstSeenAt = at)
            }
            val readings = results.flatMapIndexed { index, result ->
                val at = measured(result.timestamp * 1000)
                val status = if (fresh && at != null && System.currentTimeMillis() - at < 15_000) "fresh" else "cached"
                buildList {
                    if ("wifi" in enabled) add(observation("wifi", "observation=$index; rssi=${result.level}; frequency=${result.frequency}; capabilities=${result.capabilities.take(100)}", "dBm", status, at)
                        .copy(metric = "rssi", number = result.level.toDouble(), fields = mapOf("slot" to index.toString(), "frequencyMHz" to result.frequency.toString(), "security" to SignalPresence.wifiSecurity(result.capabilities))))
                    if ("wifi.names" in enabled) add(observation("wifi.names", "observation=$index; ssid=${result.SSID.take(100)}", status = status, measuredAt = at).copy(fields = mapOf("slot" to index.toString(), "ssid" to result.SSID.take(100))))
                    if ("wifi.identifiers" in enabled) add(observation("wifi.identifiers", "observation=$index; bssid=${result.BSSID}", status = status, measuredAt = at).copy(fields = mapOf("slot" to index.toString(), "bssid" to result.BSSID)))
                }
            } + keys.map { observation(it, "scanStatus=$scanStatus; retained=${results.size}; omitted=${all.size - results.size}", status = scanStatus, measuredAt = null)
                .copy(metric = "coverage", fields = mapOf("retained" to results.size.toString(), "omitted" to (all.size - results.size).toString(), "scan" to scanStatus)) }
            return RadioProbe(readings, candidates)
        } finally { if (registered) runCatching { context.unregisterReceiver(receiver) } }
    }

    private data class LocationResult(val location: Location?, val status: String, val measuredAt: Long? = null)
    private fun LocationResult.fix(): SignalPresenceFix? {
        val location = location ?: return null
        val time = measuredAt ?: return null
        if (!location.hasAccuracy()) return null
        return SignalPresenceFix(location.latitude, location.longitude, location.accuracy.toDouble(), time)
    }
    suspend fun locateFix(): SignalPresenceFix? = locate().fix()
    private fun locationObservations(result: LocationResult): List<SignalObservation> {
        val location = result.location ?: return unavailable(listOf("location"), result.status)
        return listOf(observation("location", "latitude=${location.latitude}; longitude=${location.longitude}; accuracy=${if (location.hasAccuracy()) location.accuracy else "unknown"}; provider=${location.provider}", "degrees; meters", result.status, result.measuredAt)
            .copy(fields = mapOf("latitude" to location.latitude.toString(), "longitude" to location.longitude.toString(),
                "accuracyMeters" to (if (location.hasAccuracy()) location.accuracy.toString() else "unknown"), "provider" to location.provider.orEmpty())))
    }
    private suspend fun environment(settings: SignalSettings, location: Deferred<LocationResult>): List<SignalObservation> {
        val keys = settings.enabled.intersect(SignalWeather.keys)
        if (keys.isEmpty()) return emptyList()
        var note = "chosen place"
        val place = if (settings.weatherLocation == "device") {
            val result = location.await()
            val fix = result.location ?: return SignalWeather.missing(keys, result.status)
            val at = result.measuredAt
            if (at == null || System.currentTimeMillis() - at !in 0..900_000) return SignalWeather.missing(keys, "location_stale")
            note = "phone position rounded to 0.01 degree; ${result.status}; location_time=$at; accuracy_m=${if (fix.hasAccuracy()) fix.accuracy else "unknown"}"
            SignalPlace("Near this phone", round(fix.latitude * 100) / 100, round(fix.longitude * 100) / 100)
        } else settings.weatherPlace ?: return SignalWeather.missing(keys, "choose_weather_place")
        return weather.collect(keys, place, note)
    }
    private suspend fun locate(): LocationResult = withContext(Dispatchers.Main) {
        if (!permitted(Manifest.permission.ACCESS_COARSE_LOCATION) && !permitted(Manifest.permission.ACCESS_FINE_LOCATION)) return@withContext LocationResult(null, "permission_denied")
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationEnabled(manager)) return@withContext LocationResult(null, "location_services_disabled")
        val available = manager.getProviders(true).filter { it == LocationManager.NETWORK_PROVIDER || (it == LocationManager.GPS_PROVIDER && permitted(Manifest.permission.ACCESS_FINE_LOCATION)) }
        if (available.isEmpty()) return@withContext LocationResult(null, "provider_unavailable")
        val latest = CompletableDeferred<Location>()
        val listener = object : LocationListener { override fun onLocationChanged(location: Location) { latest.complete(Location(location)) } }
        try {
            available.forEach { manager.requestLocationUpdates(it, 1000L, 0f, listener, Looper.getMainLooper()) }
            val fresh = withTimeoutOrNull(8_000) { latest.await() }
            val result = fresh ?: available.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos }
            if (result == null) LocationResult(null, "unavailable")
            else LocationResult(result, if (fresh != null) "fresh" else "cached", measured(result.elapsedRealtimeNanos) ?: result.time)
        } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) { LocationResult(null, "permission_denied") }
        catch (_: Exception) { LocationResult(null, "unavailable") }
        finally { runCatching { manager.removeUpdates(listener) } }
    }
}
