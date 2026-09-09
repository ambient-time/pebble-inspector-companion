package com.lukesteuber.signalstation

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import android.os.Handler
import android.os.Looper
import kotlin.coroutines.resume
import coredevices.pebble.signal.*
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID

@SuppressLint("SetJavaScriptEnabled")
internal class LocalProtocolSession(
    context: Context,
    override val watchId: String,
    override val connectionId: String,
    parent: CoroutineScope,
    private val sender: PebbleSender,
    private val request: suspend (String, String, String?, SignalWatchSession) -> SignalWatchResponse,
) : SignalWatchSession {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val loaded = CompletableDeferred<Unit>()
    private val web = WebView(context)
    private val assets = context.assets
    private var alive = true
    override var ready = false
        private set
    private val keys = Json.parseToJsonElement(assets.open("signal-station/message-keys.json").bufferedReader().use { it.readText() }).jsonObject.mapValues { it.value.jsonPrimitive.int }
    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        scope.coroutineContext[Job]?.invokeOnCompletion { Handler(Looper.getMainLooper()).post { close() } }
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.blockNetworkLoads = true
        web.addJavascriptInterface(Host(), "SignalHost")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!alive || ready) return
                val script = "var module={exports:{}};\n" + assets.open("signal-station/protocol.js").bufferedReader().use { it.readText() } + "\n" + assets.open("signal-station/adapter.js").bufferedReader().use { it.readText() }
                web.evaluateJavascript(script + "\ntrue;") { result ->
                    if (alive && result == "true") {
                        ready = true; loaded.complete(Unit)
                        web.evaluateJavascript("startProtocol(); true", null)
                    } else if (alive) loaded.completeExceptionally(IllegalStateException("Protocol unavailable"))
                }
            }
        }
        web.loadDataWithBaseURL("https://signal.invalid/", "<!doctype html><html></html>", "text/html", "UTF-8", null)
    }
    fun close() {
        if (!alive) return
        alive = false; ready = false; scope.cancel(); loaded.cancel()
        web.removeJavascriptInterface("SignalHost"); web.destroy()
    }
    private suspend fun evaluate(script: String): Boolean {
        if (!alive) return false
        return withTimeoutOrNull(3000) {
            loaded.await()
            suspendCancellableCoroutine { continuation ->
                web.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(alive && result == "true") }
            }
        } ?: false
    }
    override suspend fun sendConfigMessage(message: String) {
        val value = Json.parseToJsonElement(message).jsonObject
        if (!evaluate("configureWatch($value); true")) throw SignalProviderException("Watch connection closed.")
    }
    suspend fun receive(data: PebbleDictionary): Boolean {
        val packet = buildJsonObject {
            data.forEach { (id, item) ->
                val name = keys.entries.firstOrNull { it.value.toUInt() == id }?.key ?: return@forEach
                val primitive = when (item) {
                    is PebbleDictionaryItem.Text -> JsonPrimitive(item.value)
                    is PebbleDictionaryItem.Bytes -> return false
                    else -> item.value.toString().toLongOrNull()?.let(::JsonPrimitive) ?: return false
                }
                put(name, primitive)
            }
        }
        return evaluate("receiveWatch($packet); true")
    }
    private fun reply(id: Int, ok: Boolean, value: JsonElement = JsonNull) {
        if (alive) web.evaluateJavascript("nativeReply($id,$ok,$value)", null)
    }
    private inner class Host {
        @JavascriptInterface fun request(id: Int, method: String, url: String, body: String) {
            if (url.length > 512 || body.length > 32768 || id <= 0) return
            scope.launch {
                try {
                    val response = request(url, method, body, this@LocalProtocolSession)
                    reply(id, response.status in 200..299, Json.parseToJsonElement(response.result))
                } catch (e: CancellationException) { throw e } catch (_: Exception) { reply(id, false) }
            }
        }
        @JavascriptInterface fun send(id: Int, packet: String) {
            if (packet.length > 4096 || id <= 0) return
            scope.launch {
                try {
                    val dictionary = Json.parseToJsonElement(packet).jsonObject.map { (key, value) ->
                        val wireKey = keys[key] ?: error("Unknown message key")
                        val item = value.jsonPrimitive
                        wireKey.toUInt() to if (item.isString) PebbleDictionaryItem.Text(item.content) else PebbleDictionaryItem.Int32(item.int)
                    }.toMap()
                    val watch = WatchIdentifier(watchId)
                    val result = withTimeoutOrNull(10000) { sender.sendDataToPebble(UUID.fromString(AndroidSignalStation.APP_UUID), dictionary, listOf(watch)) }
                    reply(id, result?.get(watch) == TransmissionResult.Success)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { reply(id, false) }
            }
        }
    }
}
