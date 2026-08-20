package com.wamp.download.task

import com.wamp.download.weread.WeReadAccountPool
import com.wamp.download.weread.WeReadWebAuth
import com.wamp.download.config.AppConfig
import com.wamp.download.store.Database
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.FileSystems
import kotlin.test.assertTrue

class FullAutoAdbScanTest {

    private val deviceId = "192.168.124.13:38711"

    private fun adb(cmd: String): String {
        val fullCmd = "adb -s $deviceId $cmd"
        val proc = ProcessBuilder("sh", "-c", fullCmd).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return text.trim()
    }

    @Test
    fun `执行完整微信读书自动扫码登录测试`() {
        println("===== [1] 初始化系统配置与数据库 =====")
        AppConfig.load()
        Database.init()
        WeReadAccountPool.loadAll()

        println("===== [2] 从微信读书获取新登录二维码 =====")
        val loginPair = WeReadWebAuth.createLogin()
        assertTrue(loginPair != null, "获取登录二维码失败")
        val (uid, scanUrl) = loginPair
        println("获取到 UID: $uid")
        println("登录 URL: $scanUrl")

        println("===== [3] 生成并 Push 二维码到手机相册 =====")
        val qrDir = File("qrcode").apply { mkdirs() }
        val qrFile = File(qrDir, "weread_login_$uid.png")
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(scanUrl, BarcodeFormat.QR_CODE, 450, 450)
        MatrixToImageWriter.writeToPath(bitMatrix, "PNG", FileSystems.getDefault().getPath(qrFile.absolutePath))
        
        val phonePath = "/sdcard/DCIM/Camera/weread_login_$uid.png"
        adb("push ${qrFile.absolutePath} $phonePath")
        adb("shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$phonePath")
        Thread.sleep(1000)

        println("===== [4] 使用官方 Shortcut 拉起扫一扫 =====")
        // 唤醒屏幕并向上滑动解锁
        adb("shell input keyevent 224")
        adb("shell input swipe 540 1800 540 600 200")
        Thread.sleep(500)
        
        // 启动扫一扫
        adb("shell am start -n com.tencent.mm/.ui.LauncherUI --ez \"LauncherUI.From.Scaner.Shortcut\" true -a android.intent.action.VIEW")
        Thread.sleep(1500)

        println("===== [5] 启动后台长轮询等待手机授权 =====")
        var loginOk = false
        val pollThread = Thread {
            println("[PollThread] 正在长轮询微信读书服务端授权状态...")
            loginOk = WeReadWebAuth.pollLogin(uid, timeoutSeconds = 60)
            println("[PollThread] 登录结果: $loginOk")
        }
        pollThread.start()

        println("\n>>> 请注意手机屏幕：微信扫一扫已打开！<<<")
        println(">>> 请在手机右下角点击【相册】并选择第一张刚 push 进去的二维码，并在手机上点击【确认登录】<<<")

        pollThread.join(65000)

        println("===== [6] 清理临时二维码 =====")
        adb("shell rm -f $phonePath")
        adb("shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$phonePath")
        qrFile.delete()

        println("===== [7] 验证账号池最新凭证状态 =====")
        WeReadAccountPool.loadAll()
        WeReadAccountPool.list().forEach {
            println(" -> 账号: vid=${it.vid}, username=${it.credentials.username}, status=${it.status}, skey=${it.credentials.wrSkey}")
        }
    }
}
