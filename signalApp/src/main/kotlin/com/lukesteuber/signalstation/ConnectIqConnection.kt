package com.lukesteuber.signalstation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume

/**
 * Garmin's Connect IQ companion SDK. WIRELESS reaches paired watches through Garmin
 * Connect; TETHERED reaches the Connect IQ simulator over `adb forward tcp:7381 tcp:7381`.
 */
class ConnectIqConnection(private val context: Context, private val tethered: Boolean) : GarminConnection {
    private val mutableDevices = MutableStateFlow<List<GarminDevice>>(emptyList())
    override val devices = mutableDevices.asStateFlow()
    private val mutableStatus = MutableStateFlow("Garmin Connect has not been checked yet.")
    override val status = mutableStatus.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val app = IQApp(GarminAppLink.APP_ID)
    private val known = mutableMapOf<Long, IQDevice>()
    private var sdk: ConnectIQ? = null
    private var onMessage: (Long, Any?) -> Unit = { _, _ -> }

    override fun start(onMessage: (deviceId: Long, message: Any?) -> Unit) {
        this.onMessage = onMessage
        if (!tethered && !garminConnectInstalled()) {
            mutableStatus.value = "Garmin Connect is not installed. Garmin watches are unavailable."
            return
        }
        main.post {
            val instance = ConnectIQ.getInstance(context, if (tethered) ConnectIQ.IQConnectType.TETHERED else ConnectIQ.IQConnectType.WIRELESS)
            sdk = instance
            instance.initialize(context, false, object : ConnectIQ.ConnectIQListener {
                override fun onSdkReady() {
                    refreshDevices()
                    // The simulator appears only after its adb connection is started.
                    if (tethered) main.postDelayed(object : Runnable {
                        override fun run() { if (sdk != null) { refreshDevices(); main.postDelayed(this, 5_000) } }
                    }, 5_000)
                }
                override fun onInitializeError(error: ConnectIQ.IQSdkErrorStatus) {
                    mutableStatus.value = when (error) {
                        ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED -> "Garmin Connect is not installed. Garmin watches are unavailable."
                        ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> "Update Garmin Connect to use a Garmin watch."
                        else -> "Garmin Connect is unavailable right now."
                    }
                }
                override fun onSdkShutDown() {
                    sdk = null; known.clear(); mutableDevices.value = emptyList()
                    mutableStatus.value = "Garmin Connect link stopped."
                }
            })
        }
    }

    fun refreshDevices() {
        val instance = sdk ?: return
        try {
            val paired = instance.knownDevices.orEmpty()
            for (device in paired) {
                if (known.put(device.deviceIdentifier, device) != null) continue
                instance.registerForDeviceEvents(device) { changed, status ->
                    known[changed.deviceIdentifier] = changed.also { it.status = status }
                    publish()
                }
                instance.registerForAppEvents(device, app) { from, _, messages, status ->
                    if (status == ConnectIQ.IQMessageStatus.SUCCESS) messages.forEach { onMessage(from.deviceIdentifier, it) }
                }
                device.status = instance.getDeviceStatus(device)
            }
            mutableStatus.value = if (paired.isEmpty()) "No Garmin watch is paired in Garmin Connect." else "Garmin Connect ready."
            publish()
        } catch (error: Exception) {
            Log.w("SignalGarmin", "Device refresh failed: ${error.javaClass.simpleName}")
            mutableStatus.value = "Garmin Connect is unavailable right now."
        }
    }

    private fun publish() {
        mutableDevices.value = known.values.map {
            GarminDevice(it.deviceIdentifier, it.friendlyName?.takeIf(String::isNotBlank) ?: "Garmin watch", it.status == IQDevice.IQDeviceStatus.CONNECTED)
        }.sortedBy { it.name }
    }

    override suspend fun send(deviceId: Long, message: Map<String, Any>): Boolean = withContext(Dispatchers.Main.immediate) {
        val instance = sdk ?: return@withContext false
        val device = known[deviceId] ?: return@withContext false
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { continuation ->
                try {
                    instance.sendMessage(device, app, message) { _, _, status -> if (continuation.isActive) continuation.resume(status == ConnectIQ.IQMessageStatus.SUCCESS) }
                } catch (error: Exception) {
                    Log.w("SignalGarmin", "Send failed: ${error.javaClass.simpleName}")
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        } ?: false
    }

    override suspend fun open(deviceId: Long): GarminOpenResult = withContext(Dispatchers.Main.immediate) {
        val instance = sdk ?: return@withContext GarminOpenResult.FAILED
        val device = known[deviceId] ?: return@withContext GarminOpenResult.FAILED
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { continuation ->
                try {
                    instance.openApplication(device, app) { _, _, status ->
                        if (continuation.isActive) continuation.resume(when (status) {
                            ConnectIQ.IQOpenApplicationStatus.PROMPT_SHOWN_ON_DEVICE -> GarminOpenResult.PROMPT_SHOWN
                            ConnectIQ.IQOpenApplicationStatus.APP_IS_ALREADY_RUNNING -> GarminOpenResult.ALREADY_RUNNING
                            ConnectIQ.IQOpenApplicationStatus.APP_IS_NOT_INSTALLED -> GarminOpenResult.NOT_INSTALLED
                            else -> GarminOpenResult.FAILED
                        })
                    }
                } catch (error: Exception) {
                    Log.w("SignalGarmin", "Open failed: ${error.javaClass.simpleName}")
                    if (continuation.isActive) continuation.resume(GarminOpenResult.FAILED)
                }
            }
        } ?: GarminOpenResult.FAILED
    }

    private fun garminConnectInstalled() = try {
        context.packageManager.getPackageInfo(GARMIN_CONNECT, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    companion object {
        const val GARMIN_CONNECT = "com.garmin.android.apps.connectmobile"
    }
}
