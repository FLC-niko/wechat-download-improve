package com.wamp.download.task

import com.wamp.download.rabbitmq.GzhAutoFromBackend
import com.wamp.download.store.Database
import com.wamp.download.store.SubscriptionStore
import com.wamp.download.weread.WeReadDirectoryAccount
import com.wamp.download.weread.WeReadSubscriptionResolver
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TaskProcessorAccountResolverTest {
    @Test
    fun `未入库公众号通过微信读书解析后写入订阅并继续任务`() {
        withTemporaryDatabase {
            val resolver = WeReadSubscriptionResolver { keyword ->
                if (keyword == "海珠检查") {
                    WeReadDirectoryAccount(
                        fakeid = "ZmFrZS1iaXo=",
                        bookId = "MP_WXS_3295180586",
                        nickname = "广州海珠发布",
                        alias = "海珠发布",
                        headImg = "cover",
                    )
                } else {
                    null
                }
            }
            val processor = TaskProcessor(
                subscriptionResolver = resolver,
                einkRequestAllowed = { false },
            )

            runCatching {
                processor.process(
                    GzhAutoFromBackend(
                        queueName = "gzh-auto-BTA-queue",
                        image = "",
                        gzhName = "海珠检查",
                        tempTimeStamp = listOf("2025-01-01", "2025-01-02"),
                        url = "",
                        jobId = 10L,
                        correlationId = "account-resolver-test",
                    ),
                )
            }

            val subscription = SubscriptionStore.findByNickname("广州海珠发布")
            assertNotNull(subscription)
            assertEquals("ZmFrZS1iaXo=", subscription.fakeid)
            assertEquals("MP_WXS_3295180586", subscription.wereadMpId)
        }
    }

    private fun withTemporaryDatabase(block: suspend () -> Unit) {
        val dbFile = Files.createTempFile("wechat-download-account-resolver-", ".db").toFile()
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
