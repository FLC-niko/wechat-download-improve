package com.wamp.download.store

import org.jetbrains.exposed.sql.*
import org.slf4j.LoggerFactory
import java.time.Instant

data class Article(
    val id: Int? = null,
    val fakeid: String,
    val aid: String = "",
    val title: String = "",
    val link: String = "",
    val digest: String = "",
    val cover: String = "",
    val author: String = "",
    val content: String = "",
    val plainContent: String = "",
    val publishTime: Long = 0L,
    val fetchedAt: Long = Instant.now().epochSecond,
    val source: String = "poll",
)

/**
 * 文章存储 — 对齐 Python 版 rss_store.py 的文章相关函数
 */
object ArticleStore {
    private val logger = LoggerFactory.getLogger(ArticleStore::class.java)

    /**
     * 批量保存文章（幂等），返回新增数量。
     * 对齐 Python 版 save_articles：已存在且 content 为空时更新。
     */
    fun saveAll(fakeid: String, articles: List<Article>, source: String = "poll"): Int =
        Database.query {
            var inserted = 0
            for (a in articles) {
                val existing = Articles.selectAll()
                    .where { (Articles.fakeid eq fakeid) and (Articles.link eq a.link) }
                    .singleOrNull()

                if (existing == null) {
                    Articles.insert {
                        it[Articles.fakeid] = fakeid
                        it[Articles.aid] = a.aid
                        it[Articles.title] = a.title
                        it[Articles.link] = a.link
                        it[Articles.digest] = a.digest
                        it[Articles.cover] = a.cover
                        it[Articles.author] = a.author
                        it[Articles.content] = a.content
                        it[Articles.plainContent] = a.plainContent
                        it[Articles.publishTime] = a.publishTime
                        it[Articles.fetchedAt] = Instant.now().epochSecond
                        it[Articles.articleSource] = source
                    }
                    inserted++
                } else if (a.content.isNotEmpty() && existing[Articles.content].isEmpty()) {
                    Articles.update({ (Articles.fakeid eq fakeid) and (Articles.link eq a.link) }) {
                        it[Articles.content] = a.content
                        it[Articles.plainContent] = a.plainContent
                        if (a.author.isNotEmpty() && existing[Articles.author].isEmpty()) {
                            it[Articles.author] = a.author
                        }
                    }
                }
            }
            inserted
        }

    fun getByFakeid(fakeid: String, limit: Int = 20): List<Article> = Database.query {
        Articles.selectAll()
            .where { Articles.fakeid eq fakeid }
            .orderBy(Articles.publishTime, SortOrder.DESC)
            .limit(limit)
            .map { it.toArticle() }
    }

    fun getById(id: Int): Article? = Database.query {
        Articles.selectAll()
            .where { Articles.id eq id }
            .singleOrNull()
            ?.toArticle()
    }

    /**
     * feed 增量接口：按 publish_time 正序 + 游标返回，对齐 Python 版 get_feed_articles
     */
    fun getFeedArticles(since: Long = 0L, fakeid: String? = null, limit: Int = 50): List<Article> =
        Database.query {
            Articles.selectAll()
                .where {
                    val cond = Articles.publishTime greater since
                    if (fakeid != null) cond and (Articles.fakeid eq fakeid) else cond
                }
                .orderBy(Articles.publishTime, SortOrder.ASC)
                .limit(limit)
                .map { it.toArticle() }
        }

    /**
     * 按时间范围查询文章（供适配壳用）
     */
    fun getByFakeidAndRange(
        fakeid: String,
        startTime: Long? = null,
        endTime: Long? = null,
        limit: Int = 100,
        offset: Int = 0,
    ): Pair<List<Article>, Int> = Database.query {
        val query = Articles.selectAll().where {
            var cond = Articles.fakeid eq fakeid
            if (startTime != null) cond = cond and (Articles.publishTime greaterEq startTime)
            if (endTime != null) cond = cond and (Articles.publishTime lessEq endTime)
            cond
        }

        val total = query.count().toInt()
        val rows = query
            .orderBy(Articles.publishTime, SortOrder.DESC)
            .limit(limit, offset.toLong())
            .map { it.toArticle() }
        rows to total
    }

    private fun ResultRow.toArticle() = Article(
        id = this[Articles.id].value,
        fakeid = this[Articles.fakeid],
        aid = this[Articles.aid],
        title = this[Articles.title],
        link = this[Articles.link],
        digest = this[Articles.digest],
        cover = this[Articles.cover],
        author = this[Articles.author],
        content = this[Articles.content],
        plainContent = this[Articles.plainContent],
        publishTime = this[Articles.publishTime],
        fetchedAt = this[Articles.fetchedAt],
        source = this[Articles.articleSource],
    )
}
