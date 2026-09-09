package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.*

class SignalProviderTransportTest {
    @Test fun slowChatCanSucceedAfterFastEndpointTest() = runBlocking {
        // Use the real Android transport with a local server and a synthetic key.
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            val serving = async(Dispatchers.IO) {
                repeat(2) { index ->
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: error("Request ended early")
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { input.read() }
                        if (index == 1) delay(31_000)
                        val body = """{"output":[{"type":"message","content":[{"type":"output_text","text":"Visible answer"}]}]}"""
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n" + body).toByteArray())
                    }
                }
            }
            val http = HttpClient(OkHttp) { engine { config {
                addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url("http://127.0.0.1:${server.localPort}/responses").build())
                }
            } } }
            val providers = SignalProviders(http, object : SignalSecrets {
                override suspend fun get(provider: String) = "synthetic-test-key"
                override suspend fun put(provider: String, key: String) = Unit
            })
            try {
                val settings = SignalSettings(provider = "xai", model = "test-model")
                assertEquals("Visible answer", providers.answer(settings, listOf("user" to "Reply with OK.")).text)
                assertEquals("Visible answer", providers.answer(settings, listOf("system" to "Return a concise summary, then details.", "user" to "Describe my supplied context.")).text)
            } finally { providers.close(); http.close(); serving.cancelAndJoin() }
        }
    }
}
