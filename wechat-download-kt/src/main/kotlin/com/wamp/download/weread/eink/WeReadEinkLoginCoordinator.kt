package com.wamp.download.weread.eink

import com.wamp.download.adb.AdbDeviceSupervisor
import com.wamp.download.adb.AdbUserOperations
import com.wamp.download.adb.DeviceSlot
import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import com.wamp.download.weread.WeReadWebAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Runs one E-Ink QR login at a time and exposes a secret-free status snapshot. */
object WeReadEinkLoginCoordinator {
    private val logger = LoggerFactory.getLogger(WeReadEinkLoginCoordinator::class.java)
    private val inProgress = AtomicBoolean(false)

    @Volatile
    private var supervisor: AdbDeviceSupervisor? = null

    @Volatile
    private var operations: AdbUserOperations? = null

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

    fun configureAdb(supervisor: AdbDeviceSupervisor, operations: AdbUserOperations) {
        this.supervisor = supervisor
        this.operations = operations
    }

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
            var adbScanJob: Job? = null
            var adbSlot: DeviceSlot? = null
            var adbPushPath: String = ""
            try {
                val client = WeReadEinkQrLoginClient(baseUrl = AppConfig.wereadEinkBaseUrl)
                val credentials = client.login(
                    onQr = { qr ->
                        state = "WAITING_SCAN"
                        updatedAt = System.currentTimeMillis()
                        val png = WeReadWebAuth.generateQrPng(qr.confirmUrl)
                            ?: throw WeReadEinkQrLoginException("failed to generate E-Ink QR image")
                        val qrFile = File("qrcode/weread_eink_qrcode.png")
                        val localFileAvailable = runCatching {
                            File("qrcode").mkdirs()
                            qrFile.writeBytes(png)
                        }.onFailure { error ->
                            logger.warn("E-Ink 二维码本地保存失败: ${error.message}")
                        }.isSuccess

                        // 1. 保留未上线 ADB 设备就推送机器人的逻辑：无论 ADB 是否可用，始终执行 Webhook 投递
                        val delivery = runBlocking {
                            WebhookNotifier.pushQrcode(
                                imageBytes = png,
                                message = "请使用微信扫码登录微信读书 E-Ink 通道",
                                fileName = "weread_eink_qrcode.png",
                                localFileAvailable = localFileAvailable,
                            )
                        }

                        // 2. 检查是否有在线可用的 ADB 槽位；若有且开启，执行自动物理代扫自愈
                        val curSupervisor = supervisor
                        val curOperations = operations
                        val slot = curSupervisor?.availableSlots()?.firstOrNull()
                        if (curSupervisor != null && curOperations != null && slot != null) {
                            val pushResult = curOperations.pushQrCode(slot, qrFile)
                            if (pushResult.success) {
                                adbSlot = slot
                                adbPushPath = pushResult.destinationPath
                                logger.info("E-Ink 二维码已成功推送到 ADB 设备: slot=${slot.slotKey}, 启动后台自动代扫")
                                adbScanJob = scope.launch(Dispatchers.IO) {
                                    val lock = curSupervisor.getDeviceLock(slot)
                                    lock.withLock {
                                        try {
                                            logger.info("获取设备硬件锁成功，执行 E-Ink 扫码与确认: slot=${slot.slotKey}")
                                            if (slot.isLegacyRoute && curOperations.isReady(slot)) {
                                                curOperations.startWeReadLogin(slot)
                                            } else {
                                                curOperations.performWeReadScanAndConfirm(slot, isWeb = false)
                                            }
                                        } catch (e: Exception) {
                                            logger.warn("E-Ink 自动代扫执行异常: ${e.message}")
                                        }
                                    }
                                }
                            } else {
                                logger.warn("E-Ink 二维码推送到 ADB 设备失败: slot=${slot.slotKey}")
                            }
                        } else {
                            logger.info("当前无在线 ADB 设备或未启用 ADB，等待人工扫码（已推送机器人: ${delivery.anySuccess}）")
                        }

                        // 3. 校验恢复渠道有效性：有 ADB 代扫或已成功投递机器人均算有效；两者皆无才判定失败
                        if (automatic && !delivery.anySuccess && adbScanJob == null) {
                            throw WeReadEinkQrLoginException("E-Ink 自动恢复二维码未能投递到通知机器人且无可用 ADB 代扫")
                        }
                        if (!automatic && !localFileAvailable && !delivery.anySuccess && adbScanJob == null) {
                            throw WeReadEinkQrLoginException("E-Ink 登录二维码既未保存到本地，也未投递到通知机器人且无可用 ADB 代扫")
                        }
                        logger.info("E-Ink 登录二维码已生成并等待扫码" + if (adbScanJob != null) "（ADB 自动代扫进行中）" else "（等待人工扫码）")
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
                    WebhookNotifier.notify(
                        "weread_eink_recovery_failed",
                        mapOf(
                            "message" to "微信读书 E-Ink 二维码恢复未完成，服务将按退避策略重试",
                            "reason" to lastError,
                            "next_retry_at" to nextAutomaticRetryAt,
                        ),
                    )
                }
                logger.error("E-Ink 登录失败: ${error.message}")
            } finally {
                try {
                    adbScanJob?.cancelAndJoin()
                } catch (_: Exception) {}
                val curOperations = operations
                val slot = adbSlot
                if (curOperations != null && slot != null) {
                    runCatching {
                        curOperations.dismissAuthorizationAndReset(slot)
                    }.onFailure {
                        logger.warn("E-Ink 复位微信前台失败: slot=${slot.slotKey}, ${it.message}")
                    }
                    if (adbPushPath.isNotBlank()) {
                        curOperations.cleanupQrCode(slot, adbPushPath)
                    }
                }
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
