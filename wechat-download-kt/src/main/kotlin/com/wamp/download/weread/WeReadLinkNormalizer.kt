package com.wamp.download.weread

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object WeReadLinkNormalizer {
    private const val MP_WXS_PREFIX = "MP_WXS_"
    private const val ARTICLE_HOST = "mp.weixin.qq.com"

    fun normalizeShortId(value: String): String = value.trim().replace('~', '_')

    fun shortLinkFromReviewId(reviewId: String): String? {
        val trimmed = reviewId.trim()
        if (!trimmed.startsWith(MP_WXS_PREFIX)) return null
        val rest = trimmed.removePrefix(MP_WXS_PREFIX)
        val separator = rest.indexOf('_')
        if (separator <= 0 || separator >= rest.lastIndex) return null
        val token = rest.substring(separator + 1).trim()
        if (token.isEmpty()) return null
        return "https://$ARTICLE_HOST/s/${normalizeShortId(token)}"
    }

    fun articleUrlFromOriginalId(originalId: String): String? {
        val trimmed = originalId.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return normalizeArticleUrl(trimmed)
        }
        if (trimmed.startsWith("/s")) {
            return normalizeArticleUrl("https://$ARTICLE_HOST$trimmed")
        }
        if (trimmed.contains("__biz=") || trimmed.startsWith("?")) {
            return "https://$ARTICLE_HOST/s?${trimmed.removePrefix("?")}"
        }
        return "https://$ARTICLE_HOST/s/${normalizeShortId(trimmed)}"
    }

    fun normalizeArticleUrl(url: String): String {
        val trimmed = url.trim()
        val httpUrl = trimmed.toHttpUrlOrNull() ?: return trimmed
        if (httpUrl.host != ARTICLE_HOST) return trimmed
        val segments = httpUrl.pathSegments
        if (segments.size < 2 || segments[0] != "s") return trimmed
        val token = segments[1]
        val normalized = normalizeShortId(token)
        if (normalized == token) return trimmed
        return httpUrl.newBuilder().setPathSegment(1, normalized).build().toString()
    }
}
