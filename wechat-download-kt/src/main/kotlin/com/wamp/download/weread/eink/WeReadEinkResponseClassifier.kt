package com.wamp.download.weread.eink

import com.google.gson.JsonParser

enum class WeReadEinkResponseKind {
    SUCCESS,
    AUTH_EXPIRED,
    RATE_LIMITED,
    HUMAN_VERIFY,
    RETRYABLE_HTTP_ERROR,
    APPLICATION_ERROR,
    HTTP_ERROR,
}

internal data class WeReadEinkResponseClassification(
    val kind: WeReadEinkResponseKind,
    val errCode: Int? = null,
    val errMsg: String? = null,
)

internal object WeReadEinkResponseClassifier {
    fun classify(response: WeReadEinkResponse): WeReadEinkResponseClassification {
        val error = parseError(response.body)
        val kind = when {
            response.statusCode == 401 || error.errCode == -2012 -> WeReadEinkResponseKind.AUTH_EXPIRED
            error.errCode == -2041 -> WeReadEinkResponseKind.HUMAN_VERIFY
            error.errCode == -2014 -> WeReadEinkResponseKind.RATE_LIMITED
            response.statusCode == 429 -> WeReadEinkResponseKind.RATE_LIMITED
            response.statusCode in 500..599 -> WeReadEinkResponseKind.RETRYABLE_HTTP_ERROR
            response.statusCode !in 200..299 -> WeReadEinkResponseKind.HTTP_ERROR
            error.kind == WeReadEinkResponseKind.RETRYABLE_HTTP_ERROR -> WeReadEinkResponseKind.RETRYABLE_HTTP_ERROR
            error.errCode != null && error.errCode != 0 -> WeReadEinkResponseKind.APPLICATION_ERROR
            else -> WeReadEinkResponseKind.SUCCESS
        }
        return error.copy(kind = kind)
    }

    private fun parseError(raw: String): WeReadEinkResponseClassification {
        // A gateway/WAF can return HTTP 200 with an HTML page, an empty body, or an array.
        // None of those is a valid E-Ink API response; keep it retryable instead of allowing
        // the caller to interpret it as an authentication failure or an empty article page.
        if (raw.isBlank()) return WeReadEinkResponseClassification(WeReadEinkResponseKind.RETRYABLE_HTTP_ERROR)
        return try {
            val root = JsonParser.parseString(raw)
            if (!root.isJsonObject) {
                WeReadEinkResponseClassification(WeReadEinkResponseKind.RETRYABLE_HTTP_ERROR)
            } else {
                val obj = root.asJsonObject
                val codeElement = obj.get("errCode") ?: obj.get("errcode")
                val errCode = codeElement?.let { runCatching { it.asInt }.getOrNull() }
                val errMsg = obj.get("errMsg")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: obj.get("errmsg")?.takeIf { it.isJsonPrimitive }?.asString
                WeReadEinkResponseClassification(
                    kind = WeReadEinkResponseKind.SUCCESS,
                    errCode = errCode,
                    errMsg = errMsg,
                )
            }
        } catch (_: Exception) {
            WeReadEinkResponseClassification(WeReadEinkResponseKind.RETRYABLE_HTTP_ERROR)
        }
    }
}
