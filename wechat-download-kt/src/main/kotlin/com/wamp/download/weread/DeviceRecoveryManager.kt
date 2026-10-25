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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
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
    val operations: AdbUserOperations = AdbUserOperations(),
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(DeviceRecoveryManager::class.java)
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
        val account = WeReadAccountPool.get(vid)
        if (account == null || account.status != AccountStatus.EXPIRED) {
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

    internal suspend fun recover(vid: String): String? {
        val account = WeReadAccountPool.get(vid) ?: return null
        if (account.status == AccountStatus.HUMAN_VERIFY_REQUIRED) return null
        val slot = chooseSlot(account)
        if (slot == null) {
            if ((attempts[vid]?.attempts ?: 0) == 0) notifyManual(account)
            return "没有可用的 ADB 设备槽位"
        }
        // 升级为物理硬件级独占互斥锁，彻底避免同一手机主身与分身并发执行
        val lock = supervisor.getDeviceLock(slot)
        val failure = lock.withLock {
            val maxAttempts = 3
            var lastFailureReason = "ADB 扫码流程未完成"
            for (attempt in 1..maxAttempts) {
                val login = WeReadWebAuth.createLogin() ?: return@withLock "创建微信读书登录二维码失败"
                val qrFile = saveQrCode(login.second, vid) ?: return@withLock "保存微信读书登录二维码失败"

                val aaosLoginAvailable = AppConfig.adbAaosLoginEnabled &&
                    operations.isReady(slot) && operations.isAaosLoginServiceEnabled(slot)
                var destinationPath: String? = null
                try {
                    // 官方网页在显示二维码后立即发起 getLoginInfo 长轮询。
                    // 若先完成手机授权才监听，确认页会提示“登录失败，请重新扫码”。
                    val credentials = scanWhileListening(
                        listen = {
                            runInterruptible(Dispatchers.IO) {
                                WeReadWebAuth.pollLoginCredentials(
                                    login.first,
                                    timeoutSeconds = if (aaosLoginAvailable) AppConfig.adbAaosLoginPollTimeout else 120,
                                )
                            }
                        },
                        scan = {
                            val pushResult = operations.pushQrCode(slot, qrFile)
                            if (!pushResult.success) throw LoginScanFailure("推送二维码到手机失败")
                            destinationPath = pushResult.destinationPath
                            if (aaosLoginAvailable) {
                                logger.info("AAOS 无障碍链承接登录，下发 weread_login 任务: vid=$vid, serial=${slot.serial}")
                                if (!operations.startWeReadLogin(slot)) throw LoginScanFailure("自动扫码启动失败")
                            } else {
                                logger.info("设备执行现代坐标穿透自绘扫码管线 (第 $attempt 次): vid=$vid, serial=${slot.serial}")
                                when (operations.performWeReadScanAndConfirm(slot, isWeb = true)) {
                                    com.wamp.download.adb.ScanConfirmResult.LOGIN_FAILED_PAGE ->
                                        throw LoginScanFailure("页面提示登录失败重新扫码")
                                    com.wamp.download.adb.ScanConfirmResult.SUCCESS -> Unit
                                    else -> throw LoginScanFailure("自动扫码与点击流程启动失败")
                                }
                            }
                        },
                    )
                    if (credentials == null) {
                        logger.warn("微信读书自动恢复扫码超时: vid=$vid")
                        lastFailureReason = "ADB 扫码登录超时"
                        continue
                    }
                    if (credentials.wrVid != vid) {
                        logger.warn("扫码账号与目标账号不匹配，拒绝写入: target=$vid, scanned=${credentials.wrVid}")
                        return@withLock "扫码账号与目标账号不匹配"
                    }
                    if (!WeReadAccountPool.completeRecovery(vid, credentials, slot.serial, slot.userId)) {
                        return@withLock "恢复凭证持久化失败"
                    }
                    logger.info("微信读书账号自动恢复成功: vid=$vid, slotKey=${slot.slotKey}")
                    return@withLock null
                } catch (error: LoginScanFailure) {
                    lastFailureReason = error.message ?: "自动扫码失败"
                    logger.warn("微信读书扫码流程未完成: vid=$vid, attempt=$attempt, reason=$lastFailureReason")
                } finally {
                    // 1. 严格点击左上角叉叉关闭 WebView 并复位回微信根页面 LauncherUI
                    runCatching {
                        operations.dismissAuthorizationAndReset(slot)
                    }.onFailure {
                        logger.warn("复位微信前台失败: vid=$vid, ${it.message}")
                    }
                    // 2. 严格阅后即焚清理临时图片
                    destinationPath?.let { operations.cleanupQrCode(slot, it) }
                    qrFile.delete()
                }
            }
            lastFailureReason
        }
        if (failure != null) {
            logger.warn("ADB 自动恢复未完成，回退人工扫码二维码: vid=$vid, reason=$failure")
            notifyManual(account)
        }
        return failure
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
        // 未绑定槽位的新账号或弹性设备策略可借用在线槽位；
        // 但 FIXED_DEVICE 不能抢占已绑定给其他账号的物理槽位，避免扫码账号不匹配。
        if (account.boundSerial.isBlank() || account.bindingStrategy == "ELASTIC_DEVICE") {
            val claimed = if (account.bindingStrategy == "ELASTIC_DEVICE") {
                emptySet()
            } else {
                WeReadAccountPool.list()
                    .asSequence()
                    .filter { it.vid != account.vid && it.boundSerial.isNotBlank() }
                    .map { it.boundSerial to it.boundUserId }
                    .toSet()
            }
            return supervisor.availableSlots().firstOrNull { slot ->
                (slot.hardwareSerial.ifBlank { slot.serial } to slot.userId) !in claimed
            }
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

private class LoginScanFailure(message: String) : Exception(message)

/** Start the login listener before any phone authorization; cancel it on scan failure. */
internal suspend fun <T> scanWhileListening(
    listen: suspend () -> T,
    scan: suspend () -> Unit,
): T = coroutineScope {
    // The caller already runs on IO. Give the listener a different coroutine
    // dispatcher so runInterruptible(IO) dispatches instead of blocking this
    // undispatched startup until the HTTP long poll has timed out.
    val result = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { listen() }
    try {
        scan()
        result.await()
    } finally {
        result.cancel()
    }
}
