package coredevices.pebble.signal

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/** Requested only by the user from Signal Station settings, scoped to selected sources. */
class SignalPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!packageName.endsWith(".inspectorlab")) { finish(); return }
        if (savedInstanceState != null) return
        val enabled = intent.getStringArrayExtra("sources").orEmpty().toSet()
        val wanted = buildList {
            if (enabled.any { it == "location" || it.startsWith("wifi") || it.startsWith("bluetooth") }) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION); add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (enabled.any { it.startsWith("bluetooth") } && Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
            if (enabled.any { it == "sensor.18" || it == "sensor.19" } && Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if ("sensor.21" in enabled) { if (Build.VERSION.SDK_INT >= 36 && applicationInfo.targetSdkVersion >= 36) add("android.permission.health.READ_HEART_RATE") else add(Manifest.permission.BODY_SENSORS) }
        }.distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) finish() else requestPermissions(wanted.toTypedArray(), 801)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); finish()
    }
}
