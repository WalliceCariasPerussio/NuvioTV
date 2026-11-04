package com.nuvio.tv.core.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class AiHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

internal data class AiHttpResponse(
    val status: Int,
    val body: String,
)

/** HTTP do assistente: um cliente próprio, sem o log de requisições do app (a chave vai no cabeçalho). */
internal object AiHttp {
    private const val MAX_BODY_BYTES = 2L * 1024 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    suspend fun get(request: AiHttpRequest): AiHttpResponse = execute(request, post = false)

    suspend fun post(request: AiHttpRequest): AiHttpResponse = execute(request, post = true)

    private suspend fun execute(request: AiHttpRequest, post: Boolean): AiHttpResponse {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        if (post) {
            builder.post(request.body.toRequestBody("application/json".toMediaType()))
        }
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val result = runCatching {
                            val source = it.body?.source()
                            source?.request(MAX_BODY_BYTES)
                            val text = source?.buffer?.let { buffer ->
                                buffer.readUtf8(minOf(buffer.size, MAX_BODY_BYTES))
                            }.orEmpty()
                            AiHttpResponse(it.code, text)
                        }
                        if (!continuation.isActive) return
                        result.fold(
                            onSuccess = { value -> continuation.resume(value) },
                            onFailure = { error -> continuation.resumeWithException(error) },
                        )
                    }
                }
            })
        }
    }
}
