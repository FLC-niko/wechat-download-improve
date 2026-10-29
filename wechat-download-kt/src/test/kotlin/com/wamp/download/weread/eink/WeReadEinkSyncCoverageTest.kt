package com.wamp.download.weread.eink

import com.wamp.download.task.IdleHistoryPoller
import com.wamp.download.store.ArticleStore
import com.wamp.download.store.Database
import com.wamp.download.store.PublicAccountCollectionCheckpointStore
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
    fun `idle poller respects completed eink sync even when generic lastPoll is stale`() {
        withTemporaryDatabase {
            val sub = subscription("idle-dedup")
            val service = serviceWith("""{"data":[],"synckey":9,"hasMore":0}""")
            val now = System.currentTimeMillis() / 1000L
            val interval = com.wamp.download.config.AppConfig.wereadEinkSyncInterval
            assertTrue(now - sub.lastPoll > 43200L)
            assertEquals(listOf(sub), IdleHistoryPoller.dueSubscriptions(listOf(sub), service, now))
            service.sync(sub)
            val state = checkNotNull(WeReadEinkSyncStateStore.get("vid-coverage", sub.wereadMpId))
            assertTrue(IdleHistoryPoller.dueSubscriptions(listOf(sub), service, state.lastSuccessAt).isEmpty())
            assertTrue(IdleHistoryPoller.dueSubscriptions(listOf(sub), service, state.lastSuccessAt + interval - 1).isEmpty())
            assertEquals(listOf(sub), IdleHistoryPoller.dueSubscriptions(listOf(sub), service, state.lastSuccessAt + interval))
        }
    }

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

    @Test
    fun `后台同步让出时不记录失败也不推进游标`() {
        withTemporaryDatabase {
            val subscription = subscription("yield-to-realtime")
            val service = serviceWith("""{"data":[],"synckey":9,"hasMore":0}""")

            assertFailsWith<WeReadEinkBackgroundYieldException> {
                service.sync(subscription, shouldContinue = { false })
            }

            assertEquals(null, WeReadEinkSyncStateStore.get("vid-coverage", subscription.wereadMpId))
        }
    }

    @Test
    fun `实时窗口逐页落库并在覆盖目标时间后停止历史翻页`() {
        withTemporaryDatabase {
            val subscription = subscription("target-checkpoint")
            var chapterRequests = 0
            val session = session()
            val service = WeReadEinkSyncService(
                session = session,
                baseUrl = "https://eink.example.test",
                pageSize = 2,
                maxPages = 10,
                clientFactory = { activeSession, baseUrl ->
                    WeReadEinkChaptersClient(
                        credentials = activeSession.credentials(),
                        transport = WeReadEinkTransport { request ->
                            if (request.url.encodedPath.endsWith("/mp/chapters")) chapterRequests++
                            WeReadEinkResponse(
                                200,
                                """
                                {
                                  "data":[
                                    {"reviewId":"r-new","title":"新文章","createTime":220,"mpInfo":{"doc_url":"https://mp.weixin.qq.com/s/new"}},
                                    {"reviewId":"r-boundary","title":"边界文章","createTime":150,"mpInfo":{"doc_url":"https://mp.weixin.qq.com/s/boundary"}}
                                  ],
                                  "synckey":99,
                                  "hasMore":1,
                                  "nextOffset":2
                                }
                                """.trimIndent(),
                            )
                        },
                        baseUrl = baseUrl,
                        session = activeSession,
                        requestGate = { _, block -> block() },
                        maxTransientRetries = 0,
                    )
                },
            )

            val result = service.syncIfNeeded(subscription, startTime = 150L, endTime = 230L)

            assertEquals(1, chapterRequests)
            assertEquals(1, result.pages)
            assertEquals(2, ArticleStore.getByFakeid(subscription.fakeid).size)
            assertTrue(service.isRangeCovered(subscription, 150L, 230L))
            assertEquals(null, WeReadEinkSyncStateStore.get("vid-coverage", subscription.wereadMpId))
            assertTrue(
                checkNotNull(PublicAccountCollectionCheckpointStore.get(subscription.fakeid))
                    .covers(150L, 230L),
            )

            val cachedResult = service.syncIfNeeded(subscription, startTime = 150L, endTime = 230L)
            assertTrue(cachedResult.cacheCovered)
            assertEquals(1, chapterRequests)
        }
    }

    @Test
    fun `扩大历史窗口时复用已落库 reviewId 而不重复补链`() {
        withTemporaryDatabase {
            val subscription = subscription("resume-checkpoint")
            var chapterRequests = 0
            var reviewRequests = 0
            val session = session()
            val service = WeReadEinkSyncService(
                session = session,
                baseUrl = "https://eink.example.test",
                pageSize = 1,
                maxPages = 10,
                clientFactory = { activeSession, baseUrl ->
                    WeReadEinkChaptersClient(
                        credentials = activeSession.credentials(),
                        transport = WeReadEinkTransport { request ->
                            when {
                                request.url.encodedPath.endsWith("/review/single") -> {
                                    reviewRequests++
                                    WeReadEinkResponse(
                                        200,
                                        """{"review":{"mpInfo":{"doc_url":"https://mp.weixin.qq.com/s/resumed"}}}""",
                                    )
                                }
                                request.url.queryParameter("offset") == "1" -> {
                                    chapterRequests++
                                    WeReadEinkResponse(
                                        200,
                                        """
                                        {"data":[{"reviewId":"r-old","title":"旧文章","createTime":100,
                                        "mpInfo":{"doc_url":"https://mp.weixin.qq.com/s/old"}}],"synckey":99,"hasMore":0}
                                        """.trimIndent(),
                                    )
                                }
                                else -> {
                                    chapterRequests++
                                    WeReadEinkResponse(
                                        200,
                                        """{"data":[{"reviewId":"r-resume","title":"待复用文章","createTime":200}],"synckey":99,"hasMore":1}""",
                                    )
                                }
                            }
                        },
                        baseUrl = baseUrl,
                        session = activeSession,
                        requestGate = { _, block -> block() },
                        stringRequestGate = { _, block -> block() },
                        maxTransientRetries = 0,
                    )
                },
            )

            service.syncIfNeeded(subscription, startTime = 200L, endTime = 230L)
            assertEquals(1, reviewRequests)
            assertEquals(1, chapterRequests)

            service.syncIfNeeded(subscription, startTime = 100L, endTime = 230L)

            assertEquals(1, reviewRequests)
            assertEquals(3, chapterRequests)
            assertEquals(2, ArticleStore.getByFakeid(subscription.fakeid).size)
            assertTrue(service.isRangeCovered(subscription, 100L, 230L))
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
        val session = session()
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

    private fun session() = WeReadEinkSession(
            initialCredentials = WeReadEinkCredentials(
                vid = "vid-coverage",
                accessToken = "access-token",
                refreshToken = "refresh-token",
                deviceId = "device-id",
            ),
        )

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
