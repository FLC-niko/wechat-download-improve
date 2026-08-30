package com.wamp.download.weread.eink

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

data class WeReadEinkRequest(
    val method: String,
    val url: HttpUrl,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class WeReadEinkResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

fun interface WeReadEinkTransport {
    fun execute(request: WeReadEinkRequest): WeReadEinkResponse
}

class OkHttpWeReadEinkTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) : WeReadEinkTransport {
    override fun execute(request: WeReadEinkRequest): WeReadEinkResponse {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val body = request.body?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val httpRequest = builder.method(request.method, body).build()

        client.newCall(httpRequest).execute().use { response ->
            return WeReadEinkResponse(
                statusCode = response.code,
                body = response.body?.string().orEmpty(),
                headers = response.headers.toMultimap().mapValues { (_, values) -> values.joinToString(",") },
            )
        }
    }
}
