package com.lukesteuber.localmodels

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.app.Notification
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

/**
 * Downloads the pinned Gemma model as a user-initiated data transfer job (Android 14+), so it
 * keeps going with a notification after settings closes and resumes when the network returns.
 * Model downloads are separate from answer generation and require an explicit user action.
 */
class ModelDownloadJobService : JobService() {
    @Volatile private var downloader: GemmaDownloader? = null
    @Volatile private var stopped = false
    @Volatile private var cancelledByUser = false

    override fun onStartJob(params: JobParameters): Boolean {
        val total = GemmaArtifact.MODEL.bytes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setNotification(params, NOTIFICATION_ID, notification(0, total, verifying = false), JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
        val store = GemmaModelStore(ModelDownloads.modelDirectory(this))
        // Use the network the job was given, so "unmetered only" really is honored.
        val active = GemmaDownloader(store, network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) params.network else null)
        val admission = ModelDownloads.acquire(this, active, params.extras.getString("attempt"))
        if (admission != DownloadAdmission.START) {
            android.os.Handler(mainLooper).post { jobFinished(params, admission == DownloadAdmission.WAIT) }
            return true
        }
        downloader = active
        stopped = false; cancelledByUser = false
        thread(name = "local-model-download") {
            var lastPercent = -1
            try {
                ModelDownloads.writeProgress(this, ModelDownloadProgress(ModelDownloadProgress.State.DOWNLOADING, 0, total))
                active.run(
                    freeBytes = ModelDownloads.modelDirectory(this).apply { mkdirs() }.usableSpace,
                    onProgress = { bytes ->
                        if (active.cancelled) throw CancellationException()
                        val percent = (bytes * 100 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            ModelDownloads.writeProgress(this, ModelDownloadProgress(ModelDownloadProgress.State.DOWNLOADING, bytes, total))
                            updateNotification(params, bytes, total, verifying = false)
                        }
                    },
                    onVerifying = {
                        if (active.cancelled) throw CancellationException()
                        ModelDownloads.writeProgress(this, ModelDownloadProgress(ModelDownloadProgress.State.VERIFYING, total, total))
                        updateNotification(params, total, total, verifying = true)
                    },
                )
                if (active.cancelled) throw CancellationException()
                ModelDownloads.writeProgress(this, ModelDownloadProgress(ModelDownloadProgress.State.DONE, total, total))
                LocalModels.get(this).filesChanged()
                if (!stopped) jobFinished(params, false)
            } catch (_: CancellationException) {
                // onStopJob decided whether to reschedule; nothing else to report.
            } catch (error: Throwable) {
                if (active.cancelled) return@thread
                if (ModelDownloadRules.isTransient(error)) {
                    // Dropped connection or DNS hiccup: keep the partial file and let JobScheduler retry with backoff.
                    val saved = store.partial.takeIf { it.isFile }?.length() ?: 0L
                    ModelDownloads.writeProgress(this, ModelDownloadProgress(ModelDownloadProgress.State.WAITING_FOR_NETWORK, saved, total))
                    if (!stopped) jobFinished(params, true)
                    return@thread
                }
                val message = "The model download failed validation or could not be stored. Check free space and retry."
                ModelDownloads.writeProgress(this, ModelDownloadProgress(ModelDownloadProgress.State.FAILED, 0, total, message))
                if (!stopped) jobFinished(params, false)
            } finally {
                if (active.cancelled) ModelDownloads.writeProgress(this, ModelDownloadProgress(
                    if (cancelledByUser || ModelDownloads.userCancelled) ModelDownloadProgress.State.IDLE else ModelDownloadProgress.State.WAITING_FOR_NETWORK,
                    store.partial.length(), total))
                ModelDownloads.release(active)
                LocalModels.get(this).filesChanged()
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        downloader?.cancel()
        cancelledByUser = ModelDownloads.userCancelled || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            params.stopReason in setOf(JobParameters.STOP_REASON_CANCELLED_BY_APP, JobParameters.STOP_REASON_USER)
        )
        if (cancelledByUser) {
            ModelDownloads.writeProgress(this, ModelDownloadProgress(ModelDownloadProgress.State.CANCELLING))
            return false
        }
        // Lost Wi-Fi or similar: the partial file stays, and the job resumes when allowed.
        val previous = ModelDownloads.readProgress(this)
        ModelDownloads.writeProgress(this, previous.copy(state = ModelDownloadProgress.State.WAITING_FOR_NETWORK))
        return true
    }

    private fun updateNotification(params: JobParameters, bytes: Long, total: Long, verifying: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setNotification(params, NOTIFICATION_ID, notification(bytes, total, verifying), JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
    }

    private fun notification(bytes: Long, total: Long, verifying: Boolean): Notification {
        ModelDownloads.ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, ModelDownloads.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (verifying) "Checking Gemma model" else "Downloading Gemma model")
            .setContentText("${ModelImportRules.formatBytes(bytes)} of ${ModelImportRules.formatBytes(total)}")
            .setProgress(if (verifying) 0 else 1000, (bytes * 1000 / total.coerceAtLeast(1)).toInt(), verifying)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val NOTIFICATION_ID = 7301
    }
}

/** Scheduling and shared progress for the model download. */
object ModelDownloads {
    @Volatile private var active: GemmaDownloader? = null
    @Volatile internal var userCancelled = false
        private set
    @Synchronized internal fun acquire(context: Context, worker: GemmaDownloader, attempt: String?): DownloadAdmission {
        val decision = downloadAdmission(attempt, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("attempt", null), active != null, userCancelled)
        if (decision == DownloadAdmission.START) active = worker
        return decision
    }
    @Synchronized internal fun release(worker: GemmaDownloader) { if (active === worker) active = null }
    const val CHANNEL_ID = "model_download"
    private const val JOB_ID = 7300
    private const val PREFS = "model_download"

