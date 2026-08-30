package com.wamp.download.weread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeReadWebArticleParserTest {
    private val client = WeReadWebClient()

    @Test
    fun `使用reviews的createTime最小值作为下一页游标`() {
        val reviews = (0 until 10).joinToString(",") { index ->
            val createTime = 2_000 - index
            """{"createTime":$createTime,"subReviews":[{"review":{"mpInfo":{"originalId":"id-$index","title":"t-$index","time":$createTime}}}]}"""
        }

        val parsed = client.parseArticleList("""{"reviews":[$reviews],"clearAll":0}""")

        assertTrue(parsed.validResponse)
        assertEquals(10, parsed.articles.size)
        assertEquals(1_991, parsed.nextOffset)
        assertFalse(parsed.endReached)
    }

    @Test
    fun `reviews短页或clearAll明确表示到底`() {
        val shortPage = client.parseArticleList(
            """{"reviews":[{"createTime":100,"subReviews":[]}],"clearAll":0}""",
        )
        val clearAll = client.parseArticleList(
            """{"reviews":[${(0 until 10).joinToString(",") { "{\"createTime\":${100 - it},\"subReviews\":[]}" }}],"clearAll":1}""",
        )

        assertTrue(shortPage.endReached)
        assertTrue(clearAll.endReached)
    }
}
