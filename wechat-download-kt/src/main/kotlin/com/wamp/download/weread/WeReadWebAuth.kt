package com.wamp.download.weread

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.wamp.download.config.AppConfig
import com.wamp.download.notify.WebhookNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 微信读书官方 Web 端认证（2026 新版接口）
 *
 * 注意：官方登录接口已在 2026 改版，社区 2022 文档（/web/login/getuid + wr_skey 5400s 刷新）已过时。
 * 当前实测的新流程：
 * 1. GET  /api/auth/getLoginUid                    -> { uid }
 * 2. 二维码 URL: https://weread.qq.com/web/confirm?uid={uid}（推送到企业微信/飞书）
 * 3. GET  /api/auth/getLoginInfo?uid={uid}&otp=    -> 长轮询（服务端 60s），扫码后返回
 *    { succeed: true, logicCode: "...", accessToken, webLoginVid }
 * 4. 保存凭证：wr_vid = webLoginVid, wr_skey = accessToken，cookie 有效期 365 天
 * 5. GET  /api/userInfo?userVid={vid}              -> { value: { name } } 获取昵称
 *
 * 认证方式：请求头 x-vid / x-skey（新版前端）或 cookie wr_vid / wr_skey（旧接口兼容）
 * 凭证持久化到文件（风格对齐 AuthManager），复用 WeReadCookieStore。
 */
object WeReadWebAuth {
    enum class ReloginRequestResult {
        STARTED,
        AUTO_QR_DISABLED,
        ALREADY_RUNNING,
        BACKOFF,
        NO_ACCOUNT,
        NOT_ELIGIBLE,
        RECOVERY_UNAVAILABLE,
    }

