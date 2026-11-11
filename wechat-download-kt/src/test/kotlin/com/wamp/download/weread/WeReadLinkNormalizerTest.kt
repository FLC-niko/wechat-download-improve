package com.wamp.download.weread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeReadLinkNormalizerTest {
    @Test
    fun `reviewId token is recovered and tilde is restored to underscore`() {
        val link = WeReadLinkNormalizer.shortLinkFromReviewId(
            "MP_WXS_3295180586_TdqGgGK~LztBeKkLYF00qw",
        )

        assertEquals("https://mp.weixin.qq.com/s/TdqGgGK_LztBeKkLYF00qw", link)
    }

    @Test
    fun `reviewId with underscores in token keeps the full token`() {
        val link = WeReadLinkNormalizer.shortLinkFromReviewId(
            "MP_WXS_3295180586_abc_def~ghi",
        )

        assertEquals("https://mp.weixin.qq.com/s/abc_def_ghi", link)
    }

    @Test
    fun `non review id is not converted into an article link`() {
        assertNull(WeReadLinkNormalizer.shortLinkFromReviewId("abc_def"))
        assertNull(WeReadLinkNormalizer.shortLinkFromReviewId("MP_WXS_3295180586"))
    }

    @Test
    fun `article short urls are normalized without touching query parameters`() {
        val normalized = WeReadLinkNormalizer.normalizeArticleUrl(
            "https://mp.weixin.qq.com/s/TdqGgGK~LztBeKkLYF00qw?scene=1&from=timeline",
        )

        assertEquals(
            "https://mp.weixin.qq.com/s/TdqGgGK_LztBeKkLYF00qw?scene=1&from=timeline",
            normalized,
        )
    }

    @Test
    fun `full article url captured from e-ink app is preserved`() {
        val url = "https://mp.weixin.qq.com/s?__biz=MzI0MzYzODA1NQ==&mid=2247488959" +
            "&idx=1&sn=7ec8f4620a5f6c05f7e562898844897f&scene=294&subscene=0" +
            "&clicktag=mp-common-profile&clickpos=0&from_safari=0&ascene=0" +
            "&devicetype=android-34&version=28004c55&nettype=WIFI&lang=zh_CN"

        assertEquals(url, WeReadLinkNormalizer.normalizeArticleUrl(url))
        assertEquals(url, WeReadLinkNormalizer.articleUrlFromOriginalId(url))
    }

    @Test
    fun `short article url with fragment keeps fragment after token normalization`() {
        val normalized = WeReadLinkNormalizer.normalizeArticleUrl(
            "https://mp.weixin.qq.com/s/TdqGgGK~LztBeKkLYF00qw#rd",
        )

        assertEquals("https://mp.weixin.qq.com/s/TdqGgGK_LztBeKkLYF00qw#rd", normalized)
    }

    @Test
    fun `original ids are converted to canonical short links`() {
        assertEquals(
            "https://mp.weixin.qq.com/s/TdqGgGK_LztBeKkLYF00qw",
            WeReadLinkNormalizer.articleUrlFromOriginalId("TdqGgGK~LztBeKkLYF00qw"),
        )
        assertEquals(
            "https://mp.weixin.qq.com/s?__biz=MzA~&mid=1",
            WeReadLinkNormalizer.articleUrlFromOriginalId("?__biz=MzA~&mid=1"),
        )
    }
}
