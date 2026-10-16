package com.wamp.download.weread.eink

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wamp.download.config.AppConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal enum class WeReadEinkCaptchaSolveCode {
    SOLVED,
    SIDE_CAR_UNAVAILABLE,
    SIDE_CAR_UNAUTHORIZED,
    SOLVE_FAILED,
    INVALID_RESULT,
}

internal data class WeReadEinkCaptchaTicket(
    val ticket: String,
    val randstr: String,
)

internal data class WeReadEinkCaptchaSolveResult(
    val code: WeReadEinkCaptchaSolveCode,
    val captcha: WeReadEinkCaptchaTicket? = null,
    val attempts: Int? = null,
    val durationMs: Long,
    val httpStatus: Int? = null,
    val failure: WeReadEinkSolverFailure? = null,
)

/**
 * Minimal HTTP adapter for a local `crack-tcaptcha serve` process.
 *
 * Captcha credentials are deliberately kept out of errors and logs. The caller must hand a
 * successful result directly to [WeReadEinkHumanVerification.complete].
 */
internal class WeReadEinkCaptchaSolverClient(
    baseUrl: String,
    private val appId: String,
    private val secret: String,
    private val retries: Int,
    timeoutSeconds: Long,
) {
    private val endpoint = requireNotNull((baseUrl.trimEnd('/') + "/solve").toHttpUrlOrNull()) {
        "Invalid E-Ink captcha sidecar URL"
    }.also { url ->
        require(url.scheme == "http") { "E-Ink captcha sidecar must use loopback HTTP" }
        require(url.host in LOOPBACK_HOSTS) { "E-Ink captcha sidecar must use a loopback host" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "Credentials are not allowed in sidecar URL" }
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    fun solve(entryUrl: String): WeReadEinkCaptchaSolveResult {
        val startedAt = System.nanoTime()
        var httpStatus: Int? = null
        fun result(
            code: WeReadEinkCaptchaSolveCode,
            captcha: WeReadEinkCaptchaTicket? = null,
            attempts: Int? = null,
            failure: WeReadEinkSolverFailure? = null,
        ) = WeReadEinkCaptchaSolveResult(
            code = code,
            captcha = captcha,
            attempts = attempts,
            durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
            httpStatus = httpStatus,
            failure = failure,
        )

        val payload = JsonObject().apply {
            addProperty("appid", appId)
            addProperty("retries", retries)
            addProperty("entry_url", entryUrl)
        }
        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                if (secret.isNotBlank()) header("X-SK", secret)
            }
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                httpStatus = response.code
                when {
                    response.code == 401 || response.code == 403 ->
                        result(WeReadEinkCaptchaSolveCode.SIDE_CAR_UNAUTHORIZED, failure = WeReadEinkSolverFailure.HTTP_ERROR)
                    response.code >= 500 ->
                        result(WeReadEinkCaptchaSolveCode.SIDE_CAR_UNAVAILABLE, failure = WeReadEinkSolverFailure.HTTP_ERROR)
                    !response.isSuccessful ->
                        result(WeReadEinkCaptchaSolveCode.SOLVE_FAILED, failure = WeReadEinkSolverFailure.HTTP_ERROR)
                    else -> {
                        val raw = response.body?.readBoundedUtf8()
                            ?: return@use result(WeReadEinkCaptchaSolveCode.INVALID_RESULT, failure = WeReadEinkSolverFailure.INVALID_RESPONSE)
                        val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                            ?: return@use result(WeReadEinkCaptchaSolveCode.INVALID_RESULT, failure = WeReadEinkSolverFailure.INVALID_RESPONSE)
                        val attempts = runCatching { json["attempts"]?.asInt }.getOrNull()
                        val ok = runCatching { json["ok"]?.asBoolean }.getOrNull()
                            ?: return@use result(WeReadEinkCaptchaSolveCode.INVALID_RESULT, attempts = attempts, failure = WeReadEinkSolverFailure.INVALID_RESPONSE)
                        if (!ok) return@use result(WeReadEinkCaptchaSolveCode.SOLVE_FAILED, attempts = attempts, failure = WeReadEinkSolverFailure.SOLVER_REJECTED)
                        val ticket = runCatching { json["ticket"]?.asString }.getOrNull().orEmpty()
                        val randstr = runCatching { json["randstr"]?.asString }.getOrNull().orEmpty()
                        if (!validValue(ticket, MAX_TICKET_LENGTH) || !validValue(randstr, MAX_RANDSTR_LENGTH)) {
                            return@use result(WeReadEinkCaptchaSolveCode.INVALID_RESULT, attempts = attempts, failure = WeReadEinkSolverFailure.INVALID_RESPONSE)
                        }
                        result(
                            WeReadEinkCaptchaSolveCode.SOLVED,
                            WeReadEinkCaptchaTicket(ticket, randstr),
                            attempts,
                        )
                    }
                }
            }
        } catch (_: InterruptedIOException) {
            result(WeReadEinkCaptchaSolveCode.SIDE_CAR_UNAVAILABLE, failure = WeReadEinkSolverFailure.TIMEOUT)
        } catch (_: ConnectException) {
            result(WeReadEinkCaptchaSolveCode.SIDE_CAR_UNAVAILABLE, failure = WeReadEinkSolverFailure.CONNECTION_FAILED)
        } catch (_: IOException) {
            result(WeReadEinkCaptchaSolveCode.SIDE_CAR_UNAVAILABLE, failure = WeReadEinkSolverFailure.IO_ERROR)
        } catch (_: RuntimeException) {
            result(WeReadEinkCaptchaSolveCode.INVALID_RESULT, failure = WeReadEinkSolverFailure.INVALID_RESPONSE)
        }
    }

    companion object {
        fun fromConfig(): WeReadEinkCaptchaSolverClient = WeReadEinkCaptchaSolverClient(
            baseUrl = AppConfig.wereadEinkAutoSolverUrl,
            appId = AppConfig.wereadEinkCaptchaAppId,
            secret = AppConfig.wereadEinkAutoSolverSecret,
            retries = AppConfig.wereadEinkAutoSolverRetries,
            timeoutSeconds = AppConfig.wereadEinkAutoSolverTimeoutSeconds,
        )

        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val MAX_TICKET_LENGTH = 4096
        private const val MAX_RANDSTR_LENGTH = 1024

        private fun validValue(value: String, maxLength: Int): Boolean =
            value.isNotBlank() && value.length <= maxLength

        private fun ResponseBody.readBoundedUtf8(): String? {
            if (contentLength() > MAX_RESPONSE_BYTES) return null
            return byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }
        }
    }
}
