package com.wamp.download

import com.wamp.download.adapter.wxAdapterRoutes
import com.wamp.download.adb.AdbDeviceSupervisor
import com.wamp.download.article.articleRoutes
import com.wamp.download.auth.authRoutes
import com.wamp.download.auth.LoginChecker
import com.wamp.download.config.AppConfig
import com.wamp.download.feed.feedRoutes
import com.wamp.download.notify.LoginReminder
import com.wamp.download.poller.RssPoller
import com.wamp.download.rabbitmq.RabbitMqService
import com.wamp.download.rabbitmq.RabbitTaskMessage
import com.wamp.download.store.Database
import com.wamp.download.task.TaskProcessor
import com.wamp.download.wx.WxApiClient
import com.wamp.download.weread.CredentialHealthChecker
import com.wamp.download.weread.eink.WeReadEinkRuntime
import com.wamp.download.weread.eink.WeReadEinkLoginCoordinator
import io.ktor.serialization.gson.gson
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

fun main() {
    AppConfig.load()
    Database.init()

    val logger = LoggerFactory.getLogger("Main")
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    WeReadEinkRuntime.initialize()

    // 动态发现 adb devices；不写死 USB 序列号或无线 ADB 地址。
    val adbDeviceSupervisor = AdbDeviceSupervisor()
    val recoveryManager = com.wamp.download.weread.DeviceRecoveryManager(
        supervisor = adbDeviceSupervisor,
        scope = scope,
    )
    if (AppConfig.adbEnabled) {
        com.wamp.download.weread.WeReadWebAuth.deviceRecoveryHandler = recoveryManager::request
        adbDeviceSupervisor.refresh()
        adbDeviceSupervisor.start(scope)
        recoveryManager.start()
    }

    // 微信 API 客户端（搜索公众号 + 获取文章）
    val wxClient = WxApiClient()

    // 登录状态检查器（定时探测 + 恢复流程）
    val loginChecker = LoginChecker(wxClient = wxClient)

    // RabbitMQ 服务（消费任务队列）
    val rabbitMqService = RabbitMqService()

    // 任务处理器（搜索公众号 → 获取文章 → 发布结果）
    val taskProcessor = TaskProcessor(
        wxClient = wxClient,
        loginChecker = loginChecker,
        einkSyncServiceProvider = WeReadEinkRuntime::syncService,
    )

    // JVM shutdown hook：Ctrl+C / kill 时清理资源，避免残留进程
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("收到关闭信号，正在清理资源...")
        // 先取消后台协程，让轮询/消费/保活立即停止
        scope.cancel()
        adbDeviceSupervisor.stop()
        try {
            // RabbitMQ 关闭是阻塞网络调用，断网/自动恢复时可能长时间不返回；
            // 用超时兜底，保证 Ctrl+C 能顺利退出，不残留进程
            runBlocking {
                withTimeout(5000) {
                    withContext(Dispatchers.IO) {
                        rabbitMqService.stop()
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("关闭 RabbitMQ 超时或异常: ${e.message}")
        }
        logger.info("资源清理完成，退出")
    })

    embeddedServer(Netty, port = AppConfig.port, host = AppConfig.host, module = {
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
                disableHtmlEscaping()
            }
        }
        install(CORS) {
            anyHost()
            allowCredentials = true
            allowMethod(io.ktor.http.HttpMethod.Get)
            allowMethod(io.ktor.http.HttpMethod.Post)
            allowMethod(io.ktor.http.HttpMethod.Put)
            allowMethod(io.ktor.http.HttpMethod.Delete)
            allowHeader(io.ktor.http.HttpHeaders.ContentType)
            allowHeader(io.ktor.http.HttpHeaders.Authorization)
        }

        // 启动后台任务
        if (AppConfig.rssEnabled) {
            scope.launch { RssPoller.start() }
        }
        scope.launch { LoginReminder.start() }
        if (AppConfig.authAutoRecoveryEnabled) {
            scope.launch { loginChecker.start() }
        }
        // 启动凭证健康检查：重启后立即发现失效凭证（E-Ink 续签验证 / Web 账号探测），
        // 避免失效凭证躺到第一个任务失败才发现。
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(10_000)
            try {
                CredentialHealthChecker.checkAll(scope)
            } catch (e: Exception) {
                logger.warn("启动凭证健康检查异常: ${e.message}")
            }
        }
        // E-Ink 只按服务端明确过期时间提前续签；未提供日期时由 401/-2012 触发真实 /login 续签。
        scope.launch {
            while (true) {
                try {
                    WeReadEinkRuntime.refreshIfExpiryDue()
                } catch (e: Exception) {
                    logger.warn("E-Ink token 续签检查异常: ${e.message}")
                }
                kotlinx.coroutines.delay(AppConfig.wereadEinkRefreshCheckInterval * 1000)
            }
        }
        scope.launch {
            rabbitMqService.start(scope, onTaskReceived = { task, publisher ->
                when (task) {
                    is RabbitTaskMessage.V2 -> taskProcessor.process(task.task, publisher)
                    is RabbitTaskMessage.Legacy -> taskProcessor.process(task.task, publisher)
                }
            })
        }
        logger.info(
            "后台任务已启动: RabbitMQ + 任务处理 + 登录提醒 + E-Ink真实Token续签" +
                (if (AppConfig.authAutoRecoveryEnabled) " + 主微信自动恢复" else "（主微信自动恢复已关闭）") +
                (if (AppConfig.rssEnabled) " + RSS轮询器" else "（RSS轮询已关闭）"),
        )

        routing {
            get("/") { call.respondText("wechat-download-kt is running") }
            get("/api/health") { call.respondText("""{"status":"ok"}""") }

            get("/api/adb/status") {
                val snapshot = adbDeviceSupervisor.snapshot()
                call.respond(mapOf(
                    "mode" to snapshot.mode.name,
                    "devices" to snapshot.devices.map { device ->
                        mapOf("serial" to device.serial, "user_ids" to device.userIds)
                    },
                    "pending_recovery_vids" to recoveryManager.pendingVids(),
                ))
            }

            // 二维码图片静态服务（供飞书通知引用，如 /qrcode/wx_qrcode.png）
            get("/qrcode/{file}") {
                val file = call.parameters["file"] ?: return@get call.respondText("not found", status = io.ktor.http.HttpStatusCode.NotFound)
                val safe = file.removePrefix("/")
                val img = java.io.File("qrcode", safe)
                if (!img.exists()) {
                    call.respondText("not found", status = io.ktor.http.HttpStatusCode.NotFound)
                } else {
                    call.respondBytes(img.readBytes(), contentType = io.ktor.http.ContentType.Image.PNG)
                }
            }

            // RabbitMQ 连接状态查询
            get("/api/rabbitmq/status") {
                val states = rabbitMqService.snapshot()
                call.respond(mapOf(
                    "rabbitmq" to mapOf(
                        "host" to AppConfig.rabbitmqHost,
                        "port" to AppConfig.rabbitmqPort,
                        "exchange" to "new-media-backend",
                        "task_queue" to "gzh-auto-BTA-queue",
                        "result_queue" to "gzh-auto-ATD-queue",
                        "dead_letter_queue" to com.wamp.download.rabbitmq.GZH_DEAD_QUEUE,
                        "vhosts" to states.mapValues { it.value.name }
                    )
                ))
            }

            // 微信登录状态查询
            get("/api/wx/status") {
                val channelState = com.wamp.download.task.ChannelStateManager.snapshot()
                call.respond(mapOf(
                    "logged_in" to com.wamp.download.auth.AuthManager.isLoggedIn(),
                    "is_recovering" to loginChecker.isRecovering,
                    "token_valid" to loginChecker.isTokenValid(),
                    "nickname" to com.wamp.download.auth.AuthManager.get().nickname,
                    "expire_time" to com.wamp.download.auth.AuthManager.get().expireTime,
                    "channel" to channelState,
                ))
            }

            authRoutes(scope)
            articleRoutes()
            feedRoutes()
            wxAdapterRoutes()

            // 微信读书官方直连回退通道：状态查询 + 手动触发扫码登录
            get("/api/weread/status") {
                val pool = com.wamp.download.weread.WeReadAccountPool
                call.respond(mapOf(
                    "account_count" to pool.size(),
                    "accounts" to accountMap(pool.list()),
                    "base_url" to AppConfig.wereadWebBaseUrl,
                    "channels" to com.wamp.download.task.ChannelStateManager.snapshot(),
                    "eink" to WeReadEinkRuntime.status(),
                ))
            }
            get("/api/weread/eink/status") {
                call.respond(mapOf(
                    "runtime" to WeReadEinkRuntime.status(),
                    "login" to WeReadEinkLoginCoordinator.status(),
                ))
            }
            post("/api/weread/eink/login/start") {
                val started = WeReadEinkLoginCoordinator.start(scope)
                val message = when {
                    !AppConfig.wereadEinkEnabled -> "E-Ink 通道未启用"
                    started -> "E-Ink 登录已启动，二维码生成后将推送并保存到 qrcode 目录"
                    else -> "E-Ink 登录已在进行中"
                }
                call.respond(mapOf("started" to started, "message" to message))
            }
            post("/api/weread/login") {
                scope.launch {
                    val success = com.wamp.download.weread.WeReadWebAuth.startLogin()
                    if (!success) {
                        logger.warn("微信读书登录流程未成功完成")
                    }
                }
                call.respond(mapOf("started" to true, "message" to "登录任务已启动，二维码生成后将推送到通知机器人"))
            }
            // 用 fakeid 为公众号绑定微信读书 mpId（回退通道预绑定，wxs2mp 解析接口已下线，直接推导）
            post("/api/weread/bind") {
                val body = call.receive<Map<String, String>>()
                val fakeid = body["fakeid"] ?: ""
                if (fakeid.isBlank()) {
                    call.respond(mapOf("ok" to false, "error" to "缺少 fakeid"))
                } else {
                    val mpId = com.wamp.download.weread.WeReadWebClient().computeMpId(fakeid)
                    if (mpId.isBlank()) {
                        call.respond(mapOf("ok" to false, "error" to "该 fakeid 无法推导出 mpId"))
                    } else {
                        com.wamp.download.store.SubscriptionStore.saveWereadMpId(fakeid, mpId)
                        call.respond(mapOf("ok" to true, "fakeid" to fakeid, "mpId" to mpId))
                    }
                }
            }

            // ── 微信读书账号池管理 ──
            get("/api/weread/account/list") {
                call.respond(mapOf(
                    "accounts" to accountMap(com.wamp.download.weread.WeReadAccountPool.list()),
                ))
            }
            post("/api/weread/account/enable") {
                val body = call.receive<Map<String, String>>()
                val vid = body["vid"] ?: ""
                val ok = com.wamp.download.weread.WeReadAccountPool.setEnabled(vid, true)
                call.respond(mapOf("ok" to ok, "vid" to vid))
            }
            post("/api/weread/account/disable") {
                val body = call.receive<Map<String, String>>()
                val vid = body["vid"] ?: ""
                val ok = com.wamp.download.weread.WeReadAccountPool.setEnabled(vid, false)
                call.respond(mapOf("ok" to ok, "vid" to vid))
            }
            post("/api/weread/account/bind") {
                val body = call.receive<Map<String, String>>()
                val vid = body["vid"] ?: ""
                val serial = body["serial"] ?: ""
                val userId = body["user_id"]?.toIntOrNull() ?: 0
                val strategy = body["strategy"] ?: "FIXED_DEVICE"
                val account = com.wamp.download.weread.WeReadAccountPool.get(vid)
                val ok = account != null && serial.isNotBlank() && strategy in setOf("FIXED_DEVICE", "ELASTIC_DEVICE")
                if (ok) {
                    account!!.boundSerial = serial
                    account.boundUserId = userId
                    account.bindingStrategy = strategy
                    com.wamp.download.weread.WeReadAccountPool.saveForRecovery(account)
                }
                call.respond(mapOf("ok" to ok, "vid" to vid, "serial" to serial, "user_id" to userId, "strategy" to strategy))
            }
            post("/api/weread/account/recover") {
                val body = call.receive<Map<String, String>>()
                val vid = body["vid"] ?: ""
                val exists = com.wamp.download.weread.WeReadAccountPool.get(vid) != null
                val accepted = exists && AppConfig.adbEnabled
                if (accepted) recoveryManager.request(vid)
                val message = when {
                    !exists -> "账号不存在"
                    !AppConfig.adbEnabled -> "ADB 已关闭，请使用微信读书手动登录接口恢复"
                    else -> "恢复任务已排队"
                }
                call.respond(mapOf("ok" to accepted, "vid" to vid, "message" to message))
            }
            post("/api/weread/account/delete") {
                val body = call.receive<Map<String, String>>()
                val vid = body["vid"] ?: ""
                val ok = com.wamp.download.weread.WeReadAccountPool.delete(vid)
                call.respond(mapOf("ok" to ok, "vid" to vid))
            }
        }
    }).start(wait = true)
}

/** 账号列表序列化为路由返回结构 */
private fun accountMap(accounts: List<com.wamp.download.weread.WeReadAccount>): List<Map<String, Any>> =
    accounts.map { acc ->
        mapOf(
            "vid" to acc.vid,
            "username" to acc.credentials.username,
            "status" to acc.status.name,
            "bound_serial" to acc.boundSerial,
            "bound_user_id" to acc.boundUserId,
            "binding_strategy" to acc.bindingStrategy,
            "total_requests" to acc.totalRequests,
            "freq_control_count" to acc.freqControlCount,
            "last_used_time" to acc.lastUsedTime,
            "cooldown_until" to acc.cooldownUntil,
            "disabled_reason" to acc.disabledReason,
            "refresh_fail_count" to acc.credentials.refreshFailCount,
        )
    }
