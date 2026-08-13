package com.wamp.download.store

import com.wamp.download.config.AppConfig
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection

/**
 * 数据库表定义 — 对齐 Python 版 rss_store.py 的 schema
 */
object Subscriptions : Table("subscriptions") {
    val fakeid = varchar("fakeid", 64)
    val nickname = text("nickname").default("")
    val alias = text("alias").default("")
    val headImg = text("head_img").default("")
    val createdAt = long("created_at")
    val lastPoll = long("last_poll").default(0L)

    override val primaryKey = PrimaryKey(fakeid)
}

object Articles : IntIdTable("articles") {
    val fakeid = varchar("fakeid", 64).references(Subscriptions.fakeid, onDelete = ReferenceOption.CASCADE)
    val aid = text("aid").default("")
    val title = text("title").default("")
    val link = text("link").default("")
    val digest = text("digest").default("")
    val cover = text("cover").default("")
    val author = text("author").default("")
    val content = text("content").default("")
    val plainContent = text("plain_content").default("")
    val publishTime = long("publish_time").default(0L)
    val fetchedAt = long("fetched_at")
    val articleSource = text("source").default("poll")

    init {
        uniqueIndex("idx_articles_fakeid_link", fakeid, link)
        index("idx_articles_fakeid_time", isUnique = false, fakeid, publishTime)
    }
}

/**
 * 数据库管理器
 */
object Database {
    private val logger = LoggerFactory.getLogger(Database::class.java)
    private var db: org.jetbrains.exposed.sql.Database? = null

    fun init() {
        val dbPath = AppConfig.dbPath
        File(dbPath).parentFile?.mkdirs()

        db = org.jetbrains.exposed.sql.Database.connect(
            url = "jdbc:sqlite:$dbPath",
            driver = "org.sqlite.JDBC",
        )

        transaction {
            exec("PRAGMA journal_mode=WAL")
            exec("PRAGMA foreign_keys=ON")

            SchemaUtils.create(Subscriptions, Articles)
            logger.info("数据库初始化完成: $dbPath")
        }
    }

    fun <T> query(block: Transaction.() -> T): T = transaction(db, block)
}
