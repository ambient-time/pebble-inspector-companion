package com.lukesteuber.localmodels

import android.app.*
import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class NanoAvailability { UNKNOWN, READY, DOWNLOADABLE, DOWNLOADING, UNSUPPORTED, UNAVAILABLE }

/** One explicitly selected engine per request. No network-answer implementation exists here. */
class LocalModels private constructor(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Mutex()
    private val files = ModelFiles(app)
    private val client = ModelClient(app)
    private val nano by lazy { Generation.getClient() }
    private val resumed = mutableSetOf<Activity>()
    private var nanoTurn: Deferred<String>? = null
    private val mutableReady = MutableStateFlow<Set<String>>(emptySet())
    val ready: StateFlow<Set<String>> = mutableReady.asStateFlow()
    private val mutableNano = MutableStateFlow(NanoAvailability.UNKNOWN)
    val nanoAvailability: StateFlow<NanoAvailability> = mutableNano.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    private val mutableImport = MutableStateFlow<String?>(null)
    val importing: StateFlow<String?> = mutableImport.asStateFlow()
    private var importJob: Job? = null
    private var readinessRevision = 0L
    private var nanoInterested = false
    fun selectSource(provider: String) {
        if (provider == LocalModelPolicy.NANO && !nanoInterested) {
            nanoInterested = true
            scope.launch { checkNano() }
        }
    }
    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed += activity; filesChanged()
                if (nanoInterested) scope.launch { checkNano() }
            }
            override fun onActivityPaused(activity: Activity) {
                resumed -= activity
                if (resumed.isEmpty()) nanoTurn?.cancel()
                filesChanged()
            }
            override fun onActivityDestroyed(activity: Activity) { resumed -= activity; filesChanged() }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        })
        filesChanged()
    }
    fun filesChanged() {
        scope.launch {
            val revision = ++readinessRevision
            val gemma = withContext(Dispatchers.IO) { files.active() != null }
            if (revision != readinessRevision) return@launch
            mutableReady.value = buildSet {
                if (gemma && mutableImport.value == null) add(LocalModelPolicy.GEMMA)
                if (resumed.isNotEmpty() && mutableNano.value == NanoAvailability.READY) add(LocalModelPolicy.NANO)
            }
        }
    }
    suspend fun checkNano(): NanoAvailability {
        nanoInterested = true
        mutableNano.value = try {
            app.packageManager.getPackageInfo("com.google.android.aicore", 0)
            when (withTimeout(10_000) { nano.checkStatus() }) {
                FeatureStatus.AVAILABLE -> NanoAvailability.READY
                FeatureStatus.DOWNLOADABLE -> NanoAvailability.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> NanoAvailability.DOWNLOADING
                FeatureStatus.UNAVAILABLE -> NanoAvailability.UNSUPPORTED
                else -> NanoAvailability.UNAVAILABLE
            }
        } catch (_: TimeoutCancellationException) { NanoAvailability.UNAVAILABLE }
        catch (e: CancellationException) { throw e }
        catch (_: android.content.pm.PackageManager.NameNotFoundException) { NanoAvailability.UNSUPPORTED }
        catch (_: Throwable) { NanoAvailability.UNAVAILABLE }
        filesChanged()
        return mutableNano.value
    }
    suspend fun downloadNano() {
        if (resumed.isEmpty()) throw LocalModelFailure("Keep this app open to download the system model.")
        if (checkNano() != NanoAvailability.DOWNLOADABLE) throw LocalModelFailure("Android is not offering a system-model download here.")
        mutableNano.value = NanoAvailability.DOWNLOADING
        try { nano.download().collect { } }
        finally { checkNano() }
    }
    suspend fun answer(provider: String, messages: List<Pair<String, String>>): String = gate.withLock {
        LocalModelPolicy.validate(messages)
        if (mutableImport.value != null) throw LocalModelFailure("Wait for the model import to finish.")
        mutableBusy.value = true
        try {
            val text = when (provider) {
                LocalModelPolicy.GEMMA -> {
                    if (withContext(Dispatchers.IO) { files.active() } == null)
                        throw LocalModelFailure("Download or import Gemma in Answers settings first.")
                    client.answer(LocalModelPolicy.prompt(messages))
                }
                LocalModelPolicy.NANO -> withContext(Dispatchers.Main.immediate) {
                    if (resumed.isEmpty()) throw LocalModelFailure("Gemini Nano needs this phone app open on screen. Nothing was sent to a cloud provider.")
                    coroutineScope {
                        val task = async(start = CoroutineStart.LAZY) {
                            if (checkNano() != NanoAvailability.READY) throw LocalModelFailure("Gemini Nano is not ready on this phone. Check On-device setup in Answers.")
                            val request = GenerateContentRequest.builder(TextPart(LocalModelPolicy.prompt(messages))).apply {
                                maxOutputTokens = 512; temperature = 0.3f
                            }.build()
                            if (nano.countTokens(request).totalTokens + 512 > nano.getTokenLimit())
                                throw LocalModelFailure("This question exceeds Nano's context limit. Choose fewer readings or start a new conversation.")
                            currentCoroutineContext().ensureActive()
                            if (resumed.isEmpty()) throw LocalModelFailure("Keep the phone app on screen to use Gemini Nano.")
                            nano.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
                        }
                        nanoTurn = task
                        try { withTimeout(55_000) { task.await() } }
                        catch (_: TimeoutCancellationException) { throw LocalModelFailure("Gemini Nano timed out. No automatic retry was made.") }
                        catch (e: CancellationException) {
                            if (currentCoroutineContext().isActive && resumed.isEmpty()) throw LocalModelFailure("Gemini Nano stopped because the phone app left the screen. Your question is preserved.")
                            throw e
                        } finally { task.cancel(); nanoTurn = null }
                    }
                }
                else -> throw LocalModelFailure("Unknown on-device provider.")
            }
            if (text.isBlank()) throw LocalModelFailure("The local model returned no answer. Try a shorter question.")
            if (text.encodeToByteArray().size > LocalModelPolicy.MAX_OUTPUT_BYTES) throw LocalModelFailure("The local reply exceeded its safe size limit.")
            text.trim()
        } catch (e: CancellationException) { throw e }
        catch (e: LocalModelFailure) { throw e }
        catch (_: Throwable) { throw LocalModelFailure("Local inference is unavailable. Check On-device setup; no cloud fallback was used.") }
        finally { mutableBusy.value = false }
    }
    suspend fun downloadGemma(unmetered: Boolean) = gate.withLock { withContext(Dispatchers.IO) {
        if (mutableImport.value != null || downloadProgress().isActive) throw LocalModelFailure("Wait for the current model operation to finish.")
        files.checkSize(GemmaArtifact.MODEL.fileName, GemmaArtifact.MODEL.bytes, files.downloaded.partial.length())
        ModelDownloads.start(app, unmetered).getOrElse { throw LocalModelFailure("Android could not schedule the model download. Keep this app open and try again.") }
    } }
    fun downloadProgress() = ModelDownloads.readProgress(app)
    fun cancelDownload() { ModelDownloads.cancel(app) }
    suspend fun installedLabel(): String? = withContext(Dispatchers.IO) { files.label() }
    fun importModel(uri: Uri, complete: (String) -> Unit) {
        if (importJob?.isActive == true || mutableBusy.value || downloadProgress().isActive) {
            complete("Finish or cancel the current model operation first."); return
        }
        importJob = scope.launch {
            mutableImport.value = "Checking model file…"; filesChanged()
            try {
                gate.withLock { withContext(Dispatchers.IO) {
                    if (downloadProgress().isActive) throw LocalModelFailure("Wait for the model download to stop before importing.")
                    files.import(uri) {
                    mutableImport.value = "Importing ${ModelImportRules.formatBytes(it)}"
                } } }
                complete("Imported model stored on this phone. Run Check saved setup to test it.")
            } catch (e: CancellationException) { complete("Import cancelled. The previous model is kept."); throw e }
            catch (e: Exception) { complete((e as? LocalModelFailure)?.message ?: "Import failed. The previous model is kept.") }
            finally { mutableImport.value = null; filesChanged() }
        }
    }
    fun cancelImport() { importJob?.cancel() }
    suspend fun removeModel() {
        if (mutableBusy.value || mutableImport.value != null || downloadProgress().isActive)
            throw LocalModelFailure("Finish or cancel the current model operation first.")
        gate.withLock { withContext(Dispatchers.IO) {
            if (downloadProgress().isActive || mutableImport.value != null) throw LocalModelFailure("Wait for the current model operation to finish.")
            files.remove()
            ModelDownloads.writeProgress(app, ModelDownloadProgress(ModelDownloadProgress.State.IDLE))
        } }
        filesChanged()
    }
    companion object {
        @Volatile private var instance: LocalModels? = null
        fun get(context: Context): LocalModels = instance ?: synchronized(this) {
            instance ?: LocalModels(context.applicationContext as Application).also { instance = it }
        }
        fun isModelProcess(context: Context): Boolean =
            context.getSystemService(ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName?.endsWith(":local_model") == true
    }
}
