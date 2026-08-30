package com.wamp.download.weread

import com.wamp.download.config.AppConfig

/**
 * 微信读书官方 Web 端凭证（wr_skey 系列 cookie）
 *
 * 通过 Web 扫码登录获得。新版值实际是 accessToken，接口不返回 refreshToken 或可靠过期日期。
 */
data class WeReadWebCredentials(
    val wrSkey: String = "",           // 新版登录中保存 accessToken（字段名为兼容旧存储）
    val wrVid: String = "",            // 用户 ID
    val wrRt: String = "",             // refresh token（session/init 返回）
    val username: String = "",         // 显示名（便于识别账号）
    val loginTime: Long = 0L,          // 登录时间戳（毫秒）
    var lastRefreshTime: Long = 0L,    // 最后刷新时间（毫秒），主动刷新时更新
    var refreshFailCount: Int = 0,     // 连续刷新失败计数
) {
    /** 序列化为 Cookie 头（供请求携带） */
    fun toCookieString(): String = buildString {
        if (wrSkey.isNotEmpty()) append("wr_skey=$wrSkey; ")
        if (wrVid.isNotEmpty()) append("wr_vid=$wrVid; ")
        if (wrRt.isNotEmpty()) append("wr_rt=$wrRt")
        if (isEmpty()) append("")
    }.trimEnd(' ', ';')

    /** 旧调度兼容字段；新版 Web accessToken 不支持主动续签。 */
    @Deprecated("Web accessToken has no refresh flow; use E-Ink credentials")
    fun needRefresh(): Boolean =
        wrSkey.isNotEmpty() &&
            (lastRefreshTime <= 0L || System.currentTimeMillis() - lastRefreshTime >= AppConfig.wereadRefreshInterval * 1000)

    private fun isEmpty(): Boolean = wrSkey.isEmpty() && wrVid.isEmpty() && wrRt.isEmpty()
}

/**
 * 微信读书账号状态机
 *
 * ACTIVE   → 可用（默认）
 * COOLDOWN → 频控冷却中（到期自动恢复 ACTIVE）
 * EXPIRED  → 凭证失效，需重新扫码
 * DISABLED → 手动禁用
 */
enum class AccountStatus {
    ACTIVE,
    COOLDOWN,
    EXPIRED,
    DISABLED,
}

/**
 * 微信读书账号（一个微信账号 = 一套 wr_skey 凭证）
 *
 * @param vid 账号唯一标识（= wrVid）
 * @param credentials 该账号的凭证
 * @param totalRequests 累计请求次数（调度统计）
 * @param freqControlCount 累计频控次数（调度统计）
 * @param lastUsedTime 最后使用时间戳（least_used 调度依据）
 * @param cooldownUntil 频控冷却截止时间戳（毫秒），未冷却为 0
 * @param status 账号状态
 *
 * 注意：inUse（并发互斥占用标记）不在此类中持久化，
 * 由 WeReadAccountPool 内部 ConcurrentHashMap 管理（见 isAvailable 注释）。
 */
data class WeReadAccount(
    val vid: String,
    var credentials: WeReadWebCredentials,
    /** 原设备槽位；serial 为空时表示尚未完成绑定。 */
    var boundSerial: String = "",
    var boundUserId: Int = 0,
    /** FIXED_DEVICE 或 ELASTIC_DEVICE。默认固定在原槽位，避免跨账号串号。 */
    var bindingStrategy: String = "FIXED_DEVICE",
    var status: AccountStatus = AccountStatus.ACTIVE,
    var totalRequests: Long = 0L,
    var freqControlCount: Long = 0L,
    var lastUsedTime: Long = 0L,
    var cooldownUntil: Long = 0L,
    var disabledReason: String = "",
) {
    /** 是否拥有足够的 Web 凭证发起请求。 */
    fun hasWebCredentials(): Boolean =
        credentials.wrVid.isNotBlank() && credentials.wrSkey.isNotBlank()

    /** 是否可被调度：冷却到期后无需重启进程即可重新参与调度。 */
    fun isAvailable(now: Long = System.currentTimeMillis()): Boolean =
        hasWebCredentials() && when (status) {
            AccountStatus.ACTIVE -> now >= cooldownUntil
            AccountStatus.COOLDOWN -> now >= cooldownUntil
            AccountStatus.EXPIRED, AccountStatus.DISABLED -> false
        }
}
