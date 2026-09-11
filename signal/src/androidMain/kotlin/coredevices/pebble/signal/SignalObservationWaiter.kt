package coredevices.pebble.signal

import android.content.Context
import android.hardware.*
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*

/** Native, low-rate transition hints use only selected sources and are removed when waiting ends. */
internal suspend fun awaitSignalTransition(context: Context, sources: Set<String>, waitMillis: Long, minimumMillis: Long): Boolean = withContext(Dispatchers.Main) {
    val changed = CompletableDeferred<Unit>()
    val started = SystemClock.elapsedRealtime()
    val sensors = context.getSystemService(SensorManager::class.java)
    val network = context.getSystemService(ConnectivityManager::class.java)
    val trigger = object : TriggerEventListener() { override fun onTrigger(event: TriggerEvent) { changed.complete(Unit) } }
    val step = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(event: SensorEvent) { changed.complete(Unit) }
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        private var first: Network? = null
        override fun onAvailable(network: Network) { if (first == null) first = network else if (first != network) changed.complete(Unit) }
        override fun onLost(network: Network) { changed.complete(Unit) }
    }
    var registeredNetwork = false
    val significant = if ("sensor.17" in sources) sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) else null
    val stepSensor = if ("sensor.18" in sources) sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) else null
    try {
        significant?.let { runCatching { sensors.requestTriggerSensor(trigger, it) } }
        stepSensor?.let { runCatching { sensors.registerListener(step, it, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper())) } }
        if ("device.network" in sources || sources.any { it.startsWith("wifi") || it == "presence.wifi" })
            registeredNetwork = runCatching { network.registerDefaultNetworkCallback(callback); true }.getOrDefault(false)
        val transition = withTimeoutOrNull(waitMillis.coerceAtLeast(1)) { changed.await(); true } ?: false
        if (transition) {
            val elapsed = SystemClock.elapsedRealtime() - started
            delay((minimumMillis - elapsed).coerceIn(0, (waitMillis - elapsed).coerceAtLeast(0)))
        }
        transition
    } finally {
        significant?.let { runCatching { sensors.cancelTriggerSensor(trigger, it) } }
        runCatching { sensors?.unregisterListener(step) }
        if (registeredNetwork) runCatching { network.unregisterNetworkCallback(callback) }
    }
}
