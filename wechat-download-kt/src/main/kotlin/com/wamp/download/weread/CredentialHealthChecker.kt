package com.wamp.download.weread

import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import com.wamp.download.weread.eink.WeReadEinkApiException
import com.wamp.download.weread.eink.WeReadEinkLoginCoordinator
import com.wamp.download.weread.eink.WeReadEinkResponseKind
import com.wamp.download.weread.eink.WeReadEinkRuntime
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory

/**
 * 启动时凭证健康检查
 *
 * 部署重启后立即验证各渠道凭证死活，避免失效凭证一直躺到第一个任务失败才发现：
 * - E-Ink：用 refreshToken 主动续签一次（同时验证凭证链路 + 更新 accessToken）；
 *   refreshToken 也失效时告警，auto_relogin 开启时自动推送扫码。
 * - Web 渠道：对 ACTIVE 账号做一次书架轻量探测，失效账号标记 EXPIRED 并汇总告警。
 * - 主微信：由 LoginChecker 启动首查覆盖（auth_auto_recovery_enabled 开启时），此处不重复。
 *
 * 网络类异常一律不判死（避免网络抖动误杀好凭证）。
 */
object CredentialHealthChecker {
    private val logger = LoggerFactory.getLogger(CredentialHealthChecker::class.java)
    private val webClient = WeReadWebClient()

    suspend fun checkAll(scope: CoroutineScope? = null) {
        checkEink(scope)
        checkWebAccounts()
    }

    /**
     * E-Ink：启动即用 refreshToken 换新 accessToken。
     * 成功 = 凭证链路健康且 token 已更新；AUTH_EXPIRED = refreshToken 失效需重扫。
     */
    private suspend fun checkEink(scope: CoroutineScope?) {
        if (!AppConfig.wereadEinkEnabled) return
        val session = WeReadEinkRuntime.session()
        if (session == null) {
            logger.info("启动检测: E-Ink 凭证未配置，可调用 /api/weread/eink/login/start 扫码激活")
            return
        }
        try {
            session.refresh()
            logger.info("启动检测: E-Ink 凭证有效，accessToken 已续签")
        } catch (error: WeReadEinkApiException) {
            if (error.kind != WeReadEinkResponseKind.AUTH_EXPIRED) {
                logger.warn("启动检测: E-Ink 凭证探测服务端异常（不判死）: ${error.message}")
                return
            }
            logger.error("启动检测: E-Ink refreshToken 已失效，需重新扫码: ${error.message}")
            WebhookNotifier.notify("weread_eink_expired", mapOf(
                "message" to "微信读书 E-Ink 凭证已失效，请调用 /api/weread/eink/login/start 重新扫码",
            ))
            if (AppConfig.wereadAutoReloginEnabled && scope != null) {
                logger.warn("启动检测: auto_relogin 已开启，自动触发 E-Ink 扫码登录")
                WeReadEinkLoginCoordinator.start(scope)
            }
        } catch (error: Exception) {
            logger.warn("启动检测: E-Ink 凭证探测网络异常（不判死）: ${error.message}")
        }
    }

    /** Web 渠道：轻量探测 ACTIVE 账号，失效的立即标记并汇总告警。 */
    private suspend fun checkWebAccounts() {
        val accounts = WeReadAccountPool.list().filter {
            it.status == AccountStatus.ACTIVE && it.hasWebCredentials()
        }
        if (accounts.isEmpty()) {
            logger.info("启动检测: 无存活状态的微信读书 Web 账号，跳过探测")
            return
        }

        val expired = mutableListOf<String>()
        for (account in accounts) {
            when (webClient.probeAccount(account)) {
                WeReadWebResponseKind.AUTH_EXPIRED -> {
                    WeReadAccountPool.markExpired(account.vid, "启动检测:凭证失效(401/-2012)")
                    expired += account.vid
                }
                else -> Unit  // 存活 / 频控 / 网络异常均不动作
            }
        }

        if (expired.isNotEmpty()) {
            logger.warn("启动检测: ${expired.size} 个微信读书 Web 账号凭证失效: ${expired.joinToString()}")
            WebhookNotifier.notify("weread_accounts_expired", mapOf(
                "message" to "微信读书 Web 账号凭证失效 ${expired.size} 个（${expired.joinToString()}），" +
                    "可调用 /api/weread/login 重新扫码",
            ))
        } else {
            logger.info("启动检测: 微信读书 Web 账号全部存活（${accounts.size} 个）")
        }
    }
}
