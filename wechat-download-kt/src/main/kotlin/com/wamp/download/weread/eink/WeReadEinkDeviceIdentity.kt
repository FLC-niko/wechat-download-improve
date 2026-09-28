package com.wamp.download.weread.eink

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 墨水屏设备身份模型。
 *
 * 每个 E-Ink 账号独立分配一套设备身份（品牌/型号/系统版本/deviceId/installId），
 * 严禁多账号共享同一设备指纹，避免被微信读书关联风控。身份随凭证一起持久化，
 * 重启后保持不变。
 */
data class WeReadEinkDeviceModel(
    val brand: String,
    val model: String,
    val build: String,
    val osver: String,
    val deviceType: Int,
)

object WeReadEinkDeviceModels {
    /** 迁移旧单账号凭证时使用的历史默认型号（保持与原实现一致）。 */
    const val DEFAULT_BRAND = "Onyx"
    const val DEFAULT_MODEL = "BOOX"
    const val DEFAULT_BUILD = "onyx"
    const val DEFAULT_OSVER = "11"
    const val DEFAULT_DEVICE_TYPE = 3

    /** 型号池：新账号优先分配未被占用的型号，降低多账号指纹重叠。 */
    val POOL: List<WeReadEinkDeviceModel> = listOf(
        WeReadEinkDeviceModel(brand = "Onyx", model = "BOOX", build = "onyx", osver = "11", deviceType = 3),
        WeReadEinkDeviceModel(brand = "Onyx", model = "BOOX Note", build = "onyx", osver = "11", deviceType = 3),
        WeReadEinkDeviceModel(brand = "iReader", model = "Smart X", build = "ireader", osver = "11", deviceType = 3),
        WeReadEinkDeviceModel(brand = "Moan", model = "inkPalm", build = "moan", osver = "11", deviceType = 3),
        WeReadEinkDeviceModel(brand = "Hanvon", model = "N10", build = "hanvon", osver = "11", deviceType = 3),
        WeReadEinkDeviceModel(brand = "Hisense", model = "A9", build = "hisense", osver = "11", deviceType = 3),
    )

    val DEFAULT: WeReadEinkDeviceModel = WeReadEinkDeviceModel(
        brand = DEFAULT_BRAND,
        model = DEFAULT_MODEL,
        build = DEFAULT_BUILD,
        osver = DEFAULT_OSVER,
        deviceType = DEFAULT_DEVICE_TYPE,
    )

    /** 选择一条尚未被占用的型号；全部占用时回退默认型号。 */
    fun pickUnused(usedModels: Set<String>): WeReadEinkDeviceModel =
        POOL.firstOrNull { it.model !in usedModels } ?: DEFAULT
}

/**
 * 按账号设备身份构造的 E-Ink Profile。
 *
 * 请求头（含 User-Agent）与登录参数全部来自该账号自己的型号，不再共享全局默认设备。
 */
class WeReadEinkAccountProfile(
    private val model: WeReadEinkDeviceModel,
    appVer: String = DEFAULT_APP_VER,
) : WeReadEinkProfile {

    override val versionHeaders: Map<String, String> = linkedMapOf(
        "baseapi" to "30",
        "appver" to appVer,
        "basever" to appVer,
        "osver" to model.osver,
        "channelId" to "900",
        "User-Agent" to "WeRead/$appVer WRBrand/${model.brand} wr_eink Dalvik/2.1.0 " +
            "(Linux; U; Android ${model.osver}; ${model.model} Build/${model.build})",
    )

    override val deviceName: String = model.model
    override val deviceType: Int = model.deviceType

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

    companion object {
        const val DEFAULT_APP_VER = "2.1.2.10245900"
    }
}
