package com.wamp.download.task

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.wamp.download.adb.AdbDeviceSupervisor
import com.wamp.download.adb.AdbUserOperations
import com.wamp.download.adb.DeviceSlot
import com.wamp.download.adb.ProcessAdbCommandRunner
import com.wamp.download.adb.RecoveryMode
import com.wamp.download.config.AppConfig
import com.wamp.download.store.Database
import com.wamp.download.weread.WeReadAccountPool
import com.wamp.download.weread.WeReadWebAuth
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.FileSystems
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VerifyAdbSchemeTest {

    private val adbPath: String = run {
        val standard = "/Users/halo/Library/Android/sdk/platform-tools/adb"
        if (File(standard).canExecute()) standard else "adb"
    }

    private fun adb(cmd: String): String {
        val fullCmd = "$adbPath -s M7Z5HABUMJT8BUO7 $cmd"
        println("[ADB] > $fullCmd")
        val proc = ProcessBuilder("sh", "-c", fullCmd).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return text.trim()
    }

    private fun captureScreenshot(destName: String): File {
        val phonePath = "/sdcard/$destName"
        adb("shell screencap -p $phonePath")
        val localFile = File("/tmp/$destName")
        adb("pull $phonePath ${localFile.absolutePath}")
        adb("shell rm -f $phonePath")
        println("[Screenshot] Saved to ${localFile.absolutePath} (${localFile.length()} bytes)")
        return localFile
    }

    @Test
    fun `验证方案A有ADB模式全链路`() {
        println("================================================================")
        println(">>> 阶段 1: 验证 AdbDeviceSupervisor 设备探查与多用户空间解析 <<<")
        println("================================================================")
        AppConfig.load()
        Database.init()
        WeReadAccountPool.loadAll()

        val supervisor = AdbDeviceSupervisor()
        val snapshot = supervisor.refresh()

        println("Supervisor 探查结果: mode=${snapshot.mode}, devices=${snapshot.devices.size}")
        assertEquals(RecoveryMode.AUTO_RECOVERABLE, snapshot.mode, "应自动切换至 AUTO_RECOVERABLE 模式")
        assertTrue(snapshot.devices.isNotEmpty(), "应探测到在线物理设备")

        val targetDevice = snapshot.devices.firstOrNull { it.serial == "M7Z5HABUMJT8BUO7" }
        assertNotNull(targetDevice, "应探测到设备 M7Z5HABUMJT8BUO7")
        println("检测到目标设备: serial=${targetDevice.serial}, hwSerial=${targetDevice.hardwareSerial}, users=${targetDevice.userIds}")
        assertTrue(targetDevice.userIds.contains(0), "设备应包含 User 0")

        val availableSlots = supervisor.availableSlots()
        println("可用槽位列表: ${availableSlots.map { it.slotKey }}")
        val slot0 = availableSlots.firstOrNull { it.serial == "M7Z5HABUMJT8BUO7" && it.userId == 0 }
        assertNotNull(slot0, "应有 M7Z5HABUMJT8BUO7#0 槽位")

        println("\n================================================================")
        println(">>> 阶段 2: 申请官方微信读书登录二维码并生成高清图片 <<<")
        println("================================================================")
        val loginPair = WeReadWebAuth.createLogin()
        assertNotNull(loginPair, "获取微信读书二维码 UID 失败")
        val (uid, scanUrl) = loginPair
        println("成功获得登录 UID: $uid")
        println("登录确认 URL: $scanUrl")

        val qrDir = File("qrcode").apply { mkdirs() }
        val qrFile = File(qrDir, "weread_verify_${uid}.png")
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(scanUrl, BarcodeFormat.QR_CODE, 600, 600)
        MatrixToImageWriter.writeToPath(bitMatrix, "PNG", FileSystems.getDefault().getPath(qrFile.absolutePath))
        println("本地二维码图片就绪: ${qrFile.absolutePath}")

        println("\n================================================================")
        println(">>> 阶段 3: 执行 AdbUserOperations 推送二维码并刷新媒体库 <<<")
        println("================================================================")
        val operations = AdbUserOperations()
        val pushResult = operations.pushQrCode(slot0, qrFile)
        println("推送结果: success=${pushResult.success}, destination=${pushResult.destinationPath}")
        assertTrue(pushResult.success, "推送二维码图片并广播媒体库失败")

        try {
            println("\n================================================================")
            println(">>> 阶段 4: 唤起微信扫一扫 (BaseScanUI) 并截图确认 <<<")
            println("================================================================")
            adb("shell input keyevent 224") // 唤醒屏幕
            val scanLaunched = adb("shell am start --user 0 -a android.intent.action.VIEW -n com.tencent.mm/.ui.LauncherUI --ez LauncherUI.From.Scaner.Shortcut true -f 0x14000000")
            println("拉起扫一扫: $scanLaunched")
            Thread.sleep(1500)
            captureScreenshot("verify_step1_scan.png")

            println("\n================================================================")
            println(">>> 阶段 5: 点击进入相册 (AlbumPreviewUI) 并截图确认 <<<")
            println("================================================================")
            // 根据 AAOS ClickAlbum.kt: 1080x2460 下相册按钮中心为 (956, 2135)
            adb("shell input tap 956 2135")
            Thread.sleep(1500)
            captureScreenshot("verify_step2_album.png")

            println("\n================================================================")
            println(">>> 阶段 6: 选中第一张图片 (首张二维码 405, 365) <<<")
            println("================================================================")
            // 根据 AAOS SelectPhoto.kt: 第一格为拍摄照片，首张图片位于 Column 2 Row 1 (405, 365)
            adb("shell input tap 405 365")
            Thread.sleep(3500)
            val confirmScreen = captureScreenshot("verify_step3_confirm.png")
            println("已加载确认页，截图保存在: ${confirmScreen.absolutePath}")

            println("\n================================================================")
            println(">>> 阶段 7: 点击【登录】确认按钮并异步轮询微信读书换票 <<<")
            println("================================================================")
            // 点击 MMWebViewUI 下方的【登录】按钮 (540, 2087)
            adb("shell input tap 540 2087")
            Thread.sleep(500)
            adb("shell input tap 540 2087") // 兜底再次确认

            val creds = WeReadWebAuth.pollLoginCredentials(uid, timeoutSeconds = 60)
            println("微信读书凭证轮询结果: creds=$creds")
            assertNotNull(creds, "微信读书长轮询换票超时或失败")
            println("换票成功！wr_vid=${creds.wrVid}, username=${creds.username}, skey=${creds.wrSkey.take(16)}...")

            println("\n================================================================")
            println(">>> 阶段 8: 凭证持久化与账号池状态更新 <<<")
            println("================================================================")
            val updateOk = WeReadAccountPool.completeRecovery(
                creds.wrVid,
                creds,
                slot0.serial,
                slot0.userId,
            )
            println("账号池恢复结果: updateOk=$updateOk")
            val recoveredAccount = WeReadAccountPool.get(creds.wrVid)
            println("恢复后账号状态: vid=${recoveredAccount?.vid}, status=${recoveredAccount?.status}, bound=${recoveredAccount?.boundSerial}#${recoveredAccount?.boundUserId}")
            assertEquals("ACTIVE", recoveredAccount?.status?.name)

        } finally {
            println("\n================================================================")
            println(">>> 阶段 9: 页面复位 (dismiss & reset) 与临时图片阅后即焚清理 <<<")
            println("================================================================")
            operations.dismissAuthorizationAndReset(slot0)
            Thread.sleep(1000)
            captureScreenshot("verify_step4_reset.png")

            val cleanupOk = operations.cleanupQrCode(slot0, pushResult.destinationPath)
            println("临时二维码清理: $cleanupOk")
            qrFile.delete()
            println("=== 全链路验证完成 ===")
        }
    }
}

