package com.wamp.download.store

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InboxStoreTest {
    @Test
    fun `首次消息可 claim 重复消息复用结果事件`() {
        withTemporaryDatabase { dbPath ->
            val first = InboxStore.claim(message("wf-1:BTA", now = 1_000L))
            val duplicate = InboxStore.claim(message("wf-1:BTA", now = 1_001L))

            assertTrue(first.claimed)
            assertFalse(duplicate.claimed)
            assertNotEquals("", first.resultEventId)
            assertEquals(first.resultEventId, duplicate.resultEventId)
        }
    }

    @Test
    fun `失败消息可重新 claim 且保留稳定结果事件`() {
        withTemporaryDatabase {
            val first = InboxStore.claim(message("wf-2:BTA", now = 2_000L))
            InboxStore.markFailed("wf-2:BTA")
            val retry = InboxStore.claim(message("wf-2:BTA", now = 2_001L))

            assertTrue(retry.claimed)
            assertEquals(first.resultEventId, retry.resultEventId)
        }
    }

    @Test
    fun `处理超时的消息可被接管`() {
        withTemporaryDatabase {
            val first = InboxStore.claim(message("wf-3:BTA", now = 3_000L))
            val takeover = InboxStore.claim(message("wf-3:BTA", now = 3_000L + 30 * 60 * 1000L))

            assertTrue(first.claimed)
            assertTrue(takeover.claimed)
            assertEquals(first.resultEventId, takeover.resultEventId)
        }
    }

    @Test
    fun `broker重投可立即接管未完成任务并复用结果事件`() {
        withTemporaryDatabase {
            val first = InboxStore.claim(message("wf-4:BTA", now = 4_000L))
            val takeover = InboxStore.claim(
                message("wf-4:BTA", now = 4_001L).copy(allowProcessingTakeover = true),
            )

            assertTrue(takeover.claimed)
            assertEquals(first.resultEventId, takeover.resultEventId)
        }
    }

    @Test
    fun `并发投递的 inbox 写入不会因 SQLite 写锁失败`() {
        withTemporaryDatabase {
            val workers = Executors.newFixedThreadPool(4)
            val start = CountDownLatch(1)
            try {
                val claims = (0 until 24).map { index ->
                    workers.submit<InboxStore.ClaimResult> {
                        start.await()
                        InboxStore.claim(message("wf-concurrent-$index:BTA", now = 10_000L + index))
                    }
                }
                start.countDown()

                assertTrue(claims.all { it.get(10, TimeUnit.SECONDS).claimed })
            } finally {
                workers.shutdownNow()
            }
        }
    }

    private fun message(idempotencyKey: String, now: Long): InboxStore.InboxMessage =
        InboxStore.InboxMessage(
            idempotencyKey = idempotencyKey,
            eventId = "evt-$idempotencyKey",
            workflowId = idempotencyKey.substringBefore(':'),
            now = now,
        )

    private fun withTemporaryDatabase(block: (String) -> Unit) {
        val dbFile = Files.createTempFile("wechat-download-inbox-", ".db").toFile()
        dbFile.delete()
        try {
            Database.init(dbFile.absolutePath)
            block(dbFile.absolutePath)
        } finally {
            File(dbFile.absolutePath).delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
        }
    }
}
