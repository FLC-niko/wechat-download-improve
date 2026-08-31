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
        val lock = deviceLocks.computeIfAbsent(slot.serial) { Mutex() }
        return lock.withLock {
            val login = WeReadWebAuth.createLogin() ?: return@withLock "创建微信读书登录二维码失败"
            val qrFile = saveQrCode(login.second, vid) ?: return@withLock "保存微信读书登录二维码失败"
            if (!operations.isReady(slot) || !operations.pushQrCode(slot, qrFile) || !operations.startWeReadLogin(slot)) {
                logger.warn("微信读书自动恢复无法启动 AAOS: vid=$vid, serial=${slot.serial}, user=${slot.userId}")
                return@withLock "ADB/AAOS 登录流程启动失败"
            }
            val credentials = WeReadWebAuth.pollLoginCredentials(login.first)
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
            logger.info("微信读书账号自动恢复成功: vid=$vid, serial=${slot.serial}, user=${slot.userId}")
            null
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

    private fun chooseSlot(account: WeReadAccount): DeviceSlot? {
        val snapshot = supervisor.snapshot()
        val exact = snapshot.devices
            .firstOrNull { it.serial == account.boundSerial && account.boundUserId in it.userIds }
            ?.let { DeviceSlot(it.serial, account.boundUserId) }
        if (exact != null) return exact
        if (account.bindingStrategy != "ELASTIC_DEVICE") return null
        return supervisor.availableSlots().firstOrNull()
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
        WebhookNotifier.notify("weread_login_qr", mapOf(
            "message" to "微信读书账号等待设备恢复或人工扫码",
            "vid" to account.vid,
            "bound_serial" to account.boundSerial,
        ))
    }
}
