package com.wamp.download.adb

import com.wamp.download.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * ADB 设备生命周期管理器。
 *
 * 设备序列号来自 `adb devices -l`，不会写死 USB 序列号或 IP 地址。
 * 只有状态为 `device` 的目标才会进入可恢复设备池；offline/unauthorized
 * 设备只记录日志，不参与自动扫码，避免恢复流程被阻塞。
 */
class AdbDeviceSupervisor(
    private val commandRunner: AdbCommandRunner = ProcessAdbCommandRunner(),
    private val pollIntervalMs: Long = AppConfig.adbPollIntervalMs,
) {
    private val logger = LoggerFactory.getLogger(AdbDeviceSupervisor::class.java)
    private val devices = ConcurrentHashMap<String, AdbDevice>()
    private val deviceLocks = ConcurrentHashMap<String, Mutex>()
    @Volatile
    private var recoveryMode: RecoveryMode = RecoveryMode.MANUAL_ONLY
    private var watcherJob: Job? = null

    /** 获取物理设备级别的独占互斥锁，彻底避免同机主身/分身或 Web/Eink 自动代扫并发冲突 */
    fun getDeviceLock(slot: DeviceSlot): Mutex {
        val key = slot.hardwareSerial.ifBlank { slot.serial }
        return deviceLocks.computeIfAbsent(key) { Mutex() }
    }

    fun start(scope: CoroutineScope) {
        if (watcherJob?.isActive == true) return
        watcherJob = scope.launch(Dispatchers.IO) {
            logger.info("ADB 设备监督器已启动（探查间隔: ${pollIntervalMs}ms）")
            while (isActive) {
                refresh()
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        watcherJob?.cancel()
        watcherJob = null
    }

    /** 立即刷新一次，便于启动时同步决定当前恢复模式。 */
    fun refresh(): DeviceSnapshot {
        val discovered = runCatching { discover() }
            .onFailure { logger.warn("ADB 设备探查失败: ${it.message}") }
            .getOrDefault(emptyList())

        val previousSerials = devices.keys.toSet()
        devices.clear()
        discovered.forEach { devices[it.serial] = it }

        val currentSerials = discovered.map { it.serial }.toSet()
        (currentSerials - previousSerials).forEach { serial ->
            logger.info("检测到 ADB 设备接入: $serial, users=${devices[serial]?.userIds}")
        }
        (previousSerials - currentSerials).forEach { serial ->
            logger.warn("检测到 ADB 设备断开: $serial")
        }

        val nextMode = if (discovered.isNotEmpty()) {
            RecoveryMode.AUTO_RECOVERABLE
        } else {
            RecoveryMode.MANUAL_ONLY
        }
        if (nextMode != recoveryMode) {
            logger.info("恢复模式切换: $recoveryMode -> $nextMode（在线设备: ${discovered.size}）")
            recoveryMode = nextMode
        }
        return snapshot()
    }

    fun snapshot(): DeviceSnapshot = DeviceSnapshot(
        mode = recoveryMode,
        devices = devices.values.sortedBy { it.serial },
    )

    fun isOnline(serial: String): Boolean = devices[serial] != null

    fun hasOnlineDevice(): Boolean = devices.isNotEmpty()

    /** 返回在线设备的 User 0 / XSpace 槽位，包含真实硬件序列号映射及版本特征。 */
    fun availableSlots(): List<DeviceSlot> = devices.values.flatMap { device ->
        device.userIds.map { userId ->
            DeviceSlot(
                serial = device.serial,
                userId = userId,
                hardwareSerial = device.hardwareSerial,
                sdkVersion = device.sdkVersion,
                wechatVersion = device.wechatVersion,
            )
        }
    }

    /** 通过硬件序列号（ro.serialno）查找在线槽位。 */
    fun findSlotByHardware(hardwareSerial: String, userId: Int): DeviceSlot? {
        if (hardwareSerial.isBlank()) return null
        return devices.values
            .firstOrNull { it.hardwareSerial == hardwareSerial && userId in it.userIds }
            ?.let {
                DeviceSlot(
                    serial = it.serial,
                    userId = userId,
                    hardwareSerial = it.hardwareSerial,
                    sdkVersion = it.sdkVersion,
                    wechatVersion = it.wechatVersion,
                )
            }
    }

    private fun discover(): List<AdbDevice> {
        val result = commandRunner.run(listOf("devices", "-l"))
        if (result.exitCode != 0) {
            throw IOException(result.stderr.ifBlank { "adb devices 执行失败(${result.exitCode})" })
        }
        return result.stdout.lineSequence()
            .drop(1)
            .mapNotNull { parseDeviceLine(it) }
            .map { device ->
                val users = detectUsers(device.serial)
                val hwSerial = detectHardwareSerial(device.serial)
                val sdkVer = detectSdkVersion(device.serial)
                val wxVer = detectWechatVersion(device.serial)
                device.copy(
                    userIds = users,
                    hardwareSerial = hwSerial,
                    sdkVersion = sdkVer,
                    wechatVersion = wxVer,
                )
            }
            .toList()
    }

    fun detectSdkVersion(serial: String): Int {
        val result = runCatching { commandRunner.run(listOf("-s", serial, "shell", "getprop", "ro.build.version.sdk")) }.getOrNull()
        return if (result?.exitCode == 0) result.stdout.trim().toIntOrNull() ?: 0 else 0
    }

    fun detectWechatVersion(serial: String): String {
        val result = runCatching { commandRunner.run(listOf("-s", serial, "shell", "dumpsys", "package", "com.tencent.mm")) }.getOrNull()
        if (result?.exitCode != 0) return ""
        val match = WECHAT_VERSION_NAME_REGEX.find(result.stdout)
        return match?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    private fun detectHardwareSerial(serial: String): String {
        val result = runCatching { commandRunner.run(listOf("-s", serial, "shell", "getprop", "ro.serialno")) }.getOrNull()
        return if (result?.exitCode == 0) result.stdout.trim().ifBlank { serial } else serial
    }

    private fun detectUsers(serial: String): List<Int> {
        val result = runCatching { commandRunner.run(listOf("-s", serial, "shell", "pm", "list", "users")) }.getOrNull()
        if (result == null || result.exitCode != 0) {
            logger.warn("无法探测设备用户空间: serial=$serial, ${result?.stderr}")
            return listOf(0)
        }
        val ids = USER_ID_REGEX.findAll(result.stdout)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .toMutableSet()
        ids.add(0)
        return ids.sorted()
    }

    private fun parseDeviceLine(line: String): AdbDevice? {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 2 || fields[1] != "device") return null
        return AdbDevice(serial = fields[0], userIds = listOf(0))
    }

    companion object {
        private val USER_ID_REGEX = Regex("UserInfo\\{(\\d+):")
        private val WECHAT_VERSION_NAME_REGEX = Regex("""versionName=([^\s]+)""")

        /**
         * 语义化版本号比较：v1 < v2 返回负数，v1 == v2 返回 0，v1 > v2 返回正数。
         */
        fun compareWechatVersion(v1: String, v2: String): Int {
            if (v1.isBlank() && v2.isBlank()) return 0
            if (v1.isBlank()) return -1
            if (v2.isBlank()) return 1
            val parts1 = v1.split(".").mapNotNull { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull()
            }
            val parts2 = v2.split(".").mapNotNull { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull()
            }
            val maxLen = maxOf(parts1.size, parts2.size)
            for (i in 0 until maxLen) {
                val num1 = parts1.getOrElse(i) { 0 }
                val num2 = parts2.getOrElse(i) { 0 }
                if (num1 != num2) {
                    return num1.compareTo(num2)
                }
            }
            return 0
        }
    }
}

enum class RecoveryMode {
    AUTO_RECOVERABLE,
    MANUAL_ONLY,
}

data class AdbDevice(
    val serial: String,
    val userIds: List<Int>,
    val hardwareSerial: String = "",
    val sdkVersion: Int = 0,
    val wechatVersion: String = "",
) {
    val isLegacyWechat: Boolean get() = wechatVersion.isNotBlank() && AdbDeviceSupervisor.compareWechatVersion(wechatVersion, "8.0.40") < 0
    val isLegacyAndroid: Boolean get() = sdkVersion in 1..29
    val isLegacyRoute: Boolean get() = isLegacyWechat || isLegacyAndroid
}

data class DeviceSlot(
    val serial: String,
    val userId: Int,
    val hardwareSerial: String = "",
    val sdkVersion: Int = 0,
    val wechatVersion: String = "",
) {
    /** 物理硬件 + 多用户复合唯一键，例如 M7Z5HABUMJT8BUO7#0 或 M7Z5HABUMJT8BUO7#999 */
    val slotKey: String get() = "${hardwareSerial.ifBlank { serial }}#$userId"

    val isLegacyWechat: Boolean get() = wechatVersion.isNotBlank() && AdbDeviceSupervisor.compareWechatVersion(wechatVersion, "8.0.40") < 0
    val isLegacyAndroid: Boolean get() = sdkVersion in 1..29
    val isLegacyRoute: Boolean get() = isLegacyWechat || isLegacyAndroid
}

data class DeviceSnapshot(
    val mode: RecoveryMode,
    val devices: List<AdbDevice>,
)

data class AdbCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

fun interface AdbCommandRunner {
    fun run(arguments: List<String>): AdbCommandResult
}

class ProcessAdbCommandRunner : AdbCommandRunner {
    override fun run(arguments: List<String>): AdbCommandResult {
        val process = ProcessBuilder(listOf("adb") + arguments)
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return AdbCommandResult(exitCode, stdout, stderr)
    }
}
