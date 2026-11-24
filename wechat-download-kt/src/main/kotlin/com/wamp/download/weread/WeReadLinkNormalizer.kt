package com.wamp.download.weread

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object WeReadLinkNormalizer {
    private const val MP_WXS_PREFIX = "MP_WXS_"
    private const val ARTICLE_HOST = "mp.weixin.qq.com"
    private val identityQueryNames = listOf("__biz", "mid", "idx", "sn")

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
            return sanitizeArticleUrl(trimmed)
        }
        if (trimmed.startsWith("/s")) {
            return sanitizeArticleUrl("https://$ARTICLE_HOST$trimmed")
        }
        if (trimmed.contains("__biz=") || trimmed.startsWith("?")) {
            return sanitizeArticleUrl("https://$ARTICLE_HOST/s?${trimmed.removePrefix("?")}")
        }
        return sanitizeArticleUrl("https://$ARTICLE_HOST/s/${normalizeShortId(trimmed)}")
    }

    /** Returns a canonical article identity URL, or null for non article hosts/paths. */
    fun sanitizeArticleUrl(url: String): String? {
        val trimmed = url.trim()
        val httpUrl = trimmed.toHttpUrlOrNull() ?: return null
        if (httpUrl.scheme !in setOf("http", "https") || httpUrl.host != ARTICLE_HOST ||
            httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty() ||
            httpUrl.port != if (httpUrl.scheme == "https") 443 else 80
        ) return null
        val segments = httpUrl.pathSegments
        if (segments.firstOrNull() != "s") return null
        val builder = httpUrl.newBuilder().scheme("https").host(ARTICLE_HOST).username("").password("")
            .query(null).fragment(null)
        val token = segments.getOrNull(1).orEmpty()
        if (segments.size == 2 && token.isNotBlank()) {
            builder.setPathSegment(1, normalizeShortId(token))
            return builder.build().toString()
        }
        if (segments.size != 1 && !(segments.size == 2 && token.isBlank())) return null
        builder.encodedPath("/s")
        identityQueryNames.forEach { name ->
            httpUrl.queryParameterValues(name).firstOrNull()?.let { builder.addQueryParameter(name, it) }
        }
        if (httpUrl.queryParameter("__biz").isNullOrBlank() ||
            httpUrl.queryParameter("mid").isNullOrBlank() ||
            httpUrl.queryParameter("idx").isNullOrBlank()
        ) return null
        return builder.build().toString()
    }

    fun normalizeArticleUrl(url: String): String {
        val trimmed = url.trim()
        return sanitizeArticleUrl(trimmed) ?: trimmed
    }

}
