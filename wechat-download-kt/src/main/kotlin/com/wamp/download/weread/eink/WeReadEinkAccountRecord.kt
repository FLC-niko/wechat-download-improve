package com.wamp.download.weread.eink

/**
 * 一个 E-Ink 账号的完整持久化记录：凭证 + 独立的设备身份。
 *
 * 设备身份与凭证绑定保存，确保同一账号跨重启使用同一指纹，且不同账号之间
 * 绝不共享品牌/型号/系统版本/installId。
 */
data class WeReadEinkAccountRecord(
    val credentials: WeReadEinkCredentials,
    val deviceBrand: String = WeReadEinkDeviceModels.DEFAULT_BRAND,
    val deviceModel: String = WeReadEinkDeviceModels.DEFAULT_MODEL,
    val deviceBuild: String = WeReadEinkDeviceModels.DEFAULT_BUILD,
    val deviceOsver: String = WeReadEinkDeviceModels.DEFAULT_OSVER,
    val deviceType: Int = WeReadEinkDeviceModels.DEFAULT_DEVICE_TYPE,
    val installId: String = "",
) {
    val vid: String get() = credentials.vid

    fun deviceIdentity(): WeReadEinkDeviceModel = WeReadEinkDeviceModel(
        brand = deviceBrand,
        model = deviceModel,
        build = deviceBuild,
        osver = deviceOsver,
        deviceType = deviceType,
    )

    fun profile(): WeReadEinkAccountProfile = WeReadEinkAccountProfile(deviceIdentity())

    companion object {
        /** 旧单账号凭证迁移：保持历史默认型号，同时补一个专属 installId。 */
        fun migrated(credentials: WeReadEinkCredentials): WeReadEinkAccountRecord =
            WeReadEinkAccountRecord(
                credentials = credentials,
                installId = WeReadEinkAccountProfile(WeReadEinkDeviceModels.DEFAULT).newInstallId(),
            )

        /** 新账号：分配一套未被占用的设备型号与全新 installId。 */
        fun forNewAccount(
            credentials: WeReadEinkCredentials,
            model: WeReadEinkDeviceModel,
        ): WeReadEinkAccountRecord = WeReadEinkAccountRecord(
            credentials = credentials,
            deviceBrand = model.brand,
            deviceModel = model.model,
            deviceBuild = model.build,
            deviceOsver = model.osver,
            deviceType = model.deviceType,
            installId = WeReadEinkAccountProfile(model).newInstallId(),
        )
    }
}
