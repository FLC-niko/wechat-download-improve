package com.wamp.download.weread.eink

import com.google.gson.JsonParser

enum class WeReadEinkResponseKind {
    SUCCESS,
    AUTH_EXPIRED,
    RATE_LIMITED,
    HUMAN_VERIFY,
    RETRYABLE_HTTP_ERROR,
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
            else -> WeReadEinkResponseKind.SUCCESS
        }
        return error.copy(kind = kind)
    }

    private fun parseError(raw: String): WeReadEinkResponseClassification {
        if (raw.isBlank()) return WeReadEinkResponseClassification(WeReadEinkResponseKind.SUCCESS)
        return try {
            val root = JsonParser.parseString(raw)
            if (!root.isJsonObject) {
                WeReadEinkResponseClassification(WeReadEinkResponseKind.SUCCESS)
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
            WeReadEinkResponseClassification(WeReadEinkResponseKind.SUCCESS)
        }
    }
}
