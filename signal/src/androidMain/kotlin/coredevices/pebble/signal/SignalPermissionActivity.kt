package coredevices.pebble.signal

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/** Requested only by the user from Signal Station settings, scoped to selected sources. */
class SignalPermissionActivity : Activity() {
    private var wakeToken = -1L
    private var startReady = false
    private var resumed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!signalPackageEnabled(packageName)) { finish(); return }
        wakeToken = intent.getLongExtra("wakeToken", -1)
        if (savedInstanceState != null) { startReady = savedInstanceState.getBoolean("startReady"); return }
        val enabled = intent.getStringArrayExtra("sources").orEmpty().toSet()
        val wanted = buildList {
            if (wakeToken >= 0) {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if ((intent.getBooleanExtra("weatherDeviceLocation", false) && enabled.any { it in SignalWeather.keys }) || enabled.any { it == "location" || it.startsWith("wifi") || it.startsWith("bluetooth") || it.startsWith("presence.") }) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION); add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (enabled.any { it.startsWith("bluetooth") || it == "presence.bluetooth" } && Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
            if (enabled.any { it == "sensor.18" || it == "sensor.19" } && Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if ("sensor.21" in enabled) { if (Build.VERSION.SDK_INT >= 36 && applicationInfo.targetSdkVersion >= 36) add("android.permission.health.READ_HEART_RATE") else add(Manifest.permission.BODY_SENSORS) }
        }.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) { if (wakeToken >= 0) startReady = true else finish() }
        else requestPermissions(wanted.toTypedArray(), 801)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (wakeToken >= 0) { startReady = true; startIfReady() } else finish()
    }
    override fun onResume() { super.onResume(); resumed = true; startIfReady() }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("startReady", startReady); super.onSaveInstanceState(outState) }
    private fun startIfReady() {
        if (!resumed || !startReady || wakeToken < 0) return
        startReady = false
        if (SignalWakeRuntime.valid(wakeToken)) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try { startForegroundService(android.content.Intent(this, SignalWakeService::class.java).putExtra("wakeToken", wakeToken)) }
                catch (_: Exception) { SignalWakeRuntime.update(wakeToken, "error", "Open Signal Station and try Start listening again.") }
            } else SignalWakeRuntime.update(wakeToken, "error", "Microphone access is off. You can still type a question.")
        }
        finish()
    }
}
