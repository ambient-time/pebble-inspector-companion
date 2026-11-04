package com.lukesteuber.signalstation

import android.app.*
import android.app.job.JobScheduler
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.*
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import com.lukesteuber.localmodels.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit opt-in runner avoids production stores, pairing and default-provider changes. */
class LocalModelProbeRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
    override fun callApplicationOnCreate(app: Application) {
        super.callApplicationOnCreate(app)
        LocalModels.get(app)
    }
    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity {
        require(className == MainActivity::class.java.name)
        return LocalModelProbeActivity()
    }
    override fun onCreate(arguments: Bundle?) {
        val selected = arguments?.getString("class")?.takeIf { it == SignalLocalConsentTest::class.java.name }
            ?: LocalModelDeviceProbe::class.java.name
        super.onCreate(Bundle(arguments ?: Bundle()).apply { putString("class", selected) })
    }
}
class LocalModelProbeActivity : ComponentActivity() {
    var provider by mutableStateOf(LocalModelPolicy.GEMMA)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    Surface { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("On-phone answers · device check", style = MaterialTheme.typography.titleLarge)
                        LocalModelPanel(provider)
                    } }
                }
            }
        }
    }
}
class LocalModelDeviceProbe {
    @Test fun privateNativeProcessStartsAndReturnsMissingModelWithoutDownloading() = runBlocking {
        assumeTrue(instrumentation is LocalModelProbeRunner)
        val context = instrumentation.targetContext
        assumeTrue(LocalModels.get(context).installedLabel() == null)
        val result = CompletableDeferred<Bundle>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val replies = Messenger(Handler(Looper.getMainLooper()) { message -> result.complete(message.data); true })
                Messenger(binder).send(Message.obtain(null, ModelInferenceService.ANSWER, 42, 0).apply {
                    replyTo = replies; data = Bundle().apply { putString("prompt", "user:\\nSynthetic model-process check") }
                })
            }
            override fun onServiceDisconnected(name: ComponentName) {
                result.completeExceptionally(AssertionError("Native process disconnected before responding"))
            }
        }
        val bound = withContext(Dispatchers.Main) {
            context.bindService(Intent(context, ModelInferenceService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        assertTrue(bound)
        try {
            val response = withTimeout(10_000) { result.await() }
            assertTrue(response.getString("error").orEmpty().contains("Download or import"))
            assertNull(response.getString("text"))
            report("Private native process responded safely with no model installed.")
        } finally { withContext(Dispatchers.Main) { context.unbindService(connection) } }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun report(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\n$value\n") })
    private fun capture(activity: Activity, name: String) {
        instrumentation.waitForIdleSync()
        val done = CountDownLatch(1)
        lateinit var bitmap: Bitmap
        var result = PixelCopy.ERROR_UNKNOWN
        instrumentation.runOnMainSync {
            val view = activity.findViewById<android.view.View>(android.R.id.content)
            val location = IntArray(2); view.getLocationInWindow(location)
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, Rect(location[0], location[1], location[0] + view.width, location[1] + view.height),
                bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        val output = File(activity.cacheDir, "local-model-probe/$name.png").apply { parentFile!!.mkdirs() }
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        report("Screen: ${output.absolutePath}")
    }
    @Test fun checksAvailabilityAndForegroundRulesWithoutDownloadingOrChangingProviders() = runBlocking {
        assumeTrue(instrumentation is LocalModelProbeRunner)
        val context = instrumentation.targetContext
        val runtime = LocalModels.get(context)
        val before = runtime.installedLabel()
        fun downloadJobs() = context.getSystemService(JobScheduler::class.java).allPendingJobs
            .filter { it.service.className == ModelDownloadJobService::class.java.name }.map { it.id }.toSet()
        val jobs = downloadJobs()
        val service = context.packageManager.getServiceInfo(ComponentName(context, ModelInferenceService::class.java), 0)
        assertFalse(service.exported); assertTrue(service.processName.endsWith(":local_model"))
        val background = runCatching { runtime.answer(LocalModelPolicy.NANO, listOf("user" to "Reply OK.")) }.exceptionOrNull()
        assertTrue(background is LocalModelFailure && background.message.orEmpty().contains("open on screen"))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as LocalModelProbeActivity
        try {
            withTimeout(10_000) { while (!activity.hasWindowFocus()) delay(50) }
            delay(1200); capture(activity, "gemma-missing-large-font")
            instrumentation.runOnMainSync { activity.provider = LocalModelPolicy.NANO }
            val availability = withContext(Dispatchers.Main) { runtime.checkNano() }
            report("Nano availability: $availability on ${Build.MODEL}")
            if (availability == NanoAvailability.READY) {
                val answer = runCatching { withContext(Dispatchers.Main) { runtime.answer(LocalModelPolicy.NANO, listOf("user" to "Reply with the word OK.")) } }
                report(if (answer.isSuccess) "Nano generation returned ${answer.getOrThrow().length} characters." else "Nano generation not accepted: ${answer.exceptionOrNull()?.javaClass?.simpleName}")
                if (answer.isSuccess) assertTrue(answer.getOrThrow().isNotBlank())
            }
            delay(1000); capture(activity, "nano-availability-large-font")
            assertEquals(before, runtime.installedLabel())
            assertEquals(jobs, downloadJobs())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
        withTimeout(5000) { while (LocalModelPolicy.NANO in runtime.ready.value) delay(20) }
        val after = runCatching { runtime.answer(LocalModelPolicy.NANO, listOf("user" to "Reply OK.")) }.exceptionOrNull()
        assertTrue(after is LocalModelFailure && after.message.orEmpty().contains("open on screen"))
    }
}
