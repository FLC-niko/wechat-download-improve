package com.wamp.download.weread

import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import com.wamp.download.weread.eink.WeReadEinkRuntime
import org.slf4j.LoggerFactory

/**
 * 启动时凭证健康检查
 *
 * 部署重启后检查各渠道凭证状态：
 * - E-Ink：只在缺少 accessToken 或服务端明确过期时间已到时续签；未知过期时间继续由
 *   401/-2012 驱动，避免每次重启都无条件轮换 refreshToken。
 * - Web 渠道：对 ACTIVE 账号做一次书架轻量探测，失效账号标记 EXPIRED 并汇总告警。
 * - 主微信：由 LoginChecker 负责周期探测，此处不重复；自动恢复是否开启由其开关决定。
 *
 * 网络类异常一律不判死（避免网络抖动误杀好凭证）。
 */
object CredentialHealthChecker {
    private val logger = LoggerFactory.getLogger(CredentialHealthChecker::class.java)
    private val webClient = WeReadWebClient()

    suspend fun checkAll() {
        checkEink()
        checkWebAccounts()
    }

    /**
     * E-Ink：明确到期时续签；未知到期时间时发送轻量鉴权请求主动探活。
     */
    suspend fun checkEink() {
        if (!AppConfig.wereadEinkEnabled) return
        val session = WeReadEinkRuntime.session()
        if (session == null) {
            logger.info("启动检测: E-Ink 凭证未配置，可调用 /api/weread/eink/login/start 扫码激活")
            return
        }
        if (session.refreshStatus().authExpired) {
            logger.warn("凭证检测: E-Ink refreshToken 已失效，继续恢复编排")
            WeReadEinkRuntime.ensureAutoRecovery()
            return
        }

        WeReadEinkRuntime.refreshIfExpiryDue()
        if (session.refreshStatus().authExpired) {
            WeReadEinkRuntime.ensureAutoRecovery()
            return
        }

        val healthy = WeReadEinkRuntime.probe()
        if (healthy) {
            logger.info("凭证检测: E-Ink 真实鉴权探测成功")
        } else {
            val status = WeReadEinkRuntime.status()
            logger.warn(
                "凭证检测: E-Ink 探测未确认可用: result=${status["last_probe_result"]}, " +
                    "error=${status["last_probe_error"]}",
            )
        }
    }

    /** Web 渠道：探测 ACTIVE 账号，并恢复持久化的 EXPIRED 账号。 */
    suspend fun checkWebAccounts() {
        val accounts = WeReadAccountPool.list().filter {
            it.status in setOf(AccountStatus.ACTIVE, AccountStatus.EXPIRED) && it.hasWebCredentials()
        }
        if (accounts.isEmpty()) {
            logger.info("凭证检测: 无可探测或可恢复的微信读书 Web 账号")
            return
        }

        val expired = mutableListOf<String>()
        for (account in accounts) {
            if (account.status == AccountStatus.EXPIRED) {
                expired += account.vid
                WeReadWebAuth.requestReloginIfNeeded(account.vid, force = true)
                continue
            }
            when (webClient.probeAccount(account)) {
                WeReadWebResponseKind.AUTH_EXPIRED -> {
                    WeReadAccountPool.markAuthExpired(account.vid, "凭证检测:凭证失效(401/-2012)")
                    expired += account.vid
                    WeReadWebAuth.requestReloginIfNeeded(account.vid, force = true)
                }
                WeReadWebResponseKind.SUCCESS -> WeReadAccountPool.markProbeSuccess(account.vid)
                else -> Unit  // 存活 / 频控 / 网络异常均不动作
            }
        }

        if (expired.isNotEmpty()) {
            logger.warn("凭证检测: ${expired.size} 个微信读书 Web 账号等待恢复: ${expired.joinToString()}")
            WebhookNotifier.notify("weread_accounts_expired", mapOf(
                "message" to "微信读书 Web 账号凭证失效 ${expired.size} 个（${expired.joinToString()}），已进入恢复编排",
            ))
        } else {
            logger.info("凭证检测: 微信读书 Web 账号全部存活（${accounts.size} 个）")
        }
    }
}
