package com.wamp.download.weread.eink

import java.security.MessageDigest

interface WeReadEinkProfile {
    val versionHeaders: Map<String, String>
    val deviceName: String
    val deviceType: Int

    fun authHeaders(credentials: WeReadEinkCredentials): Map<String, String>

    fun refreshSignature(
        deviceId: String,
        timestamp: Long,
        random: Int,
        refreshToken: String,
    ): String

    fun loginBodyExtras(): Map<String, Any?>
}

object DefaultWeReadEinkProfile : WeReadEinkProfile {
    override val versionHeaders: Map<String, String> = linkedMapOf(
        "baseapi" to "30",
        "appver" to "2.1.2.10245900",
        "basever" to "2.1.2.10245900",
        "osver" to "11",
        "channelId" to "900",
        "User-Agent" to "WeRead/2.1.2 WRBrand/Onyx wr_eink Dalvik/2.1.0 (Linux; U; Android 11; BOOX Build/onyx)",
    )

    override val deviceName: String = "BOOX"
    override val deviceType: Int = 3

    override fun authHeaders(credentials: WeReadEinkCredentials): Map<String, String> {
        val accessToken = credentials.accessToken
        require(!accessToken.isNullOrBlank()) { "accessToken is required for authenticated requests" }
        return mapOf(
            "vid" to credentials.vid,
            "accessToken" to accessToken,
        )
    }

    override fun refreshSignature(
        deviceId: String,
        timestamp: Long,
        random: Int,
        refreshToken: String,
    ): String {
        val input = "$timestamp$deviceId$random"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    override fun loginBodyExtras(): Map<String, Any?> = mapOf("deviceType" to deviceType)
}
