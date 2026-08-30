package com.wamp.download.weread.eink

/**
 * Credentials used by the WeRead mobile/e-ink API.
 */
data class WeReadEinkCredentials(
    val vid: String,
    val accessToken: String? = null,
    val refreshToken: String,
    val deviceId: String,
) {
    init {
        require(vid.isNotBlank()) { "vid must not be blank" }
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank" }
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
    }

    fun hasAccessToken(): Boolean = !accessToken.isNullOrBlank()
}
