package com.wamp.download.task

import com.wamp.download.rabbitmq.GzhAutoFromBackend
import com.wamp.download.store.Article
import com.wamp.download.store.ArticleCoverageStore
import com.wamp.download.store.ArticleStore
import com.wamp.download.store.Database
import com.wamp.download.store.SubscriptionStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

class TaskProcessorCacheFirstTest {
    @Test
    fun `缓存已有可用文章时不需要覆盖记录也不会进入远端抓取计划`() {
        withTemporaryDatabase {
            val (start, _) = CaptureWindowParser.parse(listOf("2025-01-01", "2025-01-02"))
            val startTs = checkNotNull(start)
            val fakeid = "cached-fakeid"
            SubscriptionStore.add(fakeid = fakeid, nickname = "缓存公众号")
            ArticleStore.saveAll(
                fakeid,
                listOf(
                    Article(
                        fakeid = fakeid,
                        title = "缓存文章",
                        link = "https://example.test/cached",
                        publishTime = startTs + 60,
                    ),
                ),
                source = "test",
            )
            val processor = TaskProcessor(
                einkRequestAllowed = { error("缓存已有可用文章时不应规划 E-Ink 请求") },
            )
            processor.process(
                GzhAutoFromBackend(
                    queueName = "gzh-auto-BTA-queue",
                    image = "",
                    gzhName = "缓存公众号",
                    tempTimeStamp = listOf("2025-01-01", "2025-01-02"),
                    url = "",
                    jobId = 1L,
                    correlationId = "cache-first-test",
                ),
            )
        }
    }

    @Test
    fun `可信覆盖可以在缓存为空时直接确认 EMPTY`() {
        withTemporaryDatabase {
            val (start, end) = CaptureWindowParser.parse(listOf("2025-02-01", "2025-02-02"))
            val startTs = checkNotNull(start)
            val endTs = checkNotNull(end)
            val fakeid = "empty-covered-fakeid"
            SubscriptionStore.add(fakeid = fakeid, nickname = "空缓存公众号")
            ArticleCoverageStore.saveVerified(fakeid, startTs, endTs, "weread-web")

            val processor = TaskProcessor(
                einkRequestAllowed = { error("可信空覆盖不应规划 E-Ink 请求") },
            )
            processor.process(
                GzhAutoFromBackend(
                    queueName = "gzh-auto-BTA-queue",
                    image = "",
                    gzhName = "空缓存公众号",
                    tempTimeStamp = listOf("2025-02-01", "2025-02-02"),
                    url = "",
                    jobId = 2L,
                    correlationId = "cache-empty-covered-test",
                ),
            )
        }
    }

    private fun withTemporaryDatabase(block: suspend () -> Unit) {
        val dbFile = Files.createTempFile("wechat-download-task-cache-", ".db").toFile()
        dbFile.delete()
        try {
            Database.init(dbFile.absolutePath)
            runBlocking { block() }
        } finally {
            File(dbFile.absolutePath).delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
        }
    }
}
