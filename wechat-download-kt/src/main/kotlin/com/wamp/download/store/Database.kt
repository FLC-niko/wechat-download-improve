package com.wamp.download.store

import com.wamp.download.config.AppConfig
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    val wereadMpId = text("weread_mpid").default("")

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

object WeReadEinkSyncStates : Table("weread_eink_sync_states") {
    val accountVid = varchar("account_vid", 255)
    val fakeid = varchar("fakeid", 64)
    val mpId = text("mp_id")
    val synckey = long("synckey").nullable()
    val lastSyncAt = long("last_sync_at").default(0L)
    val lastSuccessAt = long("last_success_at").default(0L)
    val coverageStart = long("coverage_start").nullable()
    val coverageEnd = long("coverage_end").nullable()
    val historyComplete = bool("history_complete").default(false)
    val coverageVersion = integer("coverage_version").default(0)
    val status = varchar("status", 32).default("NEVER_SYNCED")
    val errorType = varchar("error_type", 64).default("")
    val errorMessage = text("error_message").default("")

    override val primaryKey = PrimaryKey(accountVid, mpId)
}

/**
 * 每个公众号已经连续扫描并成功落库的时间节点。
 *
 * 与 E-Ink 账号游标不同，这份进度属于公众号内容本身。即使同步在整轮结束前被中断，
 * 已成功处理的页面也可以留下安全的时间覆盖，后续实时任务无需重新补链这段历史。
 */
object PublicAccountCollectionCheckpoints : Table("public_account_collection_checkpoints") {
    val fakeid = varchar("fakeid", 64).references(Subscriptions.fakeid, onDelete = ReferenceOption.CASCADE)
    val mpId = text("mp_id").default("")
    val collectionSource = varchar("source", 32)
    val oldestCollectedAt = long("oldest_collected_at")
    val newestCollectedAt = long("newest_collected_at")
    val lastSuccessAt = long("last_success_at")
    val pagesCollected = integer("pages_collected").default(0)
    val articlesCollected = integer("articles_collected").default(0)

    override val primaryKey = PrimaryKey(fakeid, collectionSource)

    init {
        index("idx_collection_checkpoint_range", isUnique = false, fakeid, oldestCollectedAt, newestCollectedAt)
    }
}

/**
 * 已由远端完整核验过的文章时间区间。
 *
 * 文章表只能证明“缓存里有什么”，不能证明某个空窗口确实没有文章；因此覆盖信息必须
 * 独立持久化。任务处理器只有在这些区间完整覆盖请求窗口时，才允许完全跳过远端请求。
 */
object ArticleCoverageIntervals : IntIdTable("article_coverage_intervals") {
    val fakeid = varchar("fakeid", 64).references(Subscriptions.fakeid, onDelete = ReferenceOption.CASCADE)
    val coverageStart = long("coverage_start")
    val coverageEnd = long("coverage_end")
    val coverageSource = varchar("source", 32)
    val verifiedAt = long("verified_at")

    init {
        uniqueIndex("idx_article_coverage_unique", fakeid, coverageStart, coverageEnd, coverageSource)
        index("idx_article_coverage_lookup", isUnique = false, fakeid, coverageStart, coverageEnd)
    }
}

object RabbitInbox : Table("rabbit_inbox") {
    val idempotencyKey = varchar("idempotency_key", 255)
    val eventId = varchar("event_id", 255)
    val workflowId = varchar("workflow_id", 255)
    val resultEventId = varchar("result_event_id", 255)
    val status = varchar("status", 32)
    val receivedAt = long("received_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(idempotencyKey)
}

/**
 * 数据库管理器
 */
object Database {
    private val logger = LoggerFactory.getLogger(Database::class.java)
    // SQLite only has one writer. Keep transactions short and fair so concurrent RabbitMQ
    // deliveries wait locally instead of surfacing SQLITE_BUSY and entering broker retry.
    private val queryLock = ReentrantLock(true)
    private var db: org.jetbrains.exposed.sql.Database? = null

    fun init(dbPath: String = AppConfig.dbPath) {
        File(dbPath).parentFile?.mkdirs()

        db = org.jetbrains.exposed.sql.Database.connect(
            url = "jdbc:sqlite:$dbPath",
            driver = "org.sqlite.JDBC",
            setupConnection = { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA foreign_keys=ON")
                    statement.execute("PRAGMA busy_timeout=5000")
                }
            },
        )

