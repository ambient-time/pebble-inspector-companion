package com.lukesteuber.localmodels

import android.content.*
import android.os.*
import kotlinx.coroutines.*

/** Main-thread-owned Binder client. A cancelled wait also terminates native computation. */
internal class ModelClient(private val context: Context) {
    private var target: Messenger? = null
    private var connected = CompletableDeferred<Unit>()
    private var result: CompletableDeferred<Bundle>? = null
    private var sequence = 0
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            target = Messenger(binder); connected.complete(Unit)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            target = null
            result?.completeExceptionally(LocalModelFailure("The local model stopped or exceeded its time limit. Your question is preserved."))
            connected = CompletableDeferred()
        }
        override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name); unbind() }
        override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(LocalModelFailure("Local inference is unavailable.")) }
    }
    private val replies = Messenger(Handler(Looper.getMainLooper()) {
        if (it.arg1 == sequence) result?.complete(it.data)
        true
    })
    suspend fun answer(prompt: String): String = withContext(Dispatchers.Main.immediate) {
        check(result == null)
        val id = ++sequence
        try {
            if (!bound) {
                connected = CompletableDeferred()
                bound = context.bindService(Intent(context, ModelInferenceService::class.java), connection, Context.BIND_AUTO_CREATE)
            }
            if (!bound) throw LocalModelFailure("Android could not start the local model. Open the phone app and try again.")
            val service = target ?: withTimeout(5_000) { connected.await(); target }
                ?: throw LocalModelFailure("The local model process is unavailable.")
            val deferred = CompletableDeferred<Bundle>().also { result = it }
            service.send(Message.obtain(null, ModelInferenceService.ANSWER, id, 0).apply {
                data = Bundle().apply { putString("prompt", prompt) }; replyTo = replies
            })
            val reply = withTimeout(58_000) { deferred.await() }
            reply.getString("error")?.let { throw LocalModelFailure(it) }
            reply.getString("text") ?: throw LocalModelFailure("The local model returned no answer.")
        } catch (e: TimeoutCancellationException) {
            cancelNative(id)
            throw LocalModelFailure("The local model exceeded its time limit. Try a shorter question.")
        } catch (e: CancellationException) {
            cancelNative(id); throw e
        } finally { result = null; unbind() }
    }
    private fun cancelNative(id: Int) { runCatching { target?.send(Message.obtain(null, ModelInferenceService.CANCEL, id, 0)) } }
    private fun unbind() {
        if (bound) runCatching { context.unbindService(connection) }
        bound = false; target = null; connected = CompletableDeferred()
    }
}
