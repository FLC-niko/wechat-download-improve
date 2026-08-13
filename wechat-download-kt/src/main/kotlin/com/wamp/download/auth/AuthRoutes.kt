package com.wamp.download.auth

import com.wamp.download.notify.WebhookNotifier
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * 认证路由
 *
 * - GET  /api/login/status  — 获取登录状态
 * - POST /api/login/start   — 发起服务端主动登录（推送二维码到机器人）
 * - POST /api/login/manual  — 手动提交凭证
 * - POST /api/login/logout  — 清除凭证
 */
fun Route.authRoutes(scope: CoroutineScope) {
    val logger = LoggerFactory.getLogger("AuthRoutes")
    val loginService = LoginService()

    route("/api/login") {
        get("/status") {
            val creds = AuthManager.get()
            val loggedIn = AuthManager.isLoggedIn()
            val expired = AuthManager.isExpired()
            call.respond(mapOf(
                "authenticated" to loggedIn,
                "loggedIn" to loggedIn,
                "account" to creds.nickname,
                "nickname" to creds.nickname,
                "fakeid" to creds.fakeid,
                "expireTime" to creds.expireTime,
                "isExpired" to expired,
                "status" to when {
                    !loggedIn -> "未登录，请发起扫码登录"
                    expired -> "登录已过期，请重新登录"
                    else -> "登录正常"
                }
            ))
        }

        post("/start") {
            if (AuthManager.isLoggedIn() && !AuthManager.isExpired()) {
                call.respond(mapOf(
                    "success" to false,
                    "message" to "当前登录状态正常，无需重复登录"
                ))
                return@post
            }

            // 异步发起登录流程，不阻塞 HTTP 响应
            scope.launch {
                val success = loginService.startLogin()
                if (!success) {
                    logger.warn("登录流程未成功完成")
                    WebhookNotifier.notify("login_failed", mapOf(
                        "message" to "登录流程未成功完成，请重试"
                    ))
                }
            }

            call.respond(mapOf(
                "success" to true,
                "message" to "登录流程已启动，二维码将推送到通知机器人"
            ))
        }

        post("/manual") {
            val body = call.receive<Map<String, Any?>>()
            val token = body["token"] as? String ?: ""
            val cookie = body["cookie"] as? String ?: ""
            val fakeid = body["fakeid"] as? String ?: ""
            val nickname = body["nickname"] as? String ?: ""
            val expireTime = (body["expire_time"] as? Number)?.toLong()
                ?: (System.currentTimeMillis() + 4L * 24 * 3600 * 1000)

            if (token.isEmpty() || cookie.isEmpty()) {
                call.respond(mapOf(
                    "success" to false,
                    "message" to "token 和 cookie 不能为空"
                ))
                return@post
            }

            AuthManager.save(WechatCredentials(
                token = token,
                cookie = cookie,
                fakeid = fakeid,
                nickname = nickname,
                expireTime = expireTime,
            ))

            WebhookNotifier.notify("login_success", mapOf(
                "nickname" to nickname,
                "fakeid" to fakeid,
            ))

            call.respond(mapOf(
                "success" to true,
                "message" to "登录凭证已保存"
            ))
        }

        post("/logout") {
            AuthManager.clear()
            call.respond(mapOf(
                "success" to true,
                "message" to "已清除登录凭证"
            ))
        }
    }
}
