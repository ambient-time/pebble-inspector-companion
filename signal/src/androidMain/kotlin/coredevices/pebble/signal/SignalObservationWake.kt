package coredevices.pebble.signal

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps a recording on schedule with the screen off. A coroutine delay counts uptime, which stops
 * while the CPU sleeps, so on its own a one-minute wait could last until something else woke the phone.
 *
 * Between samples the CPU may sleep. An inexact allow-while-idle alarm wakes it at half the remaining
 * time, which Android delivers before the sample is due (see [SignalSchedule.wakeStep]). The last two
 * minutes before a sample, and the sample itself, run under a partial wake lock. Android honors a
 * foreground service's partial wake lock in Doze; no exact-alarm permission is needed.
 */
internal class SignalObservationWake(private val context: Context) {
    private val action = "${context.packageName}.OBSERVATION_WAKE"
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SignalStation:recording").apply { setReferenceCounted(false) }
    private val alarm = PendingIntent.getBroadcast(context, 6112, Intent(action).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private var fired: CompletableDeferred<Unit>? = null
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        // Android holds the CPU only while this runs; take our own lock before the coroutine resumes.
        override fun onReceive(context: Context, intent: Intent) { hold(ALARM_HOLD_MILLIS); fired?.complete(Unit) }
    }

    fun start() {
        if (registered) return
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, IntentFilter(action))
        registered = true
    }

    /** Keeps the CPU awake for at most [millis]; a later call replaces the timeout. */
    fun hold(millis: Long) { runCatching { lock.acquire(millis.coerceAtLeast(1_000)) } }

    fun release() { if (lock.isHeld) runCatching { lock.release() } }

    /** Returns at or soon after [target] on the elapsed-realtime clock, which keeps counting during sleep. */
    suspend fun sleepUntil(target: Long) {
        while (true) {
            val remaining = target - SystemClock.elapsedRealtime()
            if (remaining <= 0) return
            val step = SignalSchedule.wakeStep(remaining)
            if (step.holdAwake) hold(remaining + ALARM_HOLD_MILLIS) else release()
            val signal = CompletableDeferred<Unit>()
            fired = signal
            // Also set while holding awake: a backup if a phone maker's build drops the wake lock.
            runCatching { alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + step.alarmIn, alarm) }
            try { withTimeoutOrNull(step.alarmIn) { signal.await() } }
            finally { fired = null; runCatching { alarms.cancel(alarm) } }
        }
    }

    fun stop() {
        fired = null
        runCatching { alarms.cancel(alarm) }
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        release()
    }

    companion object {
        const val ALARM_HOLD_MILLIS = 30_000L
        /** Collection may take 90 seconds and scheduled analysis 65 more. */
        const val SAMPLE_HOLD_MILLIS = 90_000L + 65_000L + ALARM_HOLD_MILLIS
    }
}
