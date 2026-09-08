package com.wamp.download.article

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Base64

class ArticleIdentityException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class ArticleIdentity(
    val articleUrl: String,
    val resolvedUrl: String,
    val biz: String,
    val bid: String,
    val bookId: String,
    val title: String,
    val accountName: String,
    val method: String
)

object ArticleIdentityResolver {

    private fun padB64(value: String): String {
        val v = value.trim()
        val padLength = (4 - v.length % 4) % 4
        return v + "=".repeat(padLength)
    }

    private fun decodeBiz(value: String): String {
        val trimmed = java.net.URLDecoder.decode(value, "UTF-8").trim()
        val decoded = try {
            String(Base64.getDecoder().decode(padB64(trimmed)), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            throw ArticleIdentityException("biz 不是有效 Base64: ${e.message}", e)
        }
        if (decoded.toLongOrNull() == null) {
            throw ArticleIdentityException("biz 解码结果不是数字公众号 ID: '$decoded'")
        }
        return decoded
    }

    private fun bizFromUrl(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        return httpUrl.queryParameter("__biz") ?: httpUrl.queryParameter("biz")
    }

    private fun cleanJsString(value: String?): String {
        var v = value ?: return ""
        v = org.jsoup.parser.Parser.unescapeEntities(v, false)
        v = v.replace("\\/", "/")
            .replace("\\x26", "&").replace("\\u0026", "&")
            .replace("\\x3d", "=").replace("\\u003d", "=")
            .replace("\\x3f", "?").replace("\\u003f", "?")
        return v
    }

    private fun firstMeta(soup: Document, prop: String? = null, name: String? = null): String {
        if (prop != null) {
            val content = soup.selectFirst("meta[property=$prop]")?.attr("content")
            if (content != null) return content.trim()
        }
        if (name != null) {
            val content = soup.selectFirst("meta[name=$name]")?.attr("content")
            if (content != null) return content.trim()
        }
        return ""
    }

    fun extractIdentityFromHtml(htmlText: String, sourceUrl: String, resolvedUrl: String = sourceUrl): ArticleIdentity? {
        val candidates = mutableListOf<Pair<String, String>>()
        
        // 1. Check direct URL first
        val directBiz = bizFromUrl(resolvedUrl)
        if (!directBiz.isNullOrBlank()) {
            candidates.add(directBiz to "redirect-url")
        }

        // 2. Direct JS variables (window.biz, var biz, __biz)
        val bizPatterns = listOf(
            Regex("""(?:window\.)?biz\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""var\s+biz\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""__biz\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        )
        for (pattern in bizPatterns) {
            val match = pattern.find(htmlText)
            if (match != null) {
                candidates.add(cleanJsString(match.groupValues[1]) to "html-biz")
                break
            }
        }

        // 3. Canonical long link stored in msg_link
        val linkPatterns = listOf(
            Regex("""(?:window\.)?msg_link\s*=\s*['"](.+?)['"]\s*;""", RegexOption.IGNORE_CASE),
            Regex("""var\s+msg_link\s*=\s*['"](.+?)['"]\s*;""", RegexOption.IGNORE_CASE)
        )
        for (pattern in linkPatterns) {
            val match = pattern.find(htmlText)
            if (match != null) {
                val link = cleanJsString(match.groupValues[1])
                val candidate = bizFromUrl(link)
                if (!candidate.isNullOrBlank()) {
                    candidates.add(candidate to "html-msg-link")
                    break
                }
            }
        }

        val soup = Jsoup.parse(htmlText)

        // 4. Metadata/canonical URL fallbacks
        val urlCandidates = mutableListOf<String>()
        val ogUrl = firstMeta(soup, prop = "og:url")
        if (ogUrl.isNotBlank()) urlCandidates.add(cleanJsString(ogUrl))
        
        val twitterUrl = firstMeta(soup, name = "twitter:url")
        if (twitterUrl.isNotBlank()) urlCandidates.add(cleanJsString(twitterUrl))
        
        val canonicalHref = soup.selectFirst("link[rel~=(?i)canonical]")?.attr("href")
        if (canonicalHref != null) {
            urlCandidates.add(cleanJsString(canonicalHref))
        }
        
        for (link in urlCandidates) {
            val candidate = bizFromUrl(link)
            if (!candidate.isNullOrBlank()) {
                candidates.add(candidate to "html-canonical")
                break
            }
        }

        // 5. Last static fallback: embedded urls
        if (candidates.isEmpty()) {
            val embeddedRegex = Regex("""https?://mp\.weixin\.qq\.com/s\?[^"'<>\s]+""", RegexOption.IGNORE_CASE)
            for (match in embeddedRegex.findAll(htmlText)) {
                val link = cleanJsString(match.value)
                val candidate = bizFromUrl(link)
                if (!candidate.isNullOrBlank()) {
                    candidates.add(candidate to "html-embedded-url")
                    break
                }
            }
        }

        if (candidates.isEmpty()) return null

        var validBiz = ""
        var validBid = ""
        var validMethod = ""

        for ((biz, method) in candidates) {
            try {
                validBid = decodeBiz(biz)
                validBiz = biz
                validMethod = method
                break
            } catch (e: ArticleIdentityException) {
                continue
            }
        }

        if (validBiz.isEmpty()) return null

        var title = firstMeta(soup, prop = "og:title").takeIf { it.isNotBlank() }
            ?: firstMeta(soup, name = "twitter:title").takeIf { it.isNotBlank() }
            ?: soup.title().takeIf { it.isNotBlank() }
            ?: ""
            
        var account = firstMeta(soup, prop = "og:article:author").takeIf { it.isNotBlank() }
        if (account.isNullOrBlank()) {
            account = soup.selectFirst("#js_name, #js_wx_follow_nickname")?.text() ?: ""
        }
        if (account.isBlank()) {
            val nicknameMatch = Regex("""(?:var\s+)?nickname\s*=\s*['"](.+?)['"]\s*;""", RegexOption.IGNORE_CASE).find(htmlText)
            if (nicknameMatch != null) {
                account = cleanJsString(nicknameMatch.groupValues[1]).trim()
            }
        }

        return ArticleIdentity(
            articleUrl = sourceUrl,
            resolvedUrl = resolvedUrl,
            biz = validBiz,
            bid = validBid,
            bookId = "MP_WXS_$validBid",
            title = org.jsoup.parser.Parser.unescapeEntities(title, false).trim(),
            accountName = org.jsoup.parser.Parser.unescapeEntities(account, false).trim(),
            method = validMethod
        )
    }
}
