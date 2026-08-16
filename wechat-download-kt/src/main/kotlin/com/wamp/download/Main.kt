package com.wamp.download

import com.wamp.download.adapter.wxAdapterRoutes
import com.wamp.download.article.articleRoutes
import com.wamp.download.auth.authRoutes
import com.wamp.download.auth.LoginChecker
import com.wamp.download.config.AppConfig
import com.wamp.download.feed.feedRoutes
import com.wamp.download.notify.LoginReminder
import com.wamp.download.poller.RssPoller
import com.wamp.download.rabbitmq.RabbitMqService
import com.wamp.download.store.Database
import com.wamp.download.task.TaskProcessor
import com.wamp.download.wx.WxApiClient
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
import org.slf4j.LoggerFactory

fun main() {
    AppConfig.load()
    Database.init()

    val logger = LoggerFactory.getLogger("Main")
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
        resultPublisher = rabbitMqService.resultPublisher,
    )

    // JVM shutdown hook：Ctrl+C / kill 时清理资源，避免残留进程
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("收到关闭信号，正在清理资源...")
        try {
            rabbitMqService.stop()
        } catch (e: Exception) {
            logger.warn("关闭 RabbitMQ 异常: ${e.message}")
        }
        scope.cancel()
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
        scope.launch { RssPoller.start() }
        scope.launch { LoginReminder.start() }
        scope.launch { loginChecker.start() }
        // 微信读书凭证主动刷新保活（wr_skey 双层刷新的"主动"层）
        scope.launch {
            val auth = com.wamp.download.weread.WeReadWebAuth
            val store = com.wamp.download.weread.WeReadCookieStore
            while (true) {
                try {
                    if (auth.isLoggedIn()) {
                        when {
                            // 连续刷新失败达到阈值 → 凭证真失效，推送二维码重新扫码（防重入）
                            auth.shouldRelogin() -> auth.requestReloginIfNeeded()
                            // 距上次刷新超过阈值 → 主动刷新保活
                            store.get().needRefresh() -> auth.proactiveRefresh()
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("微信读书刷新协程异常: ${e.message}")
                }
                kotlinx.coroutines.delay(AppConfig.wereadRefreshInterval * 1000)
            }
        }
        scope.launch {
            rabbitMqService.start(scope, onTaskReceived = { task ->
                taskProcessor.process(task)
            })
        }
        logger.info("后台任务已启动: RSS轮询器 + 登录提醒 + 登录检查 + RabbitMQ + 任务处理 + 微信读书刷新保活")

        routing {
            get("/") { call.respondText("wechat-download-kt is running") }
            get("/api/health") { call.respondText("""{"status":"ok"}""") }

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
                        "dead_letter_queue" to "dead-message-queue",
                        "vhosts" to states.mapValues { it.value.name }
                    )
                ))
            }

            // 微信登录状态查询
            get("/api/wx/status") {
                call.respond(mapOf(
                    "logged_in" to com.wamp.download.auth.AuthManager.isLoggedIn(),
                    "is_recovering" to loginChecker.isRecovering,
                    "token_valid" to loginChecker.isTokenValid(),
                    "nickname" to com.wamp.download.auth.AuthManager.get().nickname,
                    "expire_time" to com.wamp.download.auth.AuthManager.get().expireTime,
                ))
            }

            authRoutes(scope)
            articleRoutes()
            feedRoutes()
            wxAdapterRoutes()

            // 微信读书官方直连回退通道：状态查询 + 手动触发扫码登录
            get("/api/weread/status") {
                val creds = com.wamp.download.weread.WeReadCookieStore.get()
                call.respond(mapOf(
                    "logged_in" to com.wamp.download.weread.WeReadWebAuth.isLoggedIn(),
                    "username" to creds.username,
                    "base_url" to AppConfig.wereadWebBaseUrl,
                    "refresh_fail_count" to creds.refreshFailCount,
                    "need_relogin" to com.wamp.download.weread.WeReadWebAuth.shouldRelogin(),
                    "channels" to com.wamp.download.task.ChannelStateManager.snapshot(),
                ))
            }
            post("/api/weread/login") {
                val ok = com.wamp.download.weread.WeReadWebAuth.startLogin()
                call.respond(mapOf("started" to ok, "message" to if (ok) "二维码已推送，请扫码" else "发起登录失败"))
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
        }
    }).start(wait = true)
}
