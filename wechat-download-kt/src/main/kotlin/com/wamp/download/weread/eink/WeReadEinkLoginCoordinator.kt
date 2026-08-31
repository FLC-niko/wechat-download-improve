package com.wamp.download.weread.eink

import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import com.wamp.download.weread.WeReadWebAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Runs one E-Ink QR login at a time and exposes a secret-free status snapshot. */
object WeReadEinkLoginCoordinator {
    private val logger = LoggerFactory.getLogger(WeReadEinkLoginCoordinator::class.java)
    private val inProgress = AtomicBoolean(false)

    @Volatile
    private var state: String = "IDLE"

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var updatedAt: Long = 0L

    @Volatile
    private var automaticAttemptCount: Int = 0

    @Volatile
    private var nextAutomaticRetryAt: Long = 0L

    @Volatile
    private var automatic: Boolean = false

    fun start(scope: CoroutineScope, automatic: Boolean = false): Boolean {
        if (!AppConfig.wereadEinkEnabled) return false
        if (!scope.isActive) return false
        val now = System.currentTimeMillis()
        if (automatic && now < nextAutomaticRetryAt) return false
        if (!inProgress.compareAndSet(false, true)) return false
        state = "STARTING"
        lastError = ""
        updatedAt = now
        this.automatic = automatic
        val job = scope.launch(Dispatchers.IO) {
            try {
                val client = WeReadEinkQrLoginClient(baseUrl = AppConfig.wereadEinkBaseUrl)
                val credentials = client.login(
                    onQr = { qr ->
                        state = "WAITING_SCAN"
                        updatedAt = System.currentTimeMillis()
                        val png = WeReadWebAuth.generateQrPng(qr.confirmUrl)
                            ?: throw WeReadEinkQrLoginException("failed to generate E-Ink QR image")
                        val localFileAvailable = runCatching {
                            File("qrcode").mkdirs()
                            File("qrcode/weread_eink_qrcode.png").writeBytes(png)
                        }.onFailure { error ->
                            logger.warn("E-Ink 二维码本地保存失败: ${error.message}")
                        }.isSuccess
                        val delivery = runBlocking {
                            WebhookNotifier.pushQrcode(
                                imageBytes = png,
                                message = "请使用微信扫码登录微信读书 E-Ink 通道",
                                fileName = "weread_eink_qrcode.png",
                                localFileAvailable = localFileAvailable,
                            )
                        }
                        if (automatic && !delivery.anySuccess) {
                            throw WeReadEinkQrLoginException(
                                "E-Ink 自动恢复二维码未能投递到通知机器人",
                            )
                        }
                        if (!automatic && !localFileAvailable && !delivery.anySuccess) {
                            throw WeReadEinkQrLoginException("E-Ink 登录二维码既未保存到本地，也未投递到通知机器人")
                        }
                        logger.info("E-Ink 登录二维码已生成并等待扫码")
                    },
                    onStatus = { qrStatus ->
                        state = qrStatus.name
                        updatedAt = System.currentTimeMillis()
                    },
                )
                WeReadEinkRuntime.installCredentials(credentials)
                state = "SUCCEEDED"
                updatedAt = System.currentTimeMillis()
                automaticAttemptCount = 0
                nextAutomaticRetryAt = 0L
                logger.info("E-Ink 登录成功并已原子保存凭证: vid=${credentials.vid}")
                WebhookNotifier.notify(
                    "weread_eink_recovered",
                    mapOf("message" to "微信读书 E-Ink 登录已恢复", "vid" to credentials.vid),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state = "FAILED"
                lastError = error.message.orEmpty().take(500)
                updatedAt = System.currentTimeMillis()
                if (automatic) {
                    automaticAttemptCount++
                    val exponent = (automaticAttemptCount - 1).coerceIn(0, 10)
                    val baseDelayMs = AppConfig.wereadEinkReloginRetryBackoff * 1000L
                    val maxDelayMs = AppConfig.wereadEinkRefreshRetryMax * 1000L
                    val delayMs = (baseDelayMs * (1L shl exponent)).coerceAtMost(maxDelayMs)
                    nextAutomaticRetryAt = System.currentTimeMillis() + delayMs
                }
                logger.error("E-Ink 登录失败: ${error.message}")
            } finally {
                inProgress.set(false)
            }
        }
        // A cancelled scope may cancel a newly-created coroutine before its body runs; keep
        // the coordinator reusable instead of leaving the global inProgress flag stuck.
        job.invokeOnCompletion { inProgress.set(false) }
        return true
    }

    fun isInProgress(): Boolean = inProgress.get()

    fun status(): Map<String, Any?> = mapOf(
        "in_progress" to inProgress.get(),
        "state" to state,
        "last_error" to lastError.ifBlank { null },
        "updated_at" to updatedAt,
        "automatic" to automatic,
        "automatic_attempt_count" to automaticAttemptCount,
        "next_automatic_retry_at" to nextAutomaticRetryAt.takeIf { it > 0L },
    )
}
