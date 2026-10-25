package com.wamp.download.weread

import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import com.wamp.download.weread.eink.WeReadEinkRuntime
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

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
    private val reportedExpiredAccounts = ConcurrentHashMap.newKeySet<String>()

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
        if (!WeReadEinkRuntime.shouldRunHealthProbe()) {
            logger.debug("凭证检测: E-Ink 等待人工验证，按长退避跳过本轮真实请求")
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

    /** Web 渠道：探测 ACTIVE 账号，并恢复持久化的 EXPIRED / 等待人工验证账号。 */
    suspend fun checkWebAccounts() {
        val accounts = WeReadAccountPool.list().filter {
            it.status in setOf(
                AccountStatus.ACTIVE,
                AccountStatus.EXPIRED,
                AccountStatus.HUMAN_VERIFY_REQUIRED,
            ) && it.hasWebCredentials()
        }
        if (accounts.isEmpty()) {
            logger.info("凭证检测: 无可探测或可恢复的微信读书 Web 账号")
            return
        }

        val expired = mutableListOf<Pair<String, WeReadWebAuth.ReloginRequestResult>>()
        for (account in accounts) {
            if (account.status == AccountStatus.HUMAN_VERIFY_REQUIRED) {
                // 等待人工验证：先做一次轻量探测；风控已解除则直接恢复，
                // 仍返回 -2041 才转交浏览器自动验证（内部有尝试冷却）。
                when (webClient.probeAccount(account)) {
                    WeReadWebResponseKind.SUCCESS -> {
                        WeReadAccountPool.clearHumanVerificationRequired(account.vid)
                        WeReadAccountPool.markProbeSuccess(account.vid)
                        reportedExpiredAccounts.remove(account.vid)
                    }
                    WeReadWebResponseKind.AUTH_EXPIRED -> {
                        WeReadAccountPool.markAuthExpired(account.vid, "凭证检测:凭证失效(401/-2012)")
                        expired += account.vid to WeReadWebAuth.requestReloginIfNeeded(account.vid, force = true)
                    }
                    WeReadWebResponseKind.HUMAN_VERIFY -> {
                        WeReadEinkRuntime.requestHumanVerificationRecovery(account.vid, "Web 账号等待人工验证")
                    }
                    else -> Unit
                }
                continue
            }
            if (account.status == AccountStatus.EXPIRED) {
                expired += account.vid to WeReadWebAuth.requestReloginIfNeeded(account.vid, force = true)
                continue
            }
            when (webClient.probeAccount(account)) {
                WeReadWebResponseKind.AUTH_EXPIRED -> {
                    WeReadAccountPool.markAuthExpired(account.vid, "凭证检测:凭证失效(401/-2012)")
                    expired += account.vid to WeReadWebAuth.requestReloginIfNeeded(account.vid, force = true)
                }
                WeReadWebResponseKind.SUCCESS -> {
                    WeReadAccountPool.markProbeSuccess(account.vid)
                    reportedExpiredAccounts.remove(account.vid)
                }
                WeReadWebResponseKind.HUMAN_VERIFY -> {
                    WeReadAccountPool.markHumanVerificationRequired(account.vid)
                    WeReadEinkRuntime.requestHumanVerificationRecovery(account.vid, "Web 探测 -2041")
                }
                else -> Unit  // 存活 / 频控 / 网络异常均不动作
            }
        }

        if (expired.isNotEmpty()) {
            logger.warn(
                "凭证检测: ${expired.size} 个微信读书 Web 账号等待恢复: " +
                    expired.joinToString { "${it.first}=${it.second}" },
            )
            val unhandled = mutableListOf<Pair<String, WeReadWebAuth.ReloginRequestResult>>()
            expired.forEach { (vid, result) ->
                // STARTED/ALREADY_RUNNING/BACKOFF 都表示二维码或设备登录流程已承接，
                // 不再额外发一条“账号失效”文字，避免同一账号收到文字 + 二维码两条消息。
                val handledAutomatically = shouldSuppressExpiredText(result)
                if (handledAutomatically) {
                    logger.info(
                        "凭证检测: Web 账号已由设备/二维码流程承接，跳过重复文字通知: " +
                            "vid=$vid, result=$result",
                    )
                } else if (reportedExpiredAccounts.add(vid)) {
                    unhandled += vid to result
                }
            }
            if (unhandled.isNotEmpty()) {
                WebhookNotifier.notify(
                    "weread_accounts_expired",
                    mapOf(
                        "message" to unhandled.joinToString("；") { (vid, result) ->
                            "$vid: ${recoveryMessage(result)}"
                        },
                        "vid" to unhandled.joinToString(",") { it.first },
                        "recovery_result" to unhandled.joinToString(",") { it.second.name },
                    ),
                )
            }
        } else {
            logger.info("凭证检测: 微信读书 Web 账号全部存活（${accounts.size} 个）")
        }
    }

    internal fun shouldSuppressExpiredText(result: WeReadWebAuth.ReloginRequestResult): Boolean = when (result) {
        WeReadWebAuth.ReloginRequestResult.STARTED,
        WeReadWebAuth.ReloginRequestResult.ALREADY_RUNNING,
        WeReadWebAuth.ReloginRequestResult.BACKOFF,
        -> true
        else -> false
    }

    internal fun recoveryMessage(result: WeReadWebAuth.ReloginRequestResult): String = when (result) {
        WeReadWebAuth.ReloginRequestResult.STARTED ->
            "微信读书 Web 凭证失效，人工扫码二维码生成任务已启动，二维码将由机器人发送"
        WeReadWebAuth.ReloginRequestResult.ALREADY_RUNNING ->
            "微信读书 Web 凭证失效，已有二维码正在等待扫码"
        WeReadWebAuth.ReloginRequestResult.BACKOFF ->
            "微信读书 Web 凭证失效，上次二维码恢复未完成，正在退避等待下次重试"
        WeReadWebAuth.ReloginRequestResult.AUTO_QR_DISABLED ->
            "微信读书 Web 凭证失效，自动二维码通知已关闭；请调用 /api/weread/account/recover 人工启动"
        WeReadWebAuth.ReloginRequestResult.NO_ACCOUNT ->
            "微信读书 Web 凭证失效，但账号已不存在"
        WeReadWebAuth.ReloginRequestResult.NOT_ELIGIBLE ->
            "微信读书 Web 账号当前不满足恢复条件"
        WeReadWebAuth.ReloginRequestResult.RECOVERY_UNAVAILABLE ->
            "微信读书 Web 凭证失效，但恢复协程不可用，请检查服务状态"
    }
}