        // PRAGMA 必须在事务外执行（WAL 模式不能从事务内切换），使用原生 JDBC 连接
        java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA foreign_keys=ON")
                st.execute("PRAGMA busy_timeout=5000")
            }
            migrateRabbitInbox(conn)
            migrateWeReadEinkSyncStates(conn)
        }

        // 建表单独一个事务（createMissingTablesAndColumns 可为旧表补齐新增列）
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Subscriptions,
                Articles,
                RabbitInbox,
                WeReadEinkSyncStates,
                PublicAccountCollectionCheckpoints,
                ArticleCoverageIntervals,
            )
            logger.info("数据库初始化完成: $dbPath")
        }
    }

    fun <T> query(block: Transaction.() -> T): T = queryLock.withLock {
        transaction(db, block)
    }

    private fun migrateRabbitInbox(conn: Connection) {
        val tableExists = conn.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'rabbit_inbox' LIMIT 1"
        ).use { statement ->
            statement.executeQuery().use { rows -> rows.next() }
        }
        if (!tableExists) return

        val hasResultEventId = conn.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(rabbit_inbox)").use { columns ->
                generateSequence {
                    if (columns.next()) columns.getString("name") else null
                }.any { it == "result_event_id" }
            }
        }
        if (hasResultEventId) return

        conn.createStatement().use { statement ->
            statement.executeUpdate(
                "ALTER TABLE rabbit_inbox ADD COLUMN result_event_id VARCHAR(255) NOT NULL DEFAULT ''"
            )
        }
        conn.prepareStatement(
            "SELECT idempotency_key FROM rabbit_inbox WHERE result_event_id = ''"
        ).use { select ->
            select.executeQuery().use { rows ->
                conn.prepareStatement(
                    "UPDATE rabbit_inbox SET result_event_id = ? WHERE idempotency_key = ?"
                ).use { update ->
                    while (rows.next()) {
                        update.setString(1, "evt_legacy_${UUID.randomUUID()}")
                        update.setString(2, rows.getString("idempotency_key"))
                        update.addBatch()
                    }
                    update.executeBatch()
                }
            }
        }
        logger.info("已迁移 rabbit_inbox.result_event_id，保留历史幂等记录")
    }

    private fun migrateWeReadEinkSyncStates(conn: Connection) {
        val tableExists = conn.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'weread_eink_sync_states' LIMIT 1"
        ).use { statement ->
            statement.executeQuery().use { rows -> rows.next() }
        }
        if (!tableExists) return

        val columns = conn.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(weread_eink_sync_states)").use { rows ->
                generateSequence {
                    if (rows.next()) rows.getString("name") else null
                }.toSet()
            }
        }
        if ("account_vid" in columns) return

        conn.createStatement().use { statement ->
            statement.execute("ALTER TABLE weread_eink_sync_states RENAME TO weread_eink_sync_states_legacy")
            statement.execute(
                """
                CREATE TABLE weread_eink_sync_states (
                    account_vid VARCHAR(255) NOT NULL,
                    fakeid VARCHAR(64) NOT NULL,
                    mp_id TEXT NOT NULL,
                    synckey INTEGER,
                    last_sync_at INTEGER NOT NULL DEFAULT 0,
                    last_success_at INTEGER NOT NULL DEFAULT 0,
                    coverage_start INTEGER,
                    coverage_end INTEGER,
                    history_complete BOOLEAN NOT NULL DEFAULT 0,
                    status VARCHAR(32) NOT NULL DEFAULT 'NEVER_SYNCED',
                    error_type VARCHAR(64) NOT NULL DEFAULT '',
                    error_message TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY (account_vid, mp_id)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                INSERT INTO weread_eink_sync_states
                    (account_vid, fakeid, mp_id, synckey, last_sync_at, last_success_at,
                     coverage_start, coverage_end, history_complete, status, error_type, error_message)
                SELECT 'legacy', fakeid, mp_id, synckey, last_sync_at, last_success_at,
                       coverage_start, coverage_end, history_complete, status, error_type, error_message
                FROM weread_eink_sync_states_legacy
                """.trimIndent(),
            )
            statement.execute("DROP TABLE weread_eink_sync_states_legacy")
        }
        logger.info("已迁移 weread_eink_sync_states，游标按 E-Ink 账号与 mpId 隔离")
    }
}
