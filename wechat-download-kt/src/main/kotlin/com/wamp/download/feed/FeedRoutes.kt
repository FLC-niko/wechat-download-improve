package com.wamp.download.feed

import com.wamp.download.store.ArticleStore
import com.wamp.download.store.SubscriptionStore
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Feed 路由 — 对齐 Python 版 feed.py
 *
 * - GET /api/feed/articles.json  — 增量拉取本地文章（含 id，游标分页）
 * - GET /api/feed/article/{id}.md — 单篇文章 markdown 正文（暂不支持，只取链接模式不需要）
 */
fun Route.feedRoutes() {
    route("/api/feed") {
        get("/articles.json") {
            val since = call.parameters["since"]?.toLongOrNull() ?: 0L
            val fakeid = call.parameters["fakeid"]
            val limit = (call.parameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)

            val articles = ArticleStore.getFeedArticles(since = since, fakeid = fakeid, limit = limit)
            val subs = SubscriptionStore.list().first.associateBy { it.fakeid }

            val items = articles.map { a ->
                mapOf(
                    "id" to (a.id ?: 0),
                    "fakeid" to a.fakeid,
                    "nickname" to (subs[a.fakeid]?.nickname ?: ""),
                    "title" to a.title,
                    "digest" to a.digest,
                    "author" to a.author,
                    "publish_time" to a.publishTime,
                    "link" to a.link,
                    "cover" to a.cover,
                    "content_fetched" to a.content.isNotEmpty(),
                )
            }

            val nextSince = if (items.isNotEmpty()) {
                (items.last()["publish_time"] as? Long) ?: since
            } else {
                since
            }

            call.respond(mapOf(
                "articles" to items,
                "next_since" to nextSince,
            ))
        }
    }
}