    private val logger = LoggerFactory.getLogger(WeReadWebAuth::class.java)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)  // getLoginInfo 长轮询最长 60s
        .build()

    private val baseUrl = AppConfig.wereadWebBaseUrl

    // --- 登录状态 ---

    fun isLoggedIn(): Boolean = WeReadCookieStore.isLoggedIn()

    /** 重新扫码进行中标志（防重入：按账号隔离，每账号同时只发起一次） */
    private val reloginInProgress = ConcurrentHashMap<String, Boolean>()
    private val reloginFailureCount = ConcurrentHashMap<String, Int>()
    private val nextReloginAttemptAt = ConcurrentHashMap<String, Long>()

    /** 待扫码登录会话（E-Ink 式“有效期内复用”语义，避免每轮健康检查都发新码）。 */
    data class PendingWebLogin(val uid: String, val scanUrl: String, val createdAt: Long)
    private val pendingLogins = ConcurrentHashMap<String, PendingWebLogin>()
    /** 上次机器人二维码提醒时间（同账号等待扫码期间限频用）。 */
    private val lastQrNotificationAt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var recoveryScope: CoroutineScope? = null

    /** Worker 启用 ADB 恢复时由启动入口注入；未注入则保留机器人二维码兼容路径。 */
    @Volatile
    var deviceRecoveryHandler: ((String) -> Unit)? = null

    fun configureRecovery(scope: CoroutineScope, handler: ((String) -> Unit)? = null) {
        recoveryScope = scope
        deviceRecoveryHandler = handler
    }

    /** 是否已配置设备（ADB/AAOS）自动登录接管。 */
    fun isDeviceRecoveryConfigured(): Boolean = deviceRecoveryHandler != null

    /** 达到重新扫码阈值时异步发起登录（操作主账号，兼容入口） */
    fun requestReloginIfNeeded(): ReloginRequestResult {
        val vid = WeReadAccountPool.primaryAccount()?.vid ?: return ReloginRequestResult.NO_ACCOUNT
        return requestReloginIfNeeded(vid)
    }

    /**
     * 达到重新扫码阈值时异步发起登录（防重入，按账号隔离）
     *
     * 在抓取链路（被动刷新失败）中调用，不阻塞当前协程。
     * 由独立线程执行扫码登录流程，结束自动复位标志。
     */
    fun requestReloginIfNeeded(
        vid: String,
        force: Boolean = false,
        automatic: Boolean = true,
    ): ReloginRequestResult {
        val account = WeReadAccountPool.get(vid) ?: return ReloginRequestResult.NO_ACCOUNT
        if (!reloginEligible(
                status = account.status,
                refreshFailCount = account.credentials.refreshFailCount,
                threshold = AppConfig.wereadRefreshFailThreshold,
                force = force,
            )
        ) return ReloginRequestResult.NOT_ELIGIBLE
        val now = System.currentTimeMillis()
        if (automatic && now < nextReloginAttemptAt.getOrDefault(vid, 0L)) {
            return ReloginRequestResult.BACKOFF
        }
        // ADB recovery has its own explicit adb.enabled gate and must not be coupled to the
        // human QR-notification preference. Only EXPIRED accounts are delegated; an ACTIVE
        // account with high refreshFailCount still uses the normal manual QR flow.
        if (account.status == AccountStatus.EXPIRED) {
            deviceRecoveryHandler?.let {
                it(vid)
                return ReloginRequestResult.STARTED
            }
        }
        if (automatic && !AppConfig.wereadAutoQrNotificationEnabled) {
            return ReloginRequestResult.AUTO_QR_DISABLED
        }
        if (reloginInProgress.putIfAbsent(vid, true) != null) return ReloginRequestResult.ALREADY_RUNNING
        val scope = recoveryScope
        if (scope == null) {
            reloginInProgress.remove(vid)
            logger.error("微信读书自动恢复未配置应用协程，无法启动: vid=$vid")
            return ReloginRequestResult.RECOVERY_UNAVAILABLE
        }
        if (!scope.isActive) {
            reloginInProgress.remove(vid)
            logger.error("微信读书自动恢复协程已关闭，无法启动: vid=$vid")
            return ReloginRequestResult.RECOVERY_UNAVAILABLE
        }
        val job = scope.launch(Dispatchers.IO) {
            try {
                logger.warn("微信读书凭证失效，异步触发重新扫码: vid=$vid")
                val windowMs = AppConfig.wereadQrReuseWindowSeconds * 1000L
                val deadline = System.currentTimeMillis() + windowMs
                var notified = false
                var success = false
                var mismatchReason: String? = null
                // 有效期内复用同一二维码；每 60s 轮询一轮，refresh 续期会被下一轮自动接走。
                while (System.currentTimeMillis() < deadline && !success) {
                    val pending = ensurePendingLogin(vid) ?: break
                    if (notifyLoginQr(vid, pending)) notified = true
                    val credentials = pollLoginCredentials(pending.uid, timeoutSeconds = 60)
                    if (credentials == null) continue
                    if (credentials.wrVid != vid) {
                        // 账号池支持多账号：无 ADB 时人工扫码通常来自另一个微信账号
                        // （如 909705585），按实际扫码账号入库即可恢复 Web 采集，不覆盖目标凭证。
                        logger.warn("扫码账号与目标账号不一致，按实际账号入库: expected=$vid, actual=${credentials.wrVid}")
                        // 该二维码已被这次扫码消费：清掉 pending，下一轮直接生成新码而不是复用旧码。
                        pendingLogins.remove(vid, pending)
                        if (saveLoginCredentials(credentials)) {
                            reloginFailureCount.remove(credentials.wrVid)
                            nextReloginAttemptAt.remove(credentials.wrVid)
                            WebhookNotifier.notify(
                                "weread_account_recovered",
                                mapOf(
                                    "message" to "微信读书 Web 扫码成功：已按实际扫码账号 ${credentials.wrVid} 入库；" +
                                        "目标账号 $vid 保持原状态（手机 ADB 恢复后再处理）",
                                    "vid" to credentials.wrVid,
                                    "expected_vid" to vid,
                                ),
                            )
                            // 目标账号的自动扫码按退避放慢，避免手机未开机期间反复推送二维码。
                            recordReloginFailure(vid)
                            success = true
                        } else {
                            mismatchReason = "扫码账号 ${credentials.wrVid} 凭证入库失败（目标 $vid）"
                        }
                        break
                    }
                    if (saveLoginCredentials(credentials)) {
                        pendingLogins.remove(vid, pending)
                        reloginFailureCount.remove(vid)
                        nextReloginAttemptAt.remove(vid)
                        WebhookNotifier.notify(
                            "weread_account_recovered",
                            mapOf("message" to "微信读书 Web 账号已恢复", "vid" to vid),
                        )
                        success = true
                    } else {
                        recordReloginFailure(vid)
                        break
                    }
                }
                if (!success) {
                    recordReloginFailure(vid)
                    if (mismatchReason != null) {
                        // 扫错账号是用户可操作的明确失败：立即发具体原因（带刷新页链接），
                        // 不再补发含糊的"等待扫码超时"，避免误导。
                        notifyReloginFailure(vid, mismatchReason)
                    } else {
                        // 失败提醒同样受提醒间隔约束：本轮推过码直接发；否则仅当距离上次提醒
                        // 已超过间隔才发，保证任何情况下同账号单位间隔内最多一条登录相关消息。
                        val now2 = System.currentTimeMillis()
                        val intervalMs = AppConfig.wereadQrReminderIntervalSeconds * 1000L
                        if (notified || now2 - lastQrNotificationAt.getOrDefault(vid, 0L) >= intervalMs) {
                            lastQrNotificationAt[vid] = now2
                            notifyReloginFailure(vid, "二维码等待扫码超时，过期后可在刷新页一键续期")
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error("微信读书自动恢复异常: vid=$vid, ${error.message}", error)
                recordReloginFailure(vid)
                notifyReloginFailure(vid, error.message ?: error.javaClass.simpleName)
            } finally {
                reloginInProgress.remove(vid)
            }
        }
        // A cancelled scope can prevent the coroutine body from running at all.
        job.invokeOnCompletion { reloginInProgress.remove(vid) }
        return ReloginRequestResult.STARTED
    }

    /** Web 登录刷新页地址（随机器人消息下发，过期点按钮续期）。 */
    fun webLoginRefreshPageUrl(vid: String): String =
        "${AppConfig.siteUrl.trimEnd('/')}/weread/web/login/refresh?vid=${vid}"

    /**
     * 获取当前有效的待扫码登录（有效期内复用，不重复生成）。
     * 有效期见 `weread.qr_reuse_window_seconds`（默认 300 秒）。
     */
    fun ensurePendingLogin(vid: String): PendingWebLogin? {
        val now = System.currentTimeMillis()
        val windowMs = AppConfig.wereadQrReuseWindowSeconds * 1000L
        pendingLogins[vid]?.let { if (now - it.createdAt < windowMs) return it }
        val (uid, scanUrl) = createLogin() ?: return null
        val pending = PendingWebLogin(uid, scanUrl, now)
        pendingLogins[vid] = pending
        runCatching {
            generateQrPng(scanUrl)?.let { png ->
                java.io.File("qrcode").apply { mkdirs() }
                java.io.File(java.io.File("qrcode"), "weread_web_${vid}.png").writeBytes(png)
            }
        }.onFailure { logger.warn("保存二维码图片失败: ${it.message}") }
        return pending
    }

    /**
     * 推送登录二维码（E-Ink 式限频：同一账号等待扫码期间最多每间隔推送一次）。
     * @return 是否实际发出了推送。
     */
    fun notifyLoginQr(vid: String, pending: PendingWebLogin): Boolean {
        val now = System.currentTimeMillis()
        val intervalMs = AppConfig.wereadQrReminderIntervalSeconds * 1000L
        if (now - lastQrNotificationAt.getOrDefault(vid, 0L) < intervalMs) {
            logger.info("微信读书登录二维码提醒限频中，跳过推送: vid=$vid")
            return false
        }
        val qrPng = generateQrPng(pending.scanUrl)
        val delivered = if (qrPng != null) {
            kotlinx.coroutines.runBlocking {
                WebhookNotifier.pushQrcode(
                    qrPng,
                    "请使用微信扫码登录微信读书（过期后可打开刷新页重新获取）",
                    fileName = "weread_web_${vid}.png",
                    localFileAvailable = true,
                    targetUrl = webLoginRefreshPageUrl(vid),
                )
            }.anySuccess
        } else {
            kotlinx.coroutines.runBlocking {
                WebhookNotifier.notify(
                    "weread_login_qr",
                    mapOf(
                        "message" to "微信读书需要重新登录，请打开链接扫码",
                        "scan_url" to pending.scanUrl,
                        "refresh_url" to webLoginRefreshPageUrl(vid),
                        "vid" to vid,
                    ),
                )
            }
        }
        if (delivered) lastQrNotificationAt[vid] = now
        else logger.warn("微信读书登录二维码推送失败: vid=$vid")
        return delivered
    }

    /**
     * 刷新页/按钮入口：强制生成新二维码并推送，同时唤醒轮询。
     * 已恢复的账号直接返回无需扫码。
     */
    fun refreshLoginQr(vid: String): Map<String, Any?> {
        val account = WeReadAccountPool.get(vid)
            ?: return mapOf("ok" to false, "error" to "账号不存在")
        if (account.status == AccountStatus.ACTIVE) {
            return mapOf("ok" to true, "recovered" to true, "message" to "账号正常，无需扫码")
        }
        pendingLogins.remove(vid)
        lastQrNotificationAt.remove(vid)
        val pending = ensurePendingLogin(vid)
            ?: return mapOf("ok" to false, "error" to "二维码生成失败")
        val notified = notifyLoginQr(vid, pending)
        // 运行中的轮询会在下一轮自动接走新 uid；没有运行时则启动一个。
        requestReloginIfNeeded(vid, force = true, automatic = false)
        return mapOf(
            "ok" to true,
            "scan_url" to pending.scanUrl,
            "refresh_url" to webLoginRefreshPageUrl(vid),
            "notified" to notified,
        )
    }

    /** Web 登录刷新页 HTML（状态 + 二维码 + 一键续期按钮）。 */
    fun webLoginStatusHtml(vid: String): String {
        fun esc(value: String): String = value
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")
        val account = WeReadAccountPool.get(vid)
            ?: return webLoginNoticeHtml("该微信读书账号不存在。", vid, null, null)
        if (account.status == AccountStatus.ACTIVE) {
            return webLoginNoticeHtml("账号 $vid 状态正常，无需扫码。", vid, null, null)
        }
        val now = System.currentTimeMillis()
        val windowMs = AppConfig.wereadQrReuseWindowSeconds * 1000L
        val pending = pendingLogins[vid]?.takeIf { now - it.createdAt < windowMs }
        val qrDataUri = pending?.let {
            generateQrPng(it.scanUrl)?.let { png ->
                "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)
            }
        }
        val remainSec = pending?.let { ((it.createdAt + windowMs - now) / 1000L).coerceAtLeast(0L) }
        return webLoginNoticeHtml(
            message = if (pending != null) {
                "账号 $vid 等待扫码登录（二维码约 ${remainSec} 秒后过期）。"
            } else {
                "账号 $vid 当前没有有效二维码（已过期或尚未生成），点下面按钮重新获取。"
            },
            vid = vid,
            scanUrl = pending?.scanUrl,
            qrDataUri = qrDataUri,
        )
    }

    private fun webLoginNoticeHtml(message: String, vid: String, scanUrl: String?, qrDataUri: String?): String {
        fun esc(value: String): String = value
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")
        val qrBlock = if (qrDataUri != null && scanUrl != null) {
            """<img src="${qrDataUri}" alt="登录二维码" style="width:260px;height:260px;margin:12px auto;display:block;border:1px solid #e5e7eb;border-radius:12px;">
        <p class="hint">或复制链接到微信打开：<br><code>${esc(scanUrl)}</code></p>"""
        } else if (scanUrl != null) {
            """<p class="hint">扫码链接：<br><code>${esc(scanUrl)}</code></p>"""
        } else {
            ""
        }
        return """
    <!doctype html>
    <html lang="zh-CN">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width,initial-scale=1">
      <title>微信读书 Web 扫码登录</title>
      <style>
        body { margin: 0; background: #f5f7fa; color: #172033; font-family: system-ui, sans-serif; }
        main { max-width: 520px; margin: 10vh auto; padding: 32px; background: white; border-radius: 16px; box-shadow: 0 10px 35px #17203318; }
        h1 { font-size: 20px; }
        .hint { color: #667085; line-height: 1.7; word-break: break-all; }
        code { font-size: 12px; }
        button { display: block; width: 100%; margin-top: 18px; padding: 14px; border: none; border-radius: 10px; color: white; background: #07c160; font-size: 17px; }
      </style>
    </head>
    <body>
      <main>
        <h1>微信读书 Web 扫码登录</h1>
        <p class="hint">${esc(message)}</p>
        ${qrBlock}
        <form method="post" action="/api/weread/account/recover/refresh?vid=${esc(vid)}">
          <button type="submit">重新获取二维码</button>
        </form>
      </main>
    </body>
    </html>
""".trimIndent()
    }

    /**
     * 发起扫码登录（获取 uid 与二维码 URL）
     *
     * @return Pair(uid, scanUrl)，失败返回 null
     */
    fun createLogin(): Pair<String, String>? {
        return try {
            val req = Request.Builder()
                .url("$baseUrl/api/auth/getLoginUid")
                .header("Referer", "$baseUrl/")
                .header("User-Agent", UA)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    logger.error("获取微信读书二维码失败: HTTP ${resp.code}")
                    return null
                }
                val body = JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject
                val uid = body.get("uid")?.asString ?: return null
                val scanUrl = "$baseUrl/web/confirm?uid=$uid"
                logger.info("微信读书二维码已生成: uid=$uid")
                uid to scanUrl
            }
        } catch (e: Exception) {
            logger.error("获取微信读书二维码异常: ${e.message}")
            null
        }
    }

    /**
     * 轮询扫码结果（getLoginInfo 长轮询）
     *
     * @param uid 二维码 uid
     * @param timeoutSeconds 最长等待秒数（服务端单次长轮询约 60s）
     * @return true=登录成功并已保存凭证
     */
    fun pollLogin(uid: String, timeoutSeconds: Long = 120): Boolean {
        val credentials = pollLoginCredentials(uid, timeoutSeconds) ?: return false
        return saveLoginCredentials(credentials)
    }

    private fun saveLoginCredentials(credentials: WeReadWebCredentials): Boolean {
        return WeReadCookieStore.setLoggedIn(
            wrSkey = credentials.wrSkey,
            wrVid = credentials.wrVid,
            wrRt = credentials.wrRt,
            username = credentials.username,
        )
    }

    /** 轮询扫码结果但不修改全局 Cookie，供指定账号恢复流程使用。 */
    fun pollLoginCredentials(uid: String, timeoutSeconds: Long = 120): WeReadWebCredentials? {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            try {
                val url = "$baseUrl/api/auth/getLoginInfo".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("uid", uid)
                    .addQueryParameter("otp", "")
                    .build()
                val req = Request.Builder()
                    .url(url)
                    .header("Referer", "$baseUrl/")
                    .header("User-Agent", UA)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = JsonParser.parseString(resp.body?.string().orEmpty())
                    if (!body.isJsonObject) return@use
                    val obj = body.asJsonObject
                    if (obj.get("succeed")?.asBoolean == true) {
                        val accessToken = obj.get("accessToken")?.asString
                        val webLoginVid = obj.get("webLoginVid")?.asString
                        if (!accessToken.isNullOrEmpty() && !webLoginVid.isNullOrEmpty()) {
                            val username = fetchUsername(webLoginVid)
                            logger.info("微信读书登录成功: username=$username, vid=$webLoginVid")
                            return WeReadWebCredentials(
                                wrSkey = accessToken,
                                wrVid = webLoginVid,
                                wrRt = "",
                                username = username,
                                loginTime = System.currentTimeMillis(),
                                lastRefreshTime = System.currentTimeMillis(),
                            )
                        }
                    } else {
                        val logicCode = obj.get("logicCode")?.asString ?: ""
                        when (logicCode) {
                            "LOGIN_TIMEOUT" -> {
                                // 单次 getLoginInfo 长轮询约 60s；AAOS 侧拉起微信、扫码并等
                                // WebView 渲染经常需要更久。只要总 deadline 未到就继续下一轮，
                                // 不要在第一轮 60s 边界就放弃（否则扫码稍慢就整体失败）。
                                if (System.currentTimeMillis() + 1_000L < deadline) {
                                    logger.info("微信读书本轮长轮询超时，继续等待扫码确认（剩余 ${(deadline - System.currentTimeMillis()) / 1000}s）")
                                } else {
                                    logger.warn("微信读书扫码等待超时")
                                    return null
                                }
                            }
                            "NEED_OTP", "OTP_EXPIRED", "OTP_NOT_MATCH" -> {
                                logger.warn("微信读书需要双重验证(OTP): $logicCode，当前暂不支持自动处理")
                                return null
                            }
                            else -> logger.debug("getLoginInfo 未完成: logicCode=$logicCode")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("轮询微信读书扫码状态异常: ${e.message}")
            }
            Thread.sleep(2000)
        }
        logger.warn("微信读书扫码等待超时")
        return null
    }

    /**
     * 获取用户昵称（登录成功后调用）
     */
    private fun fetchUsername(vid: String): String {
        return try {
            val req = Request.Builder()
                .url("$baseUrl/api/userInfo".toHttpUrl().newBuilder()
                    .addQueryParameter("userVid", vid).build())
                .header("Referer", "$baseUrl/")
                .header("User-Agent", UA)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val obj = JsonParser.parseString(resp.body?.string().orEmpty())
                if (obj.isJsonObject) {
                    obj.asJsonObject.get("value")?.asJsonObject?.get("name")?.asString ?: ""
                } else ""
            }
        } catch (e: Exception) {
            logger.warn("获取微信读书昵称失败: ${e.message}")
            ""
        }
    }

    /**
     * 发起完整扫码登录（获取二维码 + 生成图片推送机器人 + 轮询）
     *
     * @return true=登录成功
     */
    fun startLogin(targetVid: String? = null, requireNotification: Boolean = false): Boolean {
        val (uid, scanUrl) = createLogin() ?: return false
        val qrFileName = targetVid?.let { "weread_web_${it}.png" } ?: "weread_web_qrcode.png"
        // 将扫码链接生成二维码图片并推送到通知机器人
        val qrPng = generateQrPng(scanUrl)
        if (qrPng != null) {
            // 保存到 qrcode 目录供飞书卡片引用
            val localFileAvailable = try {
                val dir = java.io.File("qrcode").apply { mkdirs() }
                java.io.File(dir, qrFileName).writeBytes(qrPng)
                true
            } catch (e: Exception) {
                logger.warn("保存二维码图片失败: ${e.message}")
                false
            }
            val delivery = kotlinx.coroutines.runBlocking {
                WebhookNotifier.pushQrcode(
                    qrPng,
                    "请使用微信扫码登录微信读书（公众号采集回退通道）",
                    fileName = qrFileName,
                    localFileAvailable = localFileAvailable,
                )
            }
            if (!delivery.anySuccess) {
                logger.error("微信读书二维码已生成，但未成功推送到任何通知机器人")
            }
            if (requireNotification && !delivery.anySuccess) return false
            if (!localFileAvailable && !delivery.anySuccess) return false
        } else {
            val notified = kotlinx.coroutines.runBlocking {
                WebhookNotifier.notify("weread_login_qr", mapOf(
                    "message" to "微信读书需要重新登录，请打开链接扫码",
                    "scan_url" to scanUrl,
                    "vid" to targetVid,
                ))
            }
            if (requireNotification && !notified) return false
        }
        val credentials = pollLoginCredentials(uid) ?: return false
        if (targetVid != null && credentials.wrVid != targetVid) {
            logger.warn("扫码账号与目标账号不一致，拒绝覆盖凭证: expected=$targetVid, actual=${credentials.wrVid}")
            return false
        }
        return saveLoginCredentials(credentials)
    }

    /**
     * 主动刷新保活（操作主账号，兼容入口）
     */
    @Deprecated("Web accessToken cannot be renewed; use E-Ink credentials")
    fun proactiveRefresh(): Boolean {
        val vid = WeReadAccountPool.primaryAccount()?.vid ?: return false
        return proactiveRefresh(vid)
    }

    /**
     * 新版 Web 登录只返回 accessToken，没有 refreshToken；HEAD / 只能探活，不能续签。
     * 保留该方法作为旧调用方兼容入口，但不再发起会被误判为续签的网络请求。
     */
    @Deprecated("Web accessToken cannot be renewed; use E-Ink refreshToken flow")
    fun proactiveRefresh(vid: String): Boolean {
        val account = WeReadAccountPool.get(vid) ?: return false
        if (!account.hasWebCredentials()) return false
        WeReadAccountPool.markRefreshFailed(vid)
        logger.warn("Web accessToken 不支持自动续签，跳过 HEAD 伪刷新并等待重新扫码或 E-Ink 通道: vid=$vid")
        return false
    }

    /**
     * 连续刷新失败是否达到需重新扫码的阈值（操作主账号，兼容入口）
     */
    fun shouldRelogin(): Boolean {
        val vid = WeReadAccountPool.primaryAccount()?.vid ?: return false
        return shouldRelogin(vid)
    }

    /** 连续刷新失败是否达到需重新扫码的阈值（按账号） */
    fun shouldRelogin(vid: String): Boolean =
        WeReadAccountPool.get(vid)?.let { account ->
            account.status == AccountStatus.EXPIRED ||
                account.credentials.refreshFailCount >= AppConfig.wereadRefreshFailThreshold
        } == true

    fun recoveryStatus(vid: String): Map<String, Any?> = mapOf(
        "auto_qr_notification_enabled" to AppConfig.wereadAutoQrNotificationEnabled,
        "adb_recovery_configured" to (deviceRecoveryHandler != null),
        "in_progress" to reloginInProgress.getOrDefault(vid, false),
        "failure_count" to reloginFailureCount.getOrDefault(vid, 0),
        "next_retry_at" to nextReloginAttemptAt[vid],
    )

    private fun recordReloginFailure(vid: String) {
        val count = reloginFailureCount.merge(vid, 1) { previous, increment -> previous + increment } ?: 1
        val exponent = (count - 1).coerceIn(0, 10)
        val baseDelayMs = AppConfig.wereadReloginRetryBackoff * 1000L
        val maxDelayMs = AppConfig.wereadEinkRefreshRetryMax * 1000L
        val delayMs = (baseDelayMs * (1L shl exponent)).coerceAtMost(maxDelayMs)
        nextReloginAttemptAt[vid] = System.currentTimeMillis() + delayMs
    }

    private suspend fun notifyReloginFailure(vid: String, reason: String) {
        WebhookNotifier.notify(
            "weread_account_recovery_failed",
            mapOf(
                "message" to "微信读书 Web 二维码恢复未完成，服务将按退避策略重试",
                "vid" to vid,
                "reason" to reason.take(500),
                "refresh_url" to webLoginRefreshPageUrl(vid),
                "next_retry_at" to nextReloginAttemptAt[vid],
            ),
        )
    }

    internal fun reloginEligible(
        status: AccountStatus,
        refreshFailCount: Int,
        threshold: Int,
        force: Boolean,
    ): Boolean = force || status == AccountStatus.EXPIRED || refreshFailCount >= threshold

    /**
     * 清除登录状态
     */
    fun clear() {
        WeReadCookieStore.clear()
    }

    /**
     * 将文本编码为二维码 PNG 图片字节
     */
    fun generateQrPng(text: String, size: Int = 400): ByteArray? {
        return try {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
                com.google.zxing.EncodeHintType.MARGIN to 1,
            )
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
            val img = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    img.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
                }
            }
            val baos = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(img, "png", baos)
            baos.toByteArray()
        } catch (e: Exception) {
            logger.error("生成二维码失败: ${e.message}")
            null
        }
    }

    /** 生成适合 Android MediaStore/相册导入的 24-bit sRGB JPEG。 */
    fun generateQrJpeg(text: String, size: Int = 400): ByteArray? {
        return try {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
                com.google.zxing.EncodeHintType.MARGIN to 1,
            )
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
            val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_3BYTE_BGR)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    image.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
                }
            }
            val output = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(image, "jpg", output)
            output.toByteArray()
        } catch (e: Exception) {
            logger.error("生成二维码 JPEG 失败: ${e.message}")
            null
        }
    }

    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