    fun modelDirectory(context: Context) = java.io.File(context.noBackupFilesDir, "models")

    @Synchronized fun start(context: Context, wifiOnly: Boolean): Result<Unit> = runCatching {
        check(!readProgress(context).isActive) { "A model operation is still stopping." }
        userCancelled = false
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val component = ComponentName(context, ModelDownloadJobService::class.java)
        val builder = JobInfo.Builder(JOB_ID, component)
        val attempt = java.util.UUID.randomUUID().toString()
        builder.setExtras(android.os.PersistableBundle().apply { putString("attempt", attempt) })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val network = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .apply { if (wifiOnly) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
                .build()
            builder.setRequiredNetwork(network)
                .setEstimatedNetworkBytes(GemmaArtifact.MODEL.bytes, 1024)
                .setUserInitiated(true)
        } else {
            builder.setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("attempt", attempt).commit()) { "Could not record the model download." }
        writeProgress(context, ModelDownloadProgress(ModelDownloadProgress.State.WAITING_FOR_NETWORK, readProgress(context).bytes, GemmaArtifact.MODEL.bytes))
        check(scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS) { "Android wouldn't schedule the download." }
    }

    @Synchronized fun cancel(context: Context) {
        userCancelled = true
        // Invalidate queued callbacks before allowing import/removal or another download.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("attempt", java.util.UUID.randomUUID().toString()).commit()
        active?.cancel()
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        val partial = GemmaModelStore(modelDirectory(context)).partial.length()
        writeProgress(context, ModelDownloadProgress(if (active != null) ModelDownloadProgress.State.CANCELLING else ModelDownloadProgress.State.IDLE, partial, GemmaArtifact.MODEL.bytes))
    }

    fun readProgress(context: Context): ModelDownloadProgress {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = runCatching { ModelDownloadProgress.State.valueOf(prefs.getString("state", null) ?: "IDLE") }
            .getOrDefault(ModelDownloadProgress.State.IDLE)
        active?.let { return ModelDownloadProgress(if (it.cancelled) ModelDownloadProgress.State.CANCELLING else
            if (state == ModelDownloadProgress.State.VERIFYING) state else ModelDownloadProgress.State.DOWNLOADING,
            prefs.getLong("bytes", 0), GemmaArtifact.MODEL.bytes) }
        // A job that died with the process leaves a stale "active" state behind.
        val scheduled = context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null
        val effective = if ((state == ModelDownloadProgress.State.DOWNLOADING || state == ModelDownloadProgress.State.WAITING_FOR_NETWORK ||
                state == ModelDownloadProgress.State.VERIFYING || state == ModelDownloadProgress.State.CANCELLING) && !scheduled
        ) ModelDownloadProgress.State.IDLE else state
        return ModelDownloadProgress(effective, prefs.getLong("bytes", 0), prefs.getLong("total", 0), prefs.getString("message", null))
    }

    fun writeProgress(context: Context, progress: ModelDownloadProgress) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("state", progress.state.name)
            .putLong("bytes", progress.bytes)
            .putLong("total", progress.totalBytes)
            .putString("message", progress.message)
            .apply()
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model download", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while the on-device language model downloads"
                },
            )
        }
    }

}
