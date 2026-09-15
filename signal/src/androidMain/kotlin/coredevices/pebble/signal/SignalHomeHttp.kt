package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/** Dedicated Home transport. Never share a client with permissive TLS or automatic recovery. */
fun createSignalHomeHttpClient():HttpClient = HttpClient(OkHttp) {
    followRedirects=false
    engine { config { followRedirects(false);followSslRedirects(false);retryOnConnectionFailure(false) } }
}
