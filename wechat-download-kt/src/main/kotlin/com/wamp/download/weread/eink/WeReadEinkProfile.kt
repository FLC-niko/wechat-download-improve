package com.wamp.download.weread.eink

import java.security.MessageDigest
import java.security.SecureRandom
import java.math.BigInteger

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

    fun newDeviceId(): String

    fun newInstallId(): String
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

    override fun newDeviceId(): String {
        val bytes = ByteArray(8).also(SecureRandom()::nextBytes)
        bytes[0] = (bytes[0].toInt() and 0x7f).toByte()
        val suffix = BigInteger(1, bytes).toString().padStart(19, '0')
        return "eink334691225$suffix"
    }

    override fun newInstallId(): String = buildString {
        append("eink31")
        val random = SecureRandom()
        repeat(26) { append(random.nextInt(10)) }
    }
}
