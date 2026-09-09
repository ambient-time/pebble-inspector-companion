package coredevices.pebble.signal

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.LibVosk
import java.io.File
import java.util.zip.ZipInputStream

/** Runtime state only: process death never restarts a microphone session. */
internal object SignalWakeRuntime {
    private val session = SignalWakeSession()
    private val mutable = MutableStateFlow(session.state)
    val state = mutable.asStateFlow()
    @Synchronized fun begin(): Long = session.begin().also { mutable.value = session.state }
    @Synchronized fun update(token: Long, phase: String, status: String): Boolean = session.update(token, phase, status).also { mutable.value = session.state }
    @Synchronized fun draft(token: Long, text: String): Boolean = session.draft(token, text).also { mutable.value = session.state }
    @Synchronized fun stop() { session.stop(); mutable.value = session.state }
    @Synchronized fun dismiss() { session.dismiss(); mutable.value = session.state }
    @Synchronized fun startMicrophone(token: Long, start: () -> Unit): Boolean {
        if (!valid(token)) return false
        start(); return true
    }
    fun valid(token: Long) = state.value.token == token && state.value.phase !in setOf("stopped", "error", "draft")
}

/** Declared only by the lab manifest. No audio is written to disk or sent online. */
class SignalWakeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var token = -1L
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            if (intent.getLongExtra("wakeToken", -1) == SignalWakeRuntime.state.value.token) { SignalWakeRuntime.stop(); stopSelf() }
            return START_NOT_STICKY
        }
        val requested = intent?.getLongExtra("wakeToken", -1) ?: -1
        if (!packageName.endsWith(".inspectorlab") || !SignalWakeRuntime.valid(requested)) {
            stopSelfResult(startId); return START_NOT_STICKY
        }
        if (work?.isActive == true && token == requested) return START_NOT_STICKY
        val previous = work
        previous?.cancel()
        token = requested
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException()
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Wake listening", NotificationManager.IMPORTANCE_LOW))
            val notice = notification("Preparing local speech recognition…")
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notice)
            work = scope.launch {
                try { previous?.join(); withContext(Dispatchers.IO) { listen(requested) } }
                catch (e: CancellationException) { throw e }
                catch (_: SecurityException) { SignalWakeRuntime.update(requested, "error", "Microphone access is off. Open Android permissions to enable it.") }
                catch (_: Throwable) { SignalWakeRuntime.update(requested, "error", "Local speech recognition could not start. Stop listening and try again.") }
                finally {
                    if (SignalWakeRuntime.valid(requested)) SignalWakeRuntime.update(requested, "error", "Listening stopped. Start again when ready.")
                    if (token == requested) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId) }
                }
            }
        } catch (_: Exception) {
            SignalWakeRuntime.update(requested, "error", "Open Signal Station and start listening with microphone permission.")
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (SignalWakeRuntime.valid(token)) SignalWakeRuntime.stop()
        work?.cancel(); scope.cancel(); super.onDestroy()
    }
    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, token.toInt(), Intent(this, SignalWakeService::class.java).setAction(STOP).putExtra("wakeToken", token), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 6102, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Signal Station · go go gadget")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop listening", stop).build()
    }
    private fun announce(requested: Long, phase: String, text: String) {
        if (SignalWakeRuntime.update(requested, phase, text))
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text))
    }
    private suspend fun listen(requested: Long) {
        announce(requested, "preparing", "Preparing local speech recognition…")
        val directory = unpackModel(requested)
        currentCoroutineContext().ensureActive()
        if (!SignalWakeRuntime.valid(requested)) return
        LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
        Model(directory.absolutePath).use { model ->
            currentCoroutineContext().ensureActive()
            Recognizer(model, 16000f, "[\"go go gadget\", \"[unk]\"]").use { wake ->
                Recognizer(model, 16000f).use { question ->
                    currentCoroutineContext().ensureActive()
                    if (!SignalWakeRuntime.valid(requested)) return
                    val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    require(minimum > 0)
                    val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
                    val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SignalStation:WakeListening")
                    try {
                        check(recorder.state == AudioRecord.STATE_INITIALIZED)
                        currentCoroutineContext().ensureActive()
                        if (!SignalWakeRuntime.startMicrophone(requested) { recorder.startRecording() }) return
                        wakeLock.acquire(61 * 60 * 1000L)
                        val sessionStarted = SystemClock.elapsedRealtime()
                        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                        announce(requested, "listening", "Listening for ‘go go gadget’. Audio stays on this phone.")
                        val buffer = ShortArray(1600)
                        var recordingAt = 0L
                        while (SignalWakeRuntime.valid(requested)) {
                            currentCoroutineContext().ensureActive()
                            if (SystemClock.elapsedRealtime() - sessionStarted >= 60 * 60 * 1000L) {
                                SignalWakeRuntime.update(requested, "error", "The one-hour listening session ended. Start again when ready."); return
                            }
                            val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                            check(count >= 0)
                            if (count == 0) { delay(20); continue }
                            if (recordingAt == 0L) {
                                val final = wake.acceptWaveForm(buffer, count)
                                val text = recognized(if (final) wake.result else wake.partialResult, if (final) "text" else "partial")
                                if (SignalWakeSession.isWakePhrase(text)) {
                                    recordingAt = SystemClock.elapsedRealtime()
                                    question.reset()
                                    @Suppress("DEPRECATION") val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                                    if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                                    announce(requested, "recording", "Go ahead—say your question. Pause after speaking.")
                                }
                            } else {
                                val final = question.acceptWaveForm(buffer, count)
                                val elapsed = SystemClock.elapsedRealtime() - recordingAt
                                if (final || elapsed >= 20_000) {
                                    val text = recognized(if (final) question.result else question.finalResult, "text")
                                    if (text.isNotBlank()) {
                                        if (SignalWakeRuntime.draft(requested, text)) notifyDraft()
                                        return
                                    }
                                    if (elapsed >= 8000) {
                                        SignalWakeRuntime.update(requested, "error", "No question was recognized. Start listening to try again.")
                                        return
                                    }
                                }
                            }
                        }
                    } finally {
                        runCatching { recorder.stop() }; recorder.release()
                        if (wakeLock.isHeld) wakeLock.release()
                    }
                }
            }
        }
    }
    private fun recognized(value: String, key: String): String =
        (Json.parseToJsonElement(value).jsonObject[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun notifyDraft() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        val open = PendingIntent.getActivity(this, 6103, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { getSystemService(NotificationManager::class.java).notify(6103, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Signal Station")
            .setContentText("Your voice draft is ready to review.").setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()) }
    }

    private suspend fun unpackModel(requested: Long): File {
        val destination = File(noBackupFilesDir, "signal-wake-model-0.15")
        val marker = File(destination, "complete")
        if (marker.exists() && File(destination, "am/final.mdl").isFile && File(destination, "graph/Gr.fst").isFile) return destination
        val staging = File(noBackupFilesDir, "signal-wake-model-staging")
        staging.deleteRecursively(); check(staging.mkdirs())
        try {
            var size = 0L
            assets.open("signal-station/wake-model.zip").use { input -> ZipInputStream(input).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!SignalWakeRuntime.valid(requested)) throw CancellationException()
                    val entry = zip.nextEntry ?: break
                    require(entry.name.startsWith("vosk-model-small-en-us-0.15/"))
                    val relative = entry.name.removePrefix("vosk-model-small-en-us-0.15/")
                    if (relative.isEmpty()) continue
                    val file = File(staging, relative)
                    require(file.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                    if (entry.isDirectory) file.mkdirs() else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = zip.read(buffer)
                                if (count < 0) break
                                size += count; require(size <= 128 * 1024 * 1024)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
            } }
            currentCoroutineContext().ensureActive()
            if (!SignalWakeRuntime.valid(requested)) throw CancellationException()
            destination.deleteRecursively(); check(staging.renameTo(destination))
            marker.writeText("vosk-model-small-en-us-0.15\n")
            return destination
        } finally { staging.deleteRecursively() }
    }
    companion object {
        private const val CHANNEL = "signal-wake"
        private const val NOTIFICATION = 6100
        private const val STOP = "coredevices.pebble.signal.STOP_WAKE"
    }
}
