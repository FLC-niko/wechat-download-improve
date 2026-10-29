package com.wamp.download.weread

import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import com.wamp.download.weread.eink.WeReadEinkAutoRecoveryCode
import com.wamp.download.weread.eink.WeReadEinkCaptchaSolverClient
import com.wamp.download.weread.eink.WeReadEinkHumanVerification
import com.wamp.download.weread.eink.performEinkAutoRecovery
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Web 渠道 -2041 人工验证恢复（不依赖 E-Ink 通道）。
 *
 * weread Web 前端在收到 -2041 时弹出同一个腾讯验证码（appid 2044038556），
 * 通过后把票据以 `x-wr-ticket` / `x-wr-randstr` 请求头重放原请求。
 * 这里复用同一套验证页与自动解题 sidecar，只是把探测换成 Web 接口。
 */
object WeReadWebHumanVerification {
    private val logger = LoggerFactory.getLogger(WeReadWebHumanVerification::class.java)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "weread-web-verify").apply { isDaemon = true }
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()

    /** 同一次封禁期间最多每 20 分钟尝试一次自动解题，避免把验证码服务打成连续轰炸。 */
    private const val ATTEMPT_COOLDOWN_MS = 20 * 60 * 1000L

    fun requestRecovery(vid: String, reason: String): Boolean {
        val account = WeReadAccountPool.get(vid) ?: return false
        if (!account.hasWebCredentials()) return false
        WeReadAccountPool.markHumanVerificationRequired(vid)
        val enabled = AppConfig.wereadEinkAutoSolverEnabled
        val allowlist = AppConfig.wereadEinkAutoSolverVidAllowlist
        if (!enabled || (allowlist.isNotEmpty() && vid !in allowlist)) {
            // 未启用/不在白名单时直接推送人工链接，不占用自动解题冷却时间。
            logger.info("Web 人工验证自动解题未启用或不在白名单，推送人工链接: vid={}", vid)
            worker.execute {
                runCatching { notifyManual(vid, reason) }
                    .onFailure { logger.warn("Web 人工验证通知失败: vid={}, {}", vid, it.message) }
            }
            return true
        }
        val now = System.currentTimeMillis()
        val last = lastAttemptAt[vid] ?: 0L
        if (last > 0L && now - last < ATTEMPT_COOLDOWN_MS) {
            logger.info(
                "Web 人工验证冷却中，跳过本次自动解题: vid={}, 距上次 {} 分钟",
                vid,
                (now - last) / 60_000,
            )
            return true
        }
        if (!inFlight.add(vid)) return true
        lastAttemptAt[vid] = now
        worker.execute {
            try {
                attempt(vid, reason)
            } catch (e: Exception) {
                logger.warn("Web 人工验证恢复异常: vid={}, {}", vid, e.message)
            } finally {
                inFlight.remove(vid)
            }
        }
        return true
    }

    private fun attempt(vid: String, reason: String) {
        val enabled = AppConfig.wereadEinkAutoSolverEnabled
        val allowlist = AppConfig.wereadEinkAutoSolverVidAllowlist
        val eligible = enabled && (allowlist.isEmpty() || vid in allowlist)
        val verification = WeReadEinkHumanVerification.createOrReuse(vid, AppConfig.siteUrl)
        val attempt = if (eligible) {
            performEinkAutoRecovery(
                enabled = true,
                allowlist = setOf(vid),
                vid = vid,
                verification = verification,
                solve = { url -> WeReadEinkCaptchaSolverClient.fromConfig().solve(url) },
                complete = WeReadEinkHumanVerification::complete,
                currentVerification = { WeReadEinkHumanVerification.createOrReuse(vid, AppConfig.siteUrl) },
                probe = { probeWeb(vid) },
                probeRetries = 0,
            )
        } else {
            null
        }
        logger.info(
            "Web 人工验证自动解题结束: vid={}, code={}, attempts={}",
            vid,
            attempt?.code ?: "SOLVER_DISABLED",
            attempt?.attempts ?: 0,
        )
        if (attempt?.code != WeReadEinkAutoRecoveryCode.RECOVERED) {
            notifyManual(vid, reason)
        }
    }

    private fun probeWeb(vid: String): Boolean {
        val account = WeReadAccountPool.get(vid) ?: return false
        return WeReadWebClient().probeAccount(account) == WeReadWebResponseKind.SUCCESS
    }

    private fun notifyManual(vid: String, reason: String) {
        val status = WeReadEinkHumanVerification.createOrReuse(vid, AppConfig.siteUrl)
        val verifyUrl = status.verifyUrl ?: return
        val png = WeReadEinkHumanVerification.generateQrPng(verifyUrl) ?: return
        val saved = runCatching {
            val file = java.io.File("qrcode/weread_web_verify_$vid.png")
            file.parentFile?.mkdirs()
            file.writeBytes(png)
            file.name
        }.getOrNull()
        runBlocking {
            WebhookNotifier.pushQrcode(
                imageBytes = png,
                message = "微信读书 Web 账号 $vid 触发人工验证（$reason），请扫码或打开链接完成",
                fileName = saved ?: "weread_web_verify_$vid.png",
                localFileAvailable = saved != null,
                title = "微信读书 Web 人工验证",
                heading = "请完成验证码以恢复 Web 采集（账号 $vid）",
                targetUrl = verifyUrl,
            )
        }
        logger.info("Web 人工验证链接已推送: vid={}, url={}", vid, verifyUrl)
    }
}
