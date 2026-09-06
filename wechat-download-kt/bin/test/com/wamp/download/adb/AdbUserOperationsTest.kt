package com.wamp.download.adb

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdbUserOperationsTest {

    @Test
    fun `pushQrCode 正确清理旧图并使用匹配后缀推送与广播`() {
        val executed = mutableListOf<List<String>>()
        val runner = AdbCommandRunner { args ->
            executed.add(args)
            AdbCommandResult(0, "", "")
        }
        val ops = AdbUserOperations(runner)
        val tempFile = File.createTempFile("test_qr", ".jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }

        val slot = DeviceSlot(serial = "dev-1", userId = 0, hardwareSerial = "HW1")
        val result = ops.pushQrCode(slot, tempFile)

        assertTrue(result.success)
        assertTrue(result.destinationPath.startsWith("/storage/emulated/0/DCIM/Camera/weread_"))
        assertTrue(result.destinationPath.endsWith(".jpg"))

        // 验证没有执行违规危险的 content delete
        assertTrue(executed.none { it.contains("content") && it.contains("delete") })

        // 验证执行了清空旧图
        assertTrue(executed.any { it.contains("rm") && it.contains("/storage/emulated/0/DCIM/Camera/weread_*.jpg") })

        // 验证执行了 media scanner 广播
        assertTrue(executed.any {
            it.contains("am") && it.contains("broadcast") &&
                it.contains("android.intent.action.MEDIA_SCANNER_SCAN_FILE") &&
                it.contains("file://${result.destinationPath}")
        })
    }

    @Test
    fun `cleanupQrCode 删除文件并广播刷新媒体库`() {
        val executed = mutableListOf<List<String>>()
        val runner = AdbCommandRunner { args ->
            executed.add(args)
            AdbCommandResult(0, "", "")
        }
        val ops = AdbUserOperations(runner)
        val slot = DeviceSlot(serial = "dev-1", userId = 999, hardwareSerial = "HW1")
        val dest = "/storage/emulated/999/Pictures/QRCode_u999/weread_123.jpg"

        val success = ops.cleanupQrCode(slot, dest)

        assertTrue(success)
        assertEquals(2, executed.size)
        // 1. rm -f
        assertEquals(listOf("-s", "dev-1", "shell", "rm", "-f", dest), executed[0])
        // 2. broadcast
        assertEquals(
            listOf(
                "-s", "dev-1", "shell", "am", "broadcast", "--user", "999",
                "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                "-d", "file://$dest",
            ),
            executed[1],
        )
    }

    @Test
    fun `pushQrCode 老版本微信或老系统自动走 Pictures QRCode 与 AAOS 专用目录双重写入`() {
        val executed = mutableListOf<List<String>>()
        val runner = AdbCommandRunner { args ->
            executed.add(args)
            AdbCommandResult(0, "", "")
        }
        val ops = AdbUserOperations(runner)
        val tempFile = File.createTempFile("test_legacy_qr", ".png").apply {
            writeBytes(byteArrayOf(4, 5, 6))
            deleteOnExit()
        }

        // 模拟微信 8.0.18 或 SDK 28 的经典设备槽位
        val slot = DeviceSlot(
            serial = "legacy-dev",
            userId = 0,
            hardwareSerial = "LEGACY_HW",
            sdkVersion = 28,
            wechatVersion = "8.0.18",
        )
        assertTrue(slot.isLegacyRoute)

        val result = ops.pushQrCode(slot, tempFile)

        assertTrue(result.success)
        assertEquals("/storage/emulated/0/Pictures/QRCode/weread_qr.png", result.destinationPath)

        // 验证执行了经典 AAOS 内部专用目录的清理与同步
        val legacyAaosDir = "/storage/emulated/0/Android/media/com.topviewclub.crawling/com.topviewclub.crawling/QRCode"
        assertTrue(executed.any { it.contains("push") && it.contains("$legacyAaosDir/weread_qr.png") })
        assertTrue(executed.any { it.contains("push") && it.contains("/storage/emulated/0/Pictures/QRCode/weread_qr.png") })
    }

    @Test
    fun `版本感知逻辑正确判定老版本与现代版本`() {
        // 老版本判定 (8.0.18 < 8.0.40)
        assertTrue(AdbDeviceSupervisor.compareWechatVersion("8.0.18", "8.0.40") < 0)
        assertTrue(AdbDeviceSupervisor.compareWechatVersion("8.0.28", "8.0.40") < 0)

        // 现代版本判定 (8.0.76 > 8.0.40)
        assertTrue(AdbDeviceSupervisor.compareWechatVersion("8.0.76", "8.0.40") > 0)
        assertTrue(AdbDeviceSupervisor.compareWechatVersion("8.0.40", "8.0.40") == 0)

        // Slot 综合判定
        val legacySlot = DeviceSlot("d1", 0, "H1", sdkVersion = 28, wechatVersion = "8.0.18")
        val modernSlot = DeviceSlot("d2", 0, "H2", sdkVersion = 34, wechatVersion = "8.0.76")

        assertTrue(legacySlot.isLegacyRoute)
        assertTrue(!modernSlot.isLegacyRoute)
    }

    @Test
    fun `tapRatio 正确根据屏幕分辨率计算比例点击坐标`() {
        val executed = mutableListOf<List<String>>()
        val runner = AdbCommandRunner { args ->
            executed.add(args)
            if (args.contains("wm") && args.contains("size")) {
                AdbCommandResult(0, "Physical size: 1200x2670", "")
            } else {
                AdbCommandResult(0, "", "")
            }
        }
        val ops = AdbUserOperations(runner)
        val slot = DeviceSlot("dev-ratio", 0)

        // 验证分辨率获取
        val size = ops.getScreenSize(slot)
        assertEquals(1200 to 2670, size)

        // 验证 0.500f, 0.848f 在 1200x2670 屏幕下的计算结果
        // x = 1200 * 0.5 = 600
        // y = 2670 * 0.848 = 2264
        ops.tapRatio(slot, 0.500f, 0.848f)

        assertTrue(executed.any {
            it.contains("input") && it.contains("tap") && it.contains("600") && it.contains("2264")
        })
    }
}

