package com.wamp.download.adb

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 对指定物理设备和 Android 用户执行的最小 ADB 操作集合。 */
class AdbUserOperations(
    private val commandRunner: AdbCommandRunner = ProcessAdbCommandRunner(),
    private val aaosPackage: String = "com.topviewclub.crawling",
    private val aaosActivity: String = "com.topviewclub.crawling/.core.ui.CrawlingActivity",
) {
    companion object {
        // 关键按钮的屏幕比率坐标（多机型自适应，对齐 1080x2460、1200x2670 等不同分辨率）
        // 1. 扫一扫右下角相册图标中心比率 (1080x2460 -> 956, 2098)
        const val RATIO_ALBUM_ICON_X = 0.885f
        const val RATIO_ALBUM_ICON_Y = 0.853f

        // 2. 相册选图第二格相片中心比率 (第一格固定为“拍摄照片”，1080x2460 -> 405, 365)
        const val RATIO_FIRST_PHOTO_X = 0.375f
        const val RATIO_FIRST_PHOTO_Y = 0.148f

        // 3. Web 登录授权确认页中心按钮比率 (1080x2460 -> 540, 2087)
        const val RATIO_CONFIRM_BTN_X = 0.500f
        const val RATIO_CONFIRM_BTN_Y = 0.848f

        // 4. MMWebViewUI 左上角关闭叉叉比率 (1080x2460 -> 60, 135)
        const val RATIO_CLOSE_BTN_X = 0.055f
        const val RATIO_CLOSE_BTN_Y = 0.055f

        // 5. Eink 授权弹窗中间确认位置覆盖比率
        const val RATIO_EINK_MID_BTN_Y = 0.610f
    }

    private val screenSizeCache = ConcurrentHashMap<String, Pair<Int, Int>>()

    /** 动态获取设备物理屏幕分辨率，带缓存。 */
    fun getScreenSize(slot: DeviceSlot): Pair<Int, Int> {
        return screenSizeCache.computeIfAbsent(slot.serial) { serial ->
            val res = run(listOf("-s", serial, "shell", "wm", "size"))
            if (res.exitCode == 0) {
                val match = Regex("""(?:Physical size|Override size):\s*(\d+)x(\d+)""").find(res.stdout)
                if (match != null) {
                    val w = match.groupValues[1].toIntOrNull() ?: 1080
                    val h = match.groupValues[2].toIntOrNull() ?: 2460
                    return@computeIfAbsent w to h
                }
            }
            1080 to 2460
        }
    }

    /** 基于屏幕比例动态自适应点击，跨设备分辨率通用。 */
    fun tapRatio(slot: DeviceSlot, xRatio: Float, yRatio: Float): AdbCommandResult {
        val (w, h) = getScreenSize(slot)
        val x = (w * xRatio).toInt().coerceIn(0, w)
        val y = (h * yRatio).toInt().coerceIn(0, h)
        return run(listOf("-s", slot.serial, "shell", "input", "tap", x.toString(), y.toString()))
    }
    /** 推送新鲜二维码至隔离目录，前置清空历史图片以防错选。支持老版 AAOS 与现代 Android 14 双轨适配。 */
    fun pushQrCode(slot: DeviceSlot, localFile: File): FilePushResult {
        if (!localFile.isFile) return FilePushResult(false, "")
        
        // 双轨目录适配：老版遵循经典 AAOS 既定文档路由 Pictures/QRCode；新版走 DCIM/Camera 或隔离相册
        val subDir = when {
            slot.isLegacyRoute -> "Pictures/QRCode"
            slot.userId == 0 -> "DCIM/Camera"
            else -> "Pictures/QRCode_u${slot.userId}"
        }
        val userPath = "/storage/emulated/${slot.userId}/$subDir"
        val ext = localFile.extension.ifBlank { "png" }
        val fileName = if (slot.isLegacyRoute) "weread_qr.$ext" else "weread_${System.currentTimeMillis()}.$ext"
        val destination = "$userPath/$fileName"

        // 前置清理：清空该目录下历史推送的二维码图片
        run(listOf("-s", slot.serial, "shell", "rm", "-f", "$userPath/weread_*.$ext", "$userPath/weread_*.png", "$userPath/weread_*.jpg"))

        // 老版本既定路线同时清理并同步至经典 AAOS 内部专用目录：/sdcard/Android/media/com.topviewclub.crawling/.../QRCode
        if (slot.isLegacyRoute) {
            val legacyAaosDir = "/storage/emulated/${slot.userId}/Android/media/$aaosPackage/$aaosPackage/QRCode"
            run(listOf("-s", slot.serial, "shell", "rm", "-f", "$legacyAaosDir/*"))
            run(listOf("-s", slot.serial, "shell", "mkdir", "-p", legacyAaosDir))
            run(listOf("-s", slot.serial, "push", localFile.absolutePath, "$legacyAaosDir/weread_qr.$ext"))
        }

        // 创建目录并推送
        run(listOf("-s", slot.serial, "shell", "mkdir", "-p", userPath))
        val push = run(listOf("-s", slot.serial, "push", localFile.absolutePath, destination))
        if (push.exitCode != 0) return FilePushResult(false, "")

        // 广播刷新相册媒体库
        val scan = run(listOf(
            "-s", slot.serial, "shell", "am", "broadcast", "--user", slot.userId.toString(),
            "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d", "file://$destination",
        ))
        return FilePushResult(scan.exitCode == 0, destination)
    }

    /** 阅后即焚清理临时图片，通过 MediaScanner 广播自动移除 MediaStore 索引 */
    fun cleanupQrCode(slot: DeviceSlot, destination: String): Boolean {
        if (destination.isBlank()) return false
        val delFile = run(listOf("-s", slot.serial, "shell", "rm", "-f", destination))
        // 文件被删除后通知 MediaScanner，系统会自动从 MediaStore 移除脏索引，无需危险的 content delete
        run(listOf(
            "-s", slot.serial, "shell", "am", "broadcast", "--user", slot.userId.toString(),
            "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d", "file://$destination",
        ))
        if (slot.isLegacyRoute) {
            val legacyAaosDir = "/storage/emulated/${slot.userId}/Android/media/$aaosPackage/$aaosPackage/QRCode"
            run(listOf("-s", slot.serial, "shell", "rm", "-f", "$legacyAaosDir/*"))
        }
        return delFile.exitCode == 0
    }

    /**
     * 自动调起微信扫一扫与选图、点击授权全流程
     * 
     * 参考 AAOS 代码：使用显式 Shortcut VIEW Intent 直达 BaseScanUI，杜绝任何页面点歪。
     */
    fun performWeReadScanAndConfirm(slot: DeviceSlot, isWeb: Boolean = true): Boolean {
        // 1. 唤醒屏幕
        run(listOf("-s", slot.serial, "shell", "input", "keyevent", "224"))
        
        // 2. 直达微信扫一扫活动 BaseScanUI
        val startScan = run(listOf(
            "-s", slot.serial, "shell", "am", "start", "--user", slot.userId.toString(),
            "-a", "android.intent.action.VIEW",
            "-n", "com.tencent.mm/.ui.LauncherUI",
            "--ez", "LauncherUI.From.Scaner.Shortcut", "true",
            "-f", "0x14000000",
        ))
        if (startScan.exitCode != 0) return false
        Thread.sleep(1200)

        // 3. 点击右下角相册图标 (自适应比率点击)
        tapRatio(slot, RATIO_ALBUM_ICON_X, RATIO_ALBUM_ICON_Y)
        Thread.sleep(1500)

        // 4. 选中第一张新鲜二维码（第二格相片自适应比率点击）
        tapRatio(slot, RATIO_FIRST_PHOTO_X, RATIO_FIRST_PHOTO_Y)
        Thread.sleep(3500)

        // 5. 自动点击确认授权 (自适应比率点击)
        if (isWeb) {
            // Web 登录确认页中心点击，首击后加二次防抖点击以防网络慢
            tapRatio(slot, RATIO_CONFIRM_BTN_X, RATIO_CONFIRM_BTN_Y)
            Thread.sleep(1300)
            tapRatio(slot, RATIO_CONFIRM_BTN_X, RATIO_CONFIRM_BTN_Y)
        } else {
            // Eink 授权弹窗位置覆盖
            tapRatio(slot, RATIO_CONFIRM_BTN_X, RATIO_CONFIRM_BTN_Y)
            Thread.sleep(500)
            tapRatio(slot, RATIO_CONFIRM_BTN_X, RATIO_EINK_MID_BTN_Y)
        }
        Thread.sleep(1500)
        return true
    }

    /**
     * 关闭授权 WebView 页面并复位微信到主页，防止悬挂在 MMWebViewUI 导致后续扫码或采集任务失败。
     */
    fun dismissAuthorizationAndReset(slot: DeviceSlot): Boolean {
        // 点击 MMWebViewUI 左上角的关闭叉叉 (自适应比率点击)
        tapRatio(slot, RATIO_CLOSE_BTN_X, RATIO_CLOSE_BTN_Y)
        Thread.sleep(600)
        // 回退键兜底
        run(listOf("-s", slot.serial, "shell", "input", "keyevent", "4"))
        Thread.sleep(500)
        // 确保复位回到微信根主页 LauncherUI
        val reset = run(listOf(
            "-s", slot.serial, "shell", "am", "start", "--user", slot.userId.toString(),
            "-n", "com.tencent.mm/.ui.LauncherUI",
        ))
        return reset.exitCode == 0
    }

    /** 辅助启动 AAOS 应用 */
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

data class FilePushResult(
    val success: Boolean,
    val destinationPath: String,
)