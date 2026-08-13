package com.wamp.download.adapter

import com.google.gson.Gson
import com.wamp.download.article.ArticleFetcher
import com.wamp.download.auth.AuthManager
import com.wamp.download.config.AppConfig
import com.wamp.download.store.ArticleStore
import com.wamp.download.store.SubscriptionStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * /api/v1/wx/ 适配壳 — 让 Kotlin Worker 不改代码就能对接
 *
 * 统一返回格式: { "code": 0, "message": "ok", "data": { "list": [...], "total": N } }
 *
 * 对齐 Worker 的 MpApi.kt / AuthApi.kt / ArticleApi.kt 期望的接口。
 */
fun Route.wxAdapterRoutes() {
    val logger = LoggerFactory.getLogger("WxAdapter")
    val gson = Gson()
    val fetcher = ArticleFetcher()

    val wxClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    // 鉴权中间件：校验 ADAPTER_API_KEY
    fun ApplicationCall.checkAdapterAuth(): Boolean {
        if (AppConfig.adapterApiKey.isEmpty()) return true // 未配置密钥则放行
        val auth = request.headers["Authorization"] ?: return false
        val token = auth.removePrefix("Bearer ").trim()
        return token == AppConfig.adapterApiKey
    }

    // 统一响应
    fun ok(list: List<Map<String, Any?>>, total: Int = list.size) = mapOf(
        "code" to 0, "message" to "ok",
        "data" to mapOf("list" to list, "total" to total)
    )
    fun okData(data: Any?) = mapOf("code" to 0, "message" to "ok", "data" to data)
    fun err(msg: String) = mapOf("code" to -1, "message" to msg, "data" to null)

    route("/api/v1/wx") {
        // ── 鉴权 ──────────────────────────────

        post("/auth/login") {
            val body = try { call.receive<Map<String, Any?>>() } catch (e: Exception) { emptyMap() }
            val apiKey = body["password"] as? String ?: body["api_key"] as? String ?: ""

            if (AppConfig.adapterApiKey.isNotEmpty() && apiKey != AppConfig.adapterApiKey) {
                call.respond(err("API Key 校验失败"))
                return@post
            }
            // 返回固定 access_token（Worker 只需要非空字符串）
            call.respond(okData(mapOf("access_token" to "adapter-token")))
        }

        // ── 公众号管理 ──────────────────────────────

        get("/mps") {
            if (!call.checkAdapterAuth()) { call.respond(err("未授权")); return@get }
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 100
            val offset = call.parameters["offset"]?.toIntOrNull() ?: 0
            val kw = call.parameters["kw"]

            val (subs, total) = SubscriptionStore.list(kw = kw, limit = limit, offset = offset)
            val list = subs.map {
                mapOf(
                    "id" to it.fakeid,
                    "mp_id" to it.fakeid,
                    "mp_name" to it.nickname,
                    "mp_cover" to it.headImg,
                    "mp_intro" to it.alias,
                )
            }
            call.respond(ok(list, total))
        }

        post("/mps") {
            if (!call.checkAdapterAuth()) { call.respond(err("未授权")); return@post }
            val body = call.receive<Map<String, Any?>>()
            val mpName = body["mp_name"] as? String ?: ""
            val mpId = body["mp_id"] as? String

            if (mpName.isEmpty() && mpId.isNullOrEmpty()) {
                call.respond(err("mp_name 或 mp_id 不能为空"))
                return@post
            }

            // 如果有 fakeid 直接订阅
            if (!mpId.isNullOrEmpty()) {
                SubscriptionStore.add(fakeid = mpId, nickname = mpName)
                call.respond(ok(listOf(mapOf("id" to mpId, "mp_name" to mpName))))
                return@post
            }

            // 否则先搜索获取 fakeid
            if (!AuthManager.isLoggedIn()) {
                call.respond(err("微信未登录，无法搜索公众号"))
                return@post
            }

            val creds = AuthManager.get()
            val searchUrl = "https://mp.weixin.qq.com/cgi-bin/searchbiz".toHttpUrl().newBuilder()
                .addQueryParameter("action", "search_biz")
                .addQueryParameter("token", creds.token)
                .addQueryParameter("lang", "zh_CN")
                .addQueryParameter("f", "json")
                .addQueryParameter("ajax", "1")
                .addQueryParameter("random", System.currentTimeMillis().toString())
                .addQueryParameter("query", mpName)
                .addQueryParameter("begin", "0")
                .addQueryParameter("count", "5")
                .build()

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", userAgent)
                .header("Cookie", creds.cookie)
                .get()
                .build()

            wxClient.newCall(searchRequest).execute().use { resp ->
                val result = gson.fromJson(resp.body?.string().orEmpty(), Map::class.java)
                val baseResp = result?.get("base_resp") as? Map<*, *>
                if (baseResp?.get("ret") != 0.0 && baseResp?.get("ret") != 0) {
                    call.respond(err("搜索失败: ${baseResp?.get("err_msg")}"))
                    return@post
                }
                val accounts = (result?.get("list") as? List<*>) ?: emptyList<Any?>()
                // 找到精确匹配或用第一个结果
                val match = accounts.firstOrNull { (it as? Map<*, *>)?.get("nickname") == mpName }
                    ?: accounts.firstOrNull()
                val acc = match as? Map<*, *>
                val fakeid = acc?.get("fakeid") as? String ?: ""
                val nickname = acc?.get("nickname") as? String ?: mpName
                val headImg = acc?.get("round_head_img") as? String ?: ""

                if (fakeid.isNotEmpty()) {
                    SubscriptionStore.add(fakeid = fakeid, nickname = nickname, headImg = headImg)
                    call.respond(ok(listOf(mapOf("id" to fakeid, "mp_name" to nickname))))
                } else {
                    call.respond(err("未找到公众号: $mpName"))
                }
            }
        }

        get("/mps/search/{keyword}") {
            if (!call.checkAdapterAuth()) { call.respond(err("未授权")); return@get }
            val keyword = call.parameters["keyword"] ?: run {
                call.respond(err("keyword 不能为空")); return@get
            }

            if (!AuthManager.isLoggedIn()) {
                call.respond(err("微信未登录"))
                return@get
            }

            val creds = AuthManager.get()
            val searchUrl = "https://mp.weixin.qq.com/cgi-bin/searchbiz".toHttpUrl().newBuilder()
                .addQueryParameter("action", "search_biz")
                .addQueryParameter("token", creds.token)
                .addQueryParameter("lang", "zh_CN")
                .addQueryParameter("f", "json")
                .addQueryParameter("ajax", "1")
                .addQueryParameter("random", System.currentTimeMillis().toString())
                .addQueryParameter("query", keyword)
                .addQueryParameter("begin", "0")
                .addQueryParameter("count", "5")
                .build()

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", userAgent)
                .header("Cookie", creds.cookie)
                .get()
                .build()

            wxClient.newCall(searchRequest).execute().use { resp ->
                val result = gson.fromJson(resp.body?.string().orEmpty(), Map::class.java)
                val baseResp = result?.get("base_resp") as? Map<*, *>
                if (baseResp?.get("ret") != 0.0 && baseResp?.get("ret") != 0) {
                    call.respond(err("搜索失败: ${baseResp?.get("err_msg")}"))
                    return@get
                }
                val accounts = (result?.get("list") as? List<*>) ?: emptyList<Any?>()
                val list = accounts.map {
                    val acc = it as? Map<*, *>
                    mapOf(
                        "id" to (acc?.get("fakeid") ?: ""),
                        "mp_id" to (acc?.get("fakeid") ?: ""),
                        "mp_name" to (acc?.get("nickname") ?: ""),
                        "mp_cover" to (acc?.get("round_head_img") ?: ""),
                        "alias" to (acc?.get("alias") ?: ""),
                    )
                }
                call.respond(ok(list))
            }
        }

        // ── 文章刷新（简化版：触发一次抓取） ──────────────────────────────

        get("/mps/update/{mpId}") {
            if (!call.checkAdapterAuth()) { call.respond(err("未授权")); return@get }
            val mpId = call.parameters["mpId"] ?: run {
                call.respond(err("mpId 不能为空")); return@get
            }
            // start_page/end_page 参数在简化版中忽略，直接抓最新
            // Worker 传的是页号范围，这里转换为 begin/count
            val startPage = call.parameters["start_page"]?.toIntOrNull() ?: 0
            val endPage = call.parameters["end_page"]?.toIntOrNull() ?: 99
            val begin = startPage * 10
            val count = ((endPage - startPage + 1) * 10).coerceAtMost(100)

            try {
                val newCount = fetcher.fetchAndSave(mpId, begin = begin, count = count)
                logger.info("刷新完成: mpId=${mpId.take(8)}..., 新增 $newCount 篇")
                call.respond(okData(mapOf("new_count" to newCount)))
            } catch (e: Exception) {
                logger.error("刷新失败: ${e.message}")
                call.respond(err("刷新失败: ${e.message}"))
            }
        }

        // ── 文章查询 ──────────────────────────────

        get("/articles") {
            if (!call.checkAdapterAuth()) { call.respond(err("未授权")); return@get }
            val mpId = call.parameters["mp_id"] ?: call.parameters["fakeid"]
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 20
            val offset = call.parameters["offset"]?.toIntOrNull() ?: 0

            if (mpId.isNullOrEmpty()) {
                call.respond(err("mp_id 不能为空"))
                return@get
            }

            val (articles, total) = ArticleStore.getByFakeidAndRange(
                fakeid = mpId,
                limit = limit,
                offset = offset,
            )

            val list = articles.map {
                mapOf(
                    "id" to (it.id ?: 0),
                    "mp_id" to it.fakeid,
                    "title" to it.title,
                    "url" to it.link,
                    "publish_time" to it.publishTime,
                    "publish_date" to it.publishTime,
                    "digest" to it.digest,
                    "cover" to it.cover,
                    "author" to it.author,
                )
            }
            call.respond(ok(list, total))
        }
    }
}
