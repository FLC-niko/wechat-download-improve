package com.wamp.download.weread

import com.google.gson.JsonParser

internal enum class WeReadWebResponseKind {
    SUCCESS,
    AUTH_EXPIRED,
    RATE_LIMITED,
    HUMAN_VERIFY,
    RETRYABLE_HTTP_ERROR,
    APPLICATION_ERROR,
    HTTP_ERROR,
}

internal data class WeReadWebResponseClassification(
    val kind: WeReadWebResponseKind,
    val errCode: Int? = null,
    val errMsg: String? = null,
)

internal object WeReadWebResponseClassifier {
    fun classify(httpCode: Int, raw: String): WeReadWebResponseClassification {
        val error = parseError(raw)
        val kind = when {
            httpCode == 401 || error.errCode == -2012 || error.errCode == -2010 ->
                WeReadWebResponseKind.AUTH_EXPIRED
            error.errCode == -2041 -> WeReadWebResponseKind.HUMAN_VERIFY
            httpCode == 429 || error.errCode == -2014 -> WeReadWebResponseKind.RATE_LIMITED
            httpCode in 500..599 -> WeReadWebResponseKind.RETRYABLE_HTTP_ERROR
            httpCode !in 200..299 -> WeReadWebResponseKind.HTTP_ERROR
            error.errCode != null && error.errCode != 0 -> WeReadWebResponseKind.APPLICATION_ERROR
            else -> WeReadWebResponseKind.SUCCESS
        }
        return error.copy(kind = kind)
    }

    private fun parseError(raw: String): WeReadWebResponseClassification {
        if (raw.isBlank()) return WeReadWebResponseClassification(WeReadWebResponseKind.SUCCESS)
        return try {
            val root = JsonParser.parseString(raw)
            if (!root.isJsonObject) {
                WeReadWebResponseClassification(WeReadWebResponseKind.SUCCESS)
            } else {
                val obj = root.asJsonObject
                val codeElement = obj.get("errCode") ?: obj.get("errcode")
                val errCode = codeElement?.let {
                    runCatching { it.asInt }.getOrNull()
                }
                val errMsg = obj.get("errMsg")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: obj.get("errmsg")?.takeIf { it.isJsonPrimitive }?.asString
                WeReadWebResponseClassification(
                    kind = WeReadWebResponseKind.SUCCESS,
                    errCode = errCode,
                    errMsg = errMsg,
                )
            }
        } catch (_: Exception) {
            WeReadWebResponseClassification(WeReadWebResponseKind.SUCCESS)
        }
    }
}
