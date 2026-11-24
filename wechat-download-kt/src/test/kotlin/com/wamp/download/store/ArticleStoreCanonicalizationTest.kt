package com.wamp.download.store

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArticleStoreCanonicalizationTest {
    @Test
    fun `E-Ink writes remove credentials and tracking and same aid remains idempotent`() = withDb {
        val fakeid = "canonical-eink"
        SubscriptionStore.add(fakeid, "Canonical")
        val first = Article(
            fakeid = fakeid,
            aid = "same-review-id",
            title = "One",
            link = "https://mp.weixin.qq.com/s?__biz=b&mid=1&idx=1&sn=s&pass_ticket=secret&exportkey=x&from=share",
        )
        assertEquals(1, ArticleStore.saveAll(fakeid, listOf(first), "eink"))
        assertEquals(0, ArticleStore.saveAll(fakeid, listOf(first.copy(link = "https://mp.weixin.qq.com/s/another-token")), "eink"))

        val saved = ArticleStore.getByFakeid(fakeid, 10).single()
        assertEquals("https://mp.weixin.qq.com/s?__biz=b&mid=1&idx=1&sn=s", saved.link)
        assertTrue("pass_ticket" !in saved.link)
    }

    @Test
    fun `E-Ink write rejects external and malformed article URLs`() = withDb {
        val fakeid = "invalid-eink"
        SubscriptionStore.add(fakeid, "Invalid")
        assertFailsWith<IllegalArgumentException> {
            ArticleStore.saveAll(fakeid, listOf(Article(fakeid = fakeid, title = "Bad", link = "https://example.com/s/a")), "eink")
        }
        assertTrue(ArticleStore.getByFakeid(fakeid).isEmpty())
    }

    private fun withDb(block: () -> Unit) {
        val db = Files.createTempFile("article-canonical-", ".db").toFile()
        db.delete()
        try {
            Database.init(db.absolutePath)
            block()
        } finally {
            listOf(db.absolutePath, "${db.absolutePath}-wal", "${db.absolutePath}-shm").forEach { File(it).delete() }
        }
    }
}
