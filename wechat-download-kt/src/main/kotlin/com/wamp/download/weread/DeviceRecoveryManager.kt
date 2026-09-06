package com.wamp.download.weread

import com.wamp.download.adb.AdbUserOperations
import com.wamp.download.adb.DeviceSlot
import com.wamp.download.adb.AdbDeviceSupervisor
import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/** 账号失效后的设备路由、扫码编排和热插拔重试。 */
data class DeviceRecoveryStatus(
    val attempts: Int = 0,
    val lastError: String = "",
    val nextAttemptAt: Long = 0L,
)

class DeviceRecoveryManager(
    private val supervisor: AdbDeviceSupervisor,
    private val operations: AdbUserOperations = AdbUserOperations(),
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(DeviceRecoveryManager::class.java)
    private val deviceLocks = ConcurrentHashMap<String, Mutex>()
    private val pending = ConcurrentHashMap<String, Job>()
    private val waiting = ConcurrentHashMap.newKeySet<String>()
    private val attempts = ConcurrentHashMap<String, DeviceRecoveryStatus>()

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(AppConfig.adbPollIntervalMs)
                retryPending()
            }
        }
    }

    fun request(vid: String) {
        if (WeReadAccountPool.get(vid) == null) {
            waiting.remove(vid)
            attempts.remove(vid)
            return
        }
        waiting.add(vid)
        synchronized(pending) {
            if (pending[vid]?.isActive == true) return@synchronized
            if (System.currentTimeMillis() < (attempts[vid]?.nextAttemptAt ?: 0L)) return@synchronized
            lateinit var recoveryJob: Job
            recoveryJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    val failure = recover(vid)
                    if (failure == null) {
                        waiting.remove(vid)
                        attempts.remove(vid)
                    } else {
                        recordFailure(vid, failure)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logger.error("微信读书设备恢复异常: vid=$vid, ${error.message}", error)
                    recordFailure(vid, error.message ?: error.javaClass.simpleName)
                } finally {
                    pending.remove(vid, recoveryJob)
                }
            }
            pending[vid] = recoveryJob
            recoveryJob.start()
        }
    }

    fun pendingVids(): List<String> = waiting.toList().sorted()

    fun status(): Map<String, DeviceRecoveryStatus> = waiting.associateWith { vid ->
        attempts[vid] ?: DeviceRecoveryStatus()
    }

    private suspend fun retryPending() {
        waiting.toList().forEach { vid ->
            if (pending[vid]?.isActive != true) request(vid)
        }
    }

    private suspend fun recover(vid: String): String? {
        val account = WeReadAccountPool.get(vid) ?: return null
        val slot = chooseSlot(account)
        if (slot == null) {
            if ((attempts[vid]?.attempts ?: 0) == 0) notifyManual(account)
            return "没有可用的 ADB 设备槽位"
        }
        // 升级为物理硬件级独占互斥锁，彻底避免同一手机主身与分身并发执行
        val hardwareKey = slot.hardwareSerial.ifBlank { slot.serial }
        val lock = deviceLocks.computeIfAbsent(hardwareKey) { Mutex() }
        return lock.withLock {
            val login = WeReadWebAuth.createLogin() ?: return@withLock "创建微信读书登录二维码失败"
            val qrFile = saveQrCode(login.second, vid) ?: return@withLock "保存微信读书登录二维码失败"
            
            // 采用增强的推码与前置清理机制
            val pushResult = operations.pushQrCode(slot, qrFile)
            if (!pushResult.success) {
                logger.warn("微信读书二维码推送失败: vid=$vid, serial=${slot.serial}, user=${slot.userId}")
                return@withLock "推送二维码到手机失败"
            }

            try {
                // 双轨调度：如果是老版本环境且安装了 AAOS，走经典 AAOS 无障碍全自动路线；
                // 现代环境（Android 14 / HyperOS / 微信 8.0.76+）走坐标穿透管线
                val scanStarted = if (slot.isLegacyRoute && operations.isReady(slot)) {
                    logger.info("设备命中经典 AAOS 路线，启动无障碍全自动登录: vid=$vid, serial=${slot.serial}")
                    operations.startWeReadLogin(slot)
                } else {
                    logger.info("设备执行现代坐标穿透自绘扫码管线: vid=$vid, serial=${slot.serial}")
                    operations.performWeReadScanAndConfirm(slot, isWeb = true)
                }
                if (!scanStarted) {
                    logger.warn("微信读书自动扫码流程启动失败: vid=$vid, serial=${slot.serial}, user=${slot.userId}")
                    return@withLock "自动扫码与点击流程启动失败"
                }

                val credentials = WeReadWebAuth.pollLoginCredentials(login.first, timeoutSeconds = 60)
                if (credentials == null) {
                    logger.warn("微信读书自动恢复扫码超时: vid=$vid")
                    return@withLock "ADB 扫码登录超时"
                }
                if (credentials.wrVid != vid) {
                    logger.warn("扫码账号与目标账号不匹配，拒绝写入: target=$vid, scanned=${credentials.wrVid}")
                    return@withLock "扫码账号与目标账号不匹配"
                }
                if (!WeReadAccountPool.completeRecovery(vid, credentials, slot.serial, slot.userId)) {
                    return@withLock "恢复凭证持久化失败"
                }
                logger.info("微信读书账号自动恢复成功: vid=$vid, slotKey=${slot.slotKey}")
                null
            } finally {
                // 1. 严格点击左上角叉叉关闭 WebView 并复位回微信根页面 LauncherUI
                runCatching {
                    operations.dismissAuthorizationAndReset(slot)
                }.onFailure {
                    logger.warn("复位微信前台失败: vid=$vid, ${it.message}")
                }
                // 2. 严格阅后即焚清理临时图片
                operations.cleanupQrCode(slot, pushResult.destinationPath)
                qrFile.delete()
            }
        }
    }

    private fun recordFailure(vid: String, reason: String) {
        if (WeReadAccountPool.get(vid) == null) {
            waiting.remove(vid)
            attempts.remove(vid)
            return
        }
        val previous = attempts[vid]?.attempts ?: 0
        val current = previous + 1
        val exponent = (current - 1).coerceIn(0, 20)
        val initialDelay = AppConfig.adbPollIntervalMs
        val delayMs = runCatching { Math.multiplyExact(initialDelay, 1L shl exponent) }
            .getOrDefault(AppConfig.adbRecoveryMaxDelayMs)
            .coerceAtMost(AppConfig.adbRecoveryMaxDelayMs)
        attempts[vid] = DeviceRecoveryStatus(
            attempts = current,
            lastError = reason.take(500),
            nextAttemptAt = System.currentTimeMillis() + delayMs,
        )
        logger.warn("微信读书设备恢复失败，已退避: vid=$vid, attempt=$current, delayMs=$delayMs, reason=$reason")
    }

    internal fun chooseSlot(account: WeReadAccount): DeviceSlot? {
        // 优先通过硬件永久指纹 (ro.serialno) + 用户空间查找，解决无线/重新连接后 serial 变化问题
        if (account.boundSerial.isNotBlank()) {
            supervisor.findSlotByHardware(account.boundSerial, account.boundUserId)?.let { return it }
        }
        val snapshot = supervisor.snapshot()
        val exact = snapshot.devices
            .firstOrNull { it.serial == account.boundSerial && account.boundUserId in it.userIds }
            ?.let { DeviceSlot(it.serial, account.boundUserId, it.hardwareSerial) }
        if (exact != null) return exact
        // 未绑定槽位的新账号或弹性设备策略均可借用首个可用在线槽位
        if (account.boundSerial.isBlank() || account.bindingStrategy == "ELASTIC_DEVICE") {
            return supervisor.availableSlots().firstOrNull()
        }
        return null
    }

    private fun saveQrCode(scanUrl: String, vid: String): File? {
        val bytes = WeReadWebAuth.generateQrJpeg(scanUrl) ?: return null
        return runCatching {
            File("qrcode").apply { mkdirs() }
                .resolve("weread_${vid}.jpg")
                .apply { writeBytes(bytes) }
        }.getOrNull()
    }

    private suspend fun notifyManual(account: WeReadAccount) {
        // 当 ADB 设备离线或无可用槽位时，回退至生成带图二维码通知供人工扫码
        val started = runCatching {
            WeReadWebAuth.startLogin(targetVid = account.vid, requireNotification = true)
        }.getOrDefault(false)
        if (!started) {
            WebhookNotifier.notify("weread_login_qr", mapOf(
                "message" to "微信读书账号等待设备恢复或人工扫码（无可用 ADB 设备）",
                "vid" to account.vid,
                "bound_serial" to account.boundSerial,
            ))
        }
    }
}
