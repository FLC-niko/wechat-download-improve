package com.wamp.download.weread

import com.wamp.download.adb.AdbUserOperations
import com.wamp.download.adb.DeviceSlot
import com.wamp.download.adb.AdbDeviceSupervisor
import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/** 账号失效后的设备路由、扫码编排和热插拔重试。 */
class DeviceRecoveryManager(
    private val supervisor: AdbDeviceSupervisor,
    private val operations: AdbUserOperations = AdbUserOperations(),
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(DeviceRecoveryManager::class.java)
    private val deviceLocks = ConcurrentHashMap<String, Mutex>()
    private val pending = ConcurrentHashMap<String, Job>()
    private val waiting = ConcurrentHashMap.newKeySet<String>()

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(AppConfig.adbPollIntervalMs)
                supervisor.refresh()
                retryPending()
            }
        }
    }

    fun request(vid: String) {
        waiting.add(vid)
        if (pending[vid]?.isActive == true) return
        pending[vid] = scope.launch(Dispatchers.IO) {
            try {
                recover(vid)
            } finally {
                pending.remove(vid)
            }
        }
    }

    fun pendingVids(): List<String> = waiting.toList().sorted()

    private suspend fun retryPending() {
        waiting.toList().forEach { vid ->
            if (pending[vid]?.isActive != true) request(vid)
        }
    }

    private suspend fun recover(vid: String) {
        val account = WeReadAccountPool.get(vid) ?: return
        val slot = chooseSlot(account)
        if (slot == null) {
            notifyManual(account)
            delay(AppConfig.adbPollIntervalMs)
            return
        }
        val lock = deviceLocks.computeIfAbsent(slot.serial) { Mutex() }
        lock.withLock {
            val login = WeReadWebAuth.createLogin() ?: return@withLock
            val qrFile = saveQrCode(login.second, vid) ?: return@withLock
            if (!operations.isReady(slot) || !operations.pushQrCode(slot, qrFile) || !operations.startWeReadLogin(slot)) {
                logger.warn("微信读书自动恢复无法启动 AAOS: vid=$vid, serial=${slot.serial}, user=${slot.userId}")
                return@withLock
            }
            val credentials = WeReadWebAuth.pollLoginCredentials(login.first)
            if (credentials == null) {
                logger.warn("微信读书自动恢复扫码超时: vid=$vid")
                return@withLock
            }
            if (credentials.wrVid != vid) {
                logger.warn("扫码账号与目标账号不匹配，拒绝写入: target=$vid, scanned=${credentials.wrVid}")
                return@withLock
            }
            WeReadAccountPool.updateCredentials(vid, credentials)
            val updated = WeReadAccountPool.get(vid)
            if (updated != null) {
                updated.status = AccountStatus.ACTIVE
                updated.disabledReason = ""
                updated.boundSerial = slot.serial
                updated.boundUserId = slot.userId
                WeReadAccountPool.saveForRecovery(updated)
            }
            waiting.remove(vid)
            logger.info("微信读书账号自动恢复成功: vid=$vid, serial=${slot.serial}, user=${slot.userId}")
        }
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