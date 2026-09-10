package coredevices.pebble.signal

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import kotlinx.coroutines.*

interface SignalObservationHost { val station: AndroidSignalStation }

/** User-started finite session. No restart, boot receiver, microphone, camera, or provider request. */
class SignalObservationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var sessionId: String? = null
    private val station get() = (application as? SignalObservationHost)?.station
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { station?.stopObservation(); stopSelf(); return START_NOT_STICKY }
        val owner = station ?: run { stopSelf(); return START_NOT_STICKY }
        val id = intent?.getStringExtra("session") ?: run { stopSelf(); return START_NOT_STICKY }
        val pending = owner.pendingObservation(id) ?: run { stopSelf(); return START_NOT_STICKY }
        if (job?.isActive == true) return START_NOT_STICKY
        sessionId = id
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("signal-observation", "Observation sessions", NotificationManager.IMPORTANCE_LOW))
        try {
            val notification = notification("Starting selected observations…")
            if (Build.VERSION.SDK_INT >= 29) {
                var types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
                if (pending.sourceKeys.any { it == "location" || it.startsWith("presence.") || it.startsWith("wifi") || it.startsWith("weather.") } && (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION))) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (pending.sourceKeys.any { it.startsWith("bluetooth") || it == "presence.bluetooth" } && (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_SCAN))) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (Build.VERSION.SDK_INT >= 34 && "sensor.21" in pending.sourceKeys && (granted(Manifest.permission.BODY_SENSORS) || granted("android.permission.health.READ_HEART_RATE"))) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                startForeground(6110, notification, types)
            } else startForeground(6110, notification)
        } catch (_: Exception) { owner.finishObservation(id, "interrupted", "Android did not allow the session to start. Review collection permissions and try again."); stopSelf(); return START_NOT_STICKY }
        job = serviceScope.launch {
            val deadline = SystemClock.elapsedRealtime() + (pending.endsAt - pending.startedAt)
            try {
                owner.activateObservation(id)
                while (isActive && SystemClock.elapsedRealtime() < deadline && System.currentTimeMillis() < pending.endsAt) {
                    val battery = getSystemService(BatteryManager::class.java)
                    val power = getSystemService(PowerManager::class.java)
                    val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val pause = when {
                        !notificationsEnabled() -> "Notifications are off; session stopped."
                        level in 0..14 && !battery.isCharging -> "Paused: battery below 15%."
                        filesDir.usableSpace < 250L * 1024 * 1024 -> "Paused: storage is low."
                        Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> "Paused: phone is too warm."
                        else -> null
                    }
                    if (!notificationsEnabled()) { owner.finishObservation(id, "stopped", pause!!); break }
                    if (pause != null) owner.observationStatus(id, "paused", pause) else {
                        owner.observationStatus(id, "running", "Collecting selected sources. Android may delay individual readings.")
                        withTimeoutOrNull(minOf(90_000L, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1), (pending.endsAt - System.currentTimeMillis()).coerceAtLeast(1))) { owner.collectObservation(id) }
                    }
                    notifications.notify(6110, notification(pause ?: "Observing until ${java.time.Instant.ofEpochMilli(pending.endsAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)}. Tap Stop to end."))
                    delay(minOf(5 * 60_000L, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)))
                }
                owner.finishObservation(id, "completed", "Observation session ended.")
            } finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun notificationsEnabled() = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 6110, Intent(this, SignalObservationService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 6111, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        return Notification.Builder(this, "signal-observation").setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("Signal Station is observing").setContentText(text).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    override fun onDestroy() { job?.cancel(); serviceScope.cancel(); sessionId?.let { station?.finishObservation(it, "interrupted", "Android stopped the session. It will not restart automatically.") }; super.onDestroy() }
}
