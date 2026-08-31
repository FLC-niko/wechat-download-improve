package com.wamp.download.store

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class ArticleCoverageStoreTest {
    @Test
    fun `旧数据库启动时补齐覆盖版本与通用覆盖表`() {
        val dbFile = Files.createTempFile("wechat-download-coverage-migration-", ".db").toFile()
        dbFile.delete()
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
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
                }
            }

            Database.init(dbFile.absolutePath)

            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                val columns = connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info(weread_eink_sync_states)").use { rows ->
                        generateSequence { if (rows.next()) rows.getString("name") else null }.toSet()
                    }
                }
                assertEquals(true, "coverage_version" in columns)
                val coverageTableExists = connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='article_coverage_intervals'",
                    ).use { rows -> rows.next() }
                }
                assertEquals(true, coverageTableExists)
            }
        } finally {
            File(dbFile.absolutePath).delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
        }
    }

    @Test
    fun `只返回与请求窗口相交的已验证区间`() {
        withTemporaryDatabase {
            SubscriptionStore.add(fakeid = "fakeid-1", nickname = "测试公众号")
            ArticleCoverageStore.saveVerified("fakeid-1", 100L, 200L, "weread-web")
            ArticleCoverageStore.saveVerified("fakeid-1", 300L, 400L, "eink")
            ArticleCoverageStore.saveVerified("fakeid-1", 500L, 600L, "main")

            assertEquals(
                listOf(100L..200L, 300L..400L),
                ArticleCoverageStore.intervals("fakeid-1", 150L, 350L),
            )
        }
    }

    @Test
    fun `重复保存同一区间保持幂等`() {
        withTemporaryDatabase {
            SubscriptionStore.add(fakeid = "fakeid-2", nickname = "测试公众号")
            ArticleCoverageStore.saveVerified("fakeid-2", 100L, 200L, "weread-web")
            ArticleCoverageStore.saveVerified("fakeid-2", 100L, 200L, "weread-web")

            assertEquals(
                listOf(100L..200L),
                ArticleCoverageStore.intervals("fakeid-2", 0L, 1_000L),
            )
        }
    }

    private fun withTemporaryDatabase(block: () -> Unit) {
        val dbFile = Files.createTempFile("wechat-download-coverage-", ".db").toFile()
        dbFile.delete()
        try {
            Database.init(dbFile.absolutePath)
            block()
        } finally {
            File(dbFile.absolutePath).delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
        }
    }
}
