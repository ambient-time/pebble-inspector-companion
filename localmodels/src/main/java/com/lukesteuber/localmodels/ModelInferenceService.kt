package com.lukesteuber.localmodels

import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Message
import com.google.ai.edge.litertlm.*

/** Native work lives in this app's private process; only one bounded turn is accepted. */
class ModelInferenceService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private var active = 0
    private var engine: Engine? = null
    private var identity: String? = null
    private val kill = Runnable { Process.killProcess(Process.myPid()) }
    private val inbox by lazy { Messenger(Handler(Looper.getMainLooper(), ::receive)) }
    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("local-answer").apply { start() }
        worker = Handler(thread.looper)
    }
    override fun onBind(intent: Intent): IBinder = inbox.binder
    private fun receive(message: Message): Boolean {
        if (message.what == CANCEL) {
            if (active == message.arg1 || message.arg1 == 0) kill.run()
            return true
        }
        if (message.what != ANSWER) return true
        val target = message.replyTo ?: return true
        val id = message.arg1
        if (active != 0) { reply(target, id, error = "The local model is busy. Wait or cancel the current question."); return true }
        val prompt = message.data.getString("prompt").orEmpty()
        if (prompt.isBlank() || prompt.encodeToByteArray().size > LocalModelPolicy.MAX_INPUT_BYTES) {
            reply(target, id, error = "Local context is too large. Choose fewer readings."); return true
        }
        active = id
        main.removeCallbacks(kill)
        // Covers native initialization AND generation; cancellation is handled on the main thread.
        main.postDelayed(kill, 55_000)
        worker.post {
            try {
                val file = ModelFiles(this).active() ?: throw LocalModelFailure("Download or import Gemma in Answers settings first.")
                val signature = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
                if (signature != identity) {
                    runCatching { engine?.close() }; engine = null; identity = null
                    for (backend in listOf(Backend.GPU(), Backend.CPU())) {
                        val candidate = Engine(EngineConfig(modelPath = file.absolutePath, backend = backend,
                            maxNumTokens = 8192, cacheDir = cacheDir.absolutePath))
                        try { candidate.initialize(); engine = candidate; identity = signature; break }
                        catch (_: Throwable) { runCatching { candidate.close() } }
                    }
                }
                val current = engine ?: throw LocalModelFailure("This model could not load on this phone. Use the portable Gemma download.")
                val answer = current.createConversation(ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3),
                    maxOutputToken = 512,
                )).use { it.sendMessage(prompt).toString().trim() }
                if (answer.isBlank()) throw LocalModelFailure("The local model returned no text. Try a shorter question.")
                if (answer.encodeToByteArray().size > LocalModelPolicy.MAX_OUTPUT_BYTES)
                    throw LocalModelFailure("The local reply exceeded its safe limit. Try a shorter question.")
                reply(target, id, text = answer)
            } catch (e: Throwable) {
                reply(target, id, error = (e as? LocalModelFailure)?.message ?: "Local inference failed. Check the model in Answers settings.")
            } finally {
                main.post {
                    active = 0; main.removeCallbacks(kill)
                    // Release memory when idle; a later turn can bind and load again.
                    main.postDelayed(kill, 180_000)
                }
            }
        }
        return true
    }
    private fun reply(target: Messenger, id: Int, text: String? = null, error: String? = null) {
        runCatching { target.send(Message.obtain(null, ANSWER, id, 0).apply {
            data = Bundle().apply { putString("text", text); putString("error", error) }
        }) }
    }
    override fun onDestroy() {
        // A cancelled/unbound native call must not outlive its client or lose its watchdog.
        if (active != 0) { kill.run(); return }
        main.removeCallbacks(kill)
        worker.post { runCatching { engine?.close() } }
        thread.quitSafely()
        super.onDestroy()
    }
    companion object { const val ANSWER = 1; const val CANCEL = 2 }
}
