package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.js.InterceptResponse.Companion.ERROR
import kotlin.uuid.Uuid

data class HttpCallerContext(val runner: JsRunner)

interface HttpInterceptor {
    fun shouldIntercept(url: String): Boolean
    suspend fun onIntercepted(url: String, method: String, body: String?, appUuid: Uuid): InterceptResponse
    suspend fun onIntercepted(url: String, method: String, body: String?, appUuid: Uuid, caller: HttpCallerContext): InterceptResponse = onIntercepted(url, method, body, appUuid)
}

data class InterceptResponse(
    val result: String,
    val status: Int,
) {
    companion object {
        val OK = InterceptResponse(result = "", status = 200)
        val ERROR = InterceptResponse(result = "", status = 500)
    }
}

class InjectedPKJSHttpInterceptors(
    val interceptors: List<HttpInterceptor>,
)

class HttpInterceptorManager(
    timeline: RemoteTimelineEmulator,
    injectedHttpInterceptors: InjectedPKJSHttpInterceptors,
) {
    private val interceptors: List<HttpInterceptor> = listOf(
        timeline,
    ) + injectedHttpInterceptors.interceptors

    fun shouldIntercept(url: String): Boolean {
        return interceptors.any { it.shouldIntercept(url) }
    }

    suspend fun onIntercepted(url: String, method: String, body: String?, appUuid: Uuid, caller: HttpCallerContext? = null): InterceptResponse {
        val interceptor = interceptors.firstOrNull { it.shouldIntercept(url) }
        if (interceptor == null) {
            return ERROR
        }
        return if (caller == null) interceptor.onIntercepted(url, method, body, appUuid)
        else interceptor.onIntercepted(url, method, body, appUuid, caller)
    }
}