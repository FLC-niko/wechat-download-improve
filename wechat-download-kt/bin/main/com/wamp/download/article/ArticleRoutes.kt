package com.wamp.download.article

import com.wamp.download.auth.AuthManager
import com.wamp.download.store.Article
import com.wamp.download.store.ArticleStore
import com.wamp.download.store.SubscriptionStore
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
