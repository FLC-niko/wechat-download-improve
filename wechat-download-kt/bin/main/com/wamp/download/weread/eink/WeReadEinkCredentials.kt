package com.wamp.download.weread.eink

/**
 * Credentials used by the WeRead mobile/e-ink API.
 */
data class WeReadEinkCredentials(
    val vid: String,
    val accessToken: String? = null,
    val refreshToken: String,
    val deviceId: String,
    /** 本地签发时间；服务端未返回时间时也用于诊断，不用于猜测有效期。 */
    val accessTokenIssuedAt: Long? = null,
    /** 服务端明确返回的绝对过期时间（毫秒）。未返回时必须保持 null。 */
    val accessTokenExpiresAt: Long? = null,
) {
    init {
        require(vid.isNotBlank()) { "vid must not be blank" }
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank" }
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
    }

    fun hasAccessToken(): Boolean = !accessToken.isNullOrBlank()

    fun accessTokenNeedsRefresh(
        now: Long = System.currentTimeMillis(),
        skewMs: Long = 0L,
    ): Boolean = !hasAccessToken() || accessTokenExpiresAt?.let { it <= now + skewMs } == true

    fun secondsUntilExpiry(now: Long = System.currentTimeMillis()): Long? =
        accessTokenExpiresAt?.let { ((it - now) / 1000L).coerceAtLeast(0L) }
}

/** Parses only expiration information explicitly returned by WeRead; no fixed lifetime is invented. */
internal object WeReadEinkTokenExpiry {
    fun parse(response: com.google.gson.JsonObject, issuedAt: Long): Long? {
        val durationSeconds = firstLong(response, "expiresIn", "expireIn", "expires_in")
        if (durationSeconds != null && durationSeconds > 0L) {
            return runCatching { Math.addExact(issuedAt, Math.multiplyExact(durationSeconds, 1000L)) }.getOrNull()
        }

        val raw = firstLong(
            response,
            "accessTokenExpiresAt",
            "accessTokenExpireTime",
            "expiresAt",
            "expireTime",
            "expiredAt",
        ) ?: return null
        if (raw <= 0L) return null
        return when {
            raw >= 1_000_000_000_000L -> raw // epoch milliseconds
            raw >= 1_000_000_000L -> runCatching { Math.multiplyExact(raw, 1000L) }.getOrNull() // epoch seconds
            else -> runCatching { Math.addExact(issuedAt, Math.multiplyExact(raw, 1000L)) }.getOrNull()
        }
    }

    private fun firstLong(response: com.google.gson.JsonObject, vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
        response.get(name)
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asLong }.getOrNull() }
    }
}
