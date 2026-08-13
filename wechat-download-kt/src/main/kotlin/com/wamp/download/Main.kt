package com.wamp.download

import com.wamp.download.adapter.wxAdapterRoutes
import com.wamp.download.article.articleRoutes
import com.wamp.download.auth.authRoutes
import com.wamp.download.config.AppConfig
import com.wamp.download.feed.feedRoutes
import com.wamp.download.notify.LoginReminder
import com.wamp.download.poller.RssPoller
import com.wamp.download.store.Database
import io.ktor.serialization.gson.gson
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

fun main() {
    AppConfig.load()
    Database.init()

    val logger = LoggerFactory.getLogger("Main")
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

        // 启动后台任务（在 Ktor 模块内，确保服务就绪后运行）
        scope.launch { RssPoller.start() }
        scope.launch { LoginReminder.start() }
        logger.info("后台任务已启动: RSS轮询器 + 登录提醒")

        routing {
            get("/") { call.respondText("wechat-download-kt is running") }
            get("/api/health") { call.respondText("""{"status":"ok"}""") }

            authRoutes(scope)
            articleRoutes()
            feedRoutes()
            wxAdapterRoutes()
        }
    }).start(wait = true)
}
