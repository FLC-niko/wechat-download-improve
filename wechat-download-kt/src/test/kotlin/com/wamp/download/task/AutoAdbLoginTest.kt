package com.wamp.download.task

import com.wamp.download.weread.WeReadAccountPool
import com.wamp.download.weread.WeReadWebAuth
import com.wamp.download.weread.WeReadCookieStore
import com.wamp.download.config.AppConfig
import com.wamp.download.store.Database
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.FileSystems
import kotlin.test.assertTrue

class AutoAdbLoginTest {

    private val deviceId = "192.168.124.13:38711"

    private fun runAdb(cmd: String): String {
        val fullCmd = "adb -s $deviceId $cmd"
        println("[ADB] > $fullCmd")
        val proc = ProcessBuilder("sh", "-c", fullCmd).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return text.trim()
    }

    @Test
    fun `测试自动扫码完整链路`() {
        println("===== [1] 初始化系统配置与账号池 =====")
        AppConfig.load()
        Database.init()
        WeReadAccountPool.loadAll()

        println("===== [2] 向微信读书服务端申请登录二维码 =====")
        val loginPair = WeReadWebAuth.createLogin()
        assertTrue(loginPair != null, "获取登录二维码失败")
        val (uid, scanUrl) = loginPair
        println("获取到 UID: $uid")
        println("扫描确认链接: $scanUrl")

        println("===== [3] 生成本地二维码图片 =====")
        val qrDir = File("qrcode").apply { mkdirs() }
        val qrFile = File(qrDir, "weread_login_$uid.png")
        
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(scanUrl, BarcodeFormat.QR_CODE, 400, 400)
        val path = FileSystems.getDefault().getPath(qrFile.absolutePath)
        MatrixToImageWriter.writeToPath(bitMatrix, "PNG", path)
        println("二维码已保存到: ${qrFile.absolutePath}")

        println("===== [4] 通过 ADB 将二维码 Push 到手机相册 =====")
        val phonePath = "/sdcard/DCIM/Camera/weread_login_$uid.png"
        runAdb("push ${qrFile.absolutePath} $phonePath")

        // 广播通知图库索引新照片
        runAdb("shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$phonePath")
        Thread.sleep(1000)

        println("===== [5] 启动微信扫一扫 (ScanQrActivity) =====")
        // 唤醒屏幕
        runAdb("shell input keyevent 224")
        // 解锁（向上滑一下）
        runAdb("shell input swipe 540 1800 540 600 200")
        Thread.sleep(800)

        // 启动微信扫一扫界面
        val launchResult = runAdb("shell am start -n com.tencent.mm/.plugin.scanner.ui.BaseScanUI")
        println("启动扫一扫结果: $launchResult")
        Thread.sleep(2000)

        println("===== [6] 开始异步长轮询登录结果，同时给手机端操作预留时间 =====")
        // 启动后台线程轮询结果
        var loginSuccess = false
        val pollThread = Thread {
            println("[PollThread] 开始长轮询微信读书登录状态...")
            loginSuccess = WeReadWebAuth.pollLogin(uid, timeoutSeconds = 60)
            println("[PollThread] 长轮询结果: loginSuccess=$loginSuccess")
        }
        pollThread.start()

        pollThread.join(65000)

        println("===== [7] 清理手机和本地二维码文件 =====")
        runAdb("shell rm -f $phonePath")
        runAdb("shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$phonePath")
        qrFile.delete()

        println("===== [8] 检查最终登录状态与账号池 =====")
        WeReadAccountPool.loadAll()
        val primary = WeReadAccountPool.primaryAccount()
        println("当前主账号: vid=${primary?.vid}, status=${primary?.status}, wr_skey=${primary?.credentials?.wrSkey}")
    }
}
