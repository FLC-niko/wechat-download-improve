package com.wamp.download.adb

import java.io.File
import java.util.concurrent.TimeUnit

/** 对指定物理设备和 Android 用户执行的最小 ADB 操作集合。 */
class AdbUserOperations(
    private val commandRunner: AdbCommandRunner = ProcessAdbCommandRunner(),
    private val aaosPackage: String = "com.topviewclub.crawling",
    private val aaosActivity: String = "com.topviewclub.crawling/.core.ui.CrawlingActivity",
) {
    fun pushQrCode(slot: DeviceSlot, localFile: File): Boolean {
        if (!localFile.isFile) return false
        val push = run(listOf("-s", slot.serial, "push", localFile.absolutePath, "/data/local/tmp/weread_qr.jpg"))
        if (push.exitCode != 0) return false
        val destination = "/storage/emulated/${slot.userId}/Pictures/QRCode/weread_qr.jpg"
        val copy = run(listOf(
            "-s", slot.serial, "shell", "sh", "-c",
            "mkdir -p /storage/emulated/${slot.userId}/Pictures/QRCode && " +
                "cp /data/local/tmp/weread_qr.jpg $destination && " +
                "chmod 644 $destination",
        ))
        if (copy.exitCode != 0) return false
        val scan = run(listOf(
            "-s", slot.serial, "shell", "am", "broadcast", "--user", slot.userId.toString(),
            "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d", "file://$destination",
        ))
        return scan.exitCode == 0
    }

    fun startWeReadLogin(slot: DeviceSlot): Boolean {
        val wake = run(listOf("-s", slot.serial, "shell", "input", "keyevent", "224"))
        val start = run(listOf(
            "-s", slot.serial, "shell", "am", "start", "--user", slot.userId.toString(),
            "-n", aaosActivity,
            "--es", "task_type", "weread_login",
        ))
        return wake.exitCode == 0 && start.exitCode == 0
    }

    fun isReady(slot: DeviceSlot): Boolean = run(listOf(
        "-s", slot.serial, "shell", "pm", "path", aaosPackage,
    )).exitCode == 0

    private fun run(arguments: List<String>): AdbCommandResult = commandRunner.run(arguments)
}