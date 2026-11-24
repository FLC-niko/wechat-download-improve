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
    fun `article short urls retain only stable identity path`() {
        val normalized = WeReadLinkNormalizer.normalizeArticleUrl(
            "https://mp.weixin.qq.com/s/TdqGgGK~LztBeKkLYF00qw?scene=1&from=timeline",
        )

        assertEquals(
            "https://mp.weixin.qq.com/s/TdqGgGK_LztBeKkLYF00qw",
            normalized,
        )
    }

    @Test
    fun `full article url keeps identity query and strips tracking and credentials`() {
        val url = "https://mp.weixin.qq.com/s?__biz=MzI0MzYzODA1NQ==&mid=2247488959" +
            "&idx=1&sn=7ec8f4620a5f6c05f7e562898844897f&scene=294&subscene=0" +
            "&clicktag=mp-common-profile&clickpos=0&from_safari=0&ascene=0" +
            "&devicetype=android-34&version=28004c55&nettype=WIFI&lang=zh_CN"

        val expected = "https://mp.weixin.qq.com/s?__biz=MzI0MzYzODA1NQ%3D%3D&mid=2247488959" +
            "&idx=1&sn=7ec8f4620a5f6c05f7e562898844897f"
        assertEquals(expected, WeReadLinkNormalizer.normalizeArticleUrl(url))
        assertEquals(expected, WeReadLinkNormalizer.articleUrlFromOriginalId(url))
    }

    @Test
    fun `short article url strips fragment after token normalization`() {
        val normalized = WeReadLinkNormalizer.normalizeArticleUrl(
            "https://mp.weixin.qq.com/s/TdqGgGK~LztBeKkLYF00qw#rd",
        )

        assertEquals("https://mp.weixin.qq.com/s/TdqGgGK_LztBeKkLYF00qw", normalized)
    }

    @Test
    fun `original ids are converted to canonical short links`() {
        assertEquals(
            "https://mp.weixin.qq.com/s/TdqGgGK_LztBeKkLYF00qw",
            WeReadLinkNormalizer.articleUrlFromOriginalId("TdqGgGK~LztBeKkLYF00qw"),
        )
        assertEquals(
            "https://mp.weixin.qq.com/s?__biz=MzA%7E&mid=1&idx=1",
            WeReadLinkNormalizer.articleUrlFromOriginalId("?__biz=MzA~&mid=1&idx=1"),
        )
    }

    @Test
    fun `external hosts and malformed full identities are rejected`() {
        assertNull(WeReadLinkNormalizer.sanitizeArticleUrl("https://example.com/s/abc"))
        assertNull(WeReadLinkNormalizer.sanitizeArticleUrl("https://mp.weixin.qq.com:8443/s/abc"))
        assertNull(WeReadLinkNormalizer.sanitizeArticleUrl("https://mp.weixin.qq.com/s?__biz=b&mid=1"))
        assertNull(WeReadLinkNormalizer.sanitizeArticleUrl("https://mp.weixin.qq.com/s/token/extra"))
    }

    @Test
    fun `http full identity and trailing slash canonicalize to one https identity`() {
        assertEquals(
            "https://mp.weixin.qq.com/s?__biz=b&mid=1&idx=2",
            WeReadLinkNormalizer.sanitizeArticleUrl(
                "http://mp.weixin.qq.com/s/?__biz=b&mid=1&idx=2&pass_ticket=x&scene=4",
            ),
        )
    }
}
