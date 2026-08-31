package com.wamp.download.weread.eink

import com.wamp.download.store.ArticleStore
import com.wamp.download.store.Database
import com.wamp.download.store.SubscriptionStore
import com.wamp.download.store.WeReadEinkSyncStateStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeReadEinkSyncCoverageTest {
    @Test
    fun `全部文章映射失败时不推进游标也不声明缓存覆盖`() {
        withTemporaryDatabase {
            val subscription = subscription("invalid-payload")
            val service = serviceWith(
                """
                {
                  "data":[{"reviewId":"r1","title":"缺少文章链接","createTime":1700000000}],
                  "synckey":99,
                  "hasMore":0
                }
                """.trimIndent(),
            )

            assertFailsWith<WeReadEinkSyncException> { service.sync(subscription) }

            val state = checkNotNull(WeReadEinkSyncStateStore.get("vid-coverage", subscription.wereadMpId))
            assertEquals("FAILED", state.status)
            assertEquals(null, state.synckey)
            assertEquals(0, state.coverageVersion)
            assertFalse(service.isRangeCovered(subscription, 0L, 1_700_000_000L))
            assertTrue(ArticleStore.getByFakeid(subscription.fakeid).isEmpty())
        }
    }

    @Test
    fun `服务端明确空页时可以提交新版可信覆盖`() {
        withTemporaryDatabase {
            val subscription = subscription("verified-empty")
            val service = serviceWith("""{"data":[],"synckey":9,"hasMore":0}""")
            val before = System.currentTimeMillis() / 1000L

            val result = service.sync(subscription)

            val state = checkNotNull(WeReadEinkSyncStateStore.get("vid-coverage", subscription.wereadMpId))
            assertEquals(9L, result.synckey)
            assertEquals(WeReadEinkSyncStateStore.CURRENT_COVERAGE_VERSION, state.coverageVersion)
            assertEquals("ACTIVE", state.status)
            assertTrue(service.isRangeCovered(subscription, 0L, before))
        }
    }

    private fun subscription(suffix: String) = com.wamp.download.store.Subscription(
        fakeid = "fakeid-$suffix",
        nickname = "测试公众号-$suffix",
        wereadMpId = "MP_WXS_$suffix",
    ).also {
        SubscriptionStore.add(it.fakeid, it.nickname)
        SubscriptionStore.saveWereadMpId(it.fakeid, it.wereadMpId)
    }

    private fun serviceWith(body: String): WeReadEinkSyncService {
        val session = WeReadEinkSession(
            initialCredentials = WeReadEinkCredentials(
                vid = "vid-coverage",
                accessToken = "access-token",
                refreshToken = "refresh-token",
                deviceId = "device-id",
            ),
        )
        return WeReadEinkSyncService(
            session = session,
            baseUrl = "https://eink.example.test",
            pageSize = 50,
            maxPages = 2,
            clientFactory = { activeSession, baseUrl ->
                WeReadEinkChaptersClient(
                    credentials = activeSession.credentials(),
                    transport = WeReadEinkTransport { WeReadEinkResponse(200, body) },
                    baseUrl = baseUrl,
                    session = activeSession,
                    requestGate = { _, block -> block() },
                    maxTransientRetries = 0,
                )
            },
        )
    }

    private fun withTemporaryDatabase(block: () -> Unit) {
        val dbFile = Files.createTempFile("wechat-download-eink-coverage-", ".db").toFile()
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
