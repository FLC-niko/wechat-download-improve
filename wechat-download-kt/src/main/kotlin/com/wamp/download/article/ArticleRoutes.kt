package com.wamp.download.article

import com.wamp.download.auth.AuthManager
import com.wamp.download.store.Article
import com.wamp.download.store.ArticleStore
import com.wamp.download.store.SubscriptionStore
import com.wamp.download.task.ChannelStateManager
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * 文章相关路由
 *
 * - GET /api/public/articles?fakeid&begin&count  — 获取文章列表（调微信 API）
 * - GET /api/articles?fakeid&limit&offset         — 查询本地已存储的文章
 */
fun Route.articleRoutes() {
    val fetcher = ArticleFetcher()

    // 直接调微信 API 获取文章列表（对齐 Python 版 /api/public/articles）
    route("/api/public") {
        get("/articles") {
            val fakeid = call.parameters["fakeid"] ?: run {
                call.respond(mapOf("success" to false, "error" to "fakeid 不能为空"))
                return@get
            }
            val begin = call.parameters["begin"]?.toIntOrNull() ?: 0
            val count = call.parameters["count"]?.toIntOrNull() ?: 10

            if (!ChannelStateManager.isMainChannelAvailable()) {
                call.respond(mapOf(
                    "success" to false,
                    "error" to "主微信文章接口不可用: ${ChannelStateManager.mainChannelUnavailableReason()}",
                ))
                return@get
            }

            if (!AuthManager.isLoggedIn()) {
                call.respond(mapOf("success" to false, "error" to "未登录"))
                return@get
            }

            val articles = fetcher.fetchList(fakeid, begin, count)
            call.respond(mapOf(
                "success" to true,
                "data" to mapOf(
                    "articles" to articles.map { it.toMap() },
                    "total" to articles.size,
                    "begin" to begin,
                    "count" to articles.size,
                )
            ))
        }
    }

    // 查询本地已存储的文章
    get("/api/articles") {
        val fakeid = call.parameters["fakeid"] ?: run {
            call.respond(mapOf("success" to false, "error" to "fakeid 不能为空"))
            return@get
        }
        val limit = call.parameters["limit"]?.toIntOrNull() ?: 20
        val offset = call.parameters["offset"]?.toIntOrNull() ?: 0

        val (articles, total) = ArticleStore.getByFakeidAndRange(
            fakeid = fakeid,
            limit = limit,
            offset = offset,
        )

        call.respond(mapOf(
            "success" to true,
            "data" to mapOf(
                "list" to articles.map { it.toMap() },
                "total" to total,
            )
        ))
    }

    // 查询文章详情
    get("/api/article/{id}") {
        val id = call.parameters["id"]?.toIntOrNull() ?: run {
            call.respond(mapOf("success" to false, "error" to "id 不能为空"))
            return@get
        }

        val article = ArticleStore.getById(id)
        if (article == null) {
            call.respond(mapOf("success" to false, "error" to "文章不存在"))
            return@get
        }

        call.respond(mapOf(
            "success" to true,
            "data" to article.toMap()
        ))
    }

    // 生成指定公众号的历史文章二维码（用于绕过名片码扫码风控）
    get("/api/article/qr") {
        val target = call.parameters["name"] ?: call.parameters["fakeid"] ?: run {
            call.respond(mapOf("success" to false, "error" to "name 或 fakeid 参数不能为空"))
            return@get
        }
        val result = ArticleQrService.getOrGenerateArticleQr(target)
        if (result == null) {
            call.respond(mapOf("success" to false, "error" to "未找到公众号 [$target] 的已采集历史文章，无法生成缓解二维码"))
            return@get
        }
        val (articleUrl, qrRef) = result
        call.respond(mapOf(
            "success" to true,
            "data" to mapOf(
                "target" to target,
                "articleUrl" to articleUrl,
                "qrImage" to qrRef,
            )
        ))
    }

    // 直接输出历史文章二维码图片流
    get("/api/article/qr/image") {
        val target = call.parameters["name"] ?: call.parameters["fakeid"] ?: run {
            call.respond(mapOf("success" to false, "error" to "name 或 fakeid 参数不能为空"))
            return@get
        }
        val articleUrl = ArticleQrService.getLatestArticleUrlByGzh(target)
        if (articleUrl == null) {
            call.respond(mapOf("success" to false, "error" to "未找到公众号 [$target] 的已采集历史文章"))
            return@get
        }
        val bytes = ArticleQrService.generateQrImageBytes(articleUrl)
        call.respondBytes(bytes, ContentType.Image.JPEG)
    }
}

/**
 * Article 转 Map（用于 JSON 响应）
 */
fun Article.toMap() = mapOf(
    "id" to (id ?: 0),
    "fakeid" to fakeid,
    "aid" to aid,
    "title" to title,
    "link" to link,
    "url" to link,  // 适配 Worker 字段名
    "digest" to digest,
    "cover" to cover,
    "author" to author,
    "publish_time" to publishTime,
    "publish_date" to publishTime,
    "source" to source,
)
