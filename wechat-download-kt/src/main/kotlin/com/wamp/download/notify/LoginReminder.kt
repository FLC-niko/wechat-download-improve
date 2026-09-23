package com.wamp.download.notify

import com.wamp.download.auth.AuthManager
import com.wamp.download.config.AppConfig
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

/**
 * 登录过期提醒 — 对齐 Python 版 login_reminder.py
 *
 * 定期检查微信登录凭证过期状态，提前 webhook 通知。
 * - 提前 24 小时：一般警告
 * - 提前 6 小时：严重警告
 * - 已过期：过期通知
 */
object LoginReminder {
    private val logger = LoggerFactory.getLogger(LoginReminder::class.java)

    private const val CHECK_INTERVAL = 6L * 3600      // 每 6 小时检查一次
    private const val WARNING_THRESHOLD = 24L * 3600   // 提前 24 小时预警
    private const val CRITICAL_THRESHOLD = 6L * 3600   // 提前 6 小时严重警告

    @Volatile
    private var running = false
    private var lastWarningLevel: String? = null

    suspend fun start() {
        if (running) return
        running = true
        logger.info("登录提醒服务已启动（检查间隔: ${CHECK_INTERVAL}秒）")

        while (running) {
            try {
                checkLoginStatus()
            } catch (e: Exception) {
                logger.error("检查登录状态失败: ${e.message}", e)
            }
            delay(CHECK_INTERVAL * 1000)
        }
    }

    fun stop() {
        running = false
        logger.info("登录提醒服务已停止")
    }

    private suspend fun checkLoginStatus() {
        // 主通道关闭时不产生任何登录过期告警（与 LoginChecker 保持一致）；
        // 清空告警等级，保证以后重新启用主通道且凭证仍失效时能再次告警。
        if (!AppConfig.mainEnabled) {
            lastWarningLevel = null
            return
        }

        if (!AuthManager.isLoggedIn()) {
            logger.debug("无登录凭证，跳过检查")
            return
        }

        val timeLeft = AuthManager.timeLeftSeconds()
        if (timeLeft == Long.MAX_VALUE) {
            logger.debug("凭证无过期时间，跳过检查")
            return
        }

        val nickname = AuthManager.get().nickname

        // 已过期
        if (timeLeft <= 0) {
            if (lastWarningLevel != "expired") {
                logger.error("登录凭证已过期 [$nickname]")
                WebhookNotifier.notify("login_expired", mapOf(
                    "nickname" to nickname,
                    "message" to "登录凭证已过期，API 功能将受限，请重新登录",
                ))
                lastWarningLevel = "expired"
            }
            return
        }

        // 严重警告
        if (timeLeft <= CRITICAL_THRESHOLD) {
            if (lastWarningLevel !in listOf("critical", "expired")) {
                val hours = timeLeft / 3600.0
                logger.error("登录凭证即将过期（紧急）[$nickname] - 剩余 ${"%.1f".format(hours)} 小时")
                WebhookNotifier.notify("login_expiring_critical", mapOf(
                    "nickname" to nickname,
                    "hours_left" to "%.1f".format(hours),
                    "level" to "critical",
                    "message" to "登录凭证将在 ${"%.1f".format(hours)} 小时后过期（紧急），请立即重新登录",
                ))
                lastWarningLevel = "critical"
            }
            return
        }

        // 一般警告
        if (timeLeft <= WARNING_THRESHOLD) {
            if (lastWarningLevel !in listOf("warning", "critical", "expired")) {
                val hours = timeLeft / 3600.0
                logger.warn("登录凭证即将过期 [$nickname] - 剩余 ${"%.1f".format(hours)} 小时")
                WebhookNotifier.notify("login_expiring_soon", mapOf(
                    "nickname" to nickname,
                    "hours_left" to "%.1f".format(hours),
                    "level" to "warning",
                    "message" to "登录凭证将在 ${"%.1f".format(hours)} 小时后过期，请及时重新登录",
                ))
                lastWarningLevel = "warning"
            }
            return
        }

        // 状态正常
        if (lastWarningLevel != null) {
            lastWarningLevel = null
            logger.info("登录状态已恢复正常: $nickname")
        }
    }
}
