package com.wamp.download.weread.eink

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wamp.download.config.AppConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.random.Random

class WeReadEinkApiException(
    val kind: WeReadEinkResponseKind,
    val statusCode: Int,
    val errCode: Int?,
    val errMsg: String?,
) : RuntimeException(
    "WeRead E-Ink API failed: kind=$kind, http=$statusCode, errCode=$errCode, errMsg=${errMsg.orEmpty()}".trim(),
)

class WeReadEinkParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class WeReadEinkMpInfo(
    val originalId: String? = null,
    val docUrl: String? = null,
    val picUrl: String? = null,
    val title: String? = null,
    val content: String? = null,
    val mpName: String? = null,
    val avatar: String? = null,
    val time: Long? = null,
    val payType: Long? = null,
    val inner: Long? = null,
)

data class WeReadEinkArticle(
    val reviewId: String? = null,
    val title: String? = null,
    val createTime: Long? = null,
    val mpInfo: WeReadEinkMpInfo? = null,
)

data class WeReadEinkArticlesPage(
    val accountId: String,
    val articles: List<WeReadEinkArticle>,
    val returnedCount: Int,
    val requestedOffset: Long,
    val nextOffset: Long? = null,
    val synckey: Long? = null,
    val hasMore: Int? = null,
)

class WeReadEinkChaptersClient(
    private val credentials: WeReadEinkCredentials,
    private val transport: WeReadEinkTransport = OkHttpWeReadEinkTransport(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val profile: WeReadEinkProfile = DefaultWeReadEinkProfile,
    private val session: WeReadEinkSession? = null,
    private val requestGate: (String, () -> WeReadEinkArticlesPage) -> WeReadEinkArticlesPage = { vid, block ->
        WeReadEinkRequestGate.execute(vid, block)
    },
    private val maxTransientRetries: Int = AppConfig.wereadEinkMaxTransientRetries,
    private val retryBaseDelayMs: Long = AppConfig.wereadEinkRetryBaseDelay * 1000L,
    private val waitProvider: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun fetchArticles(
        accountId: String,
        count: Int = DEFAULT_COUNT,
        synckey: Long? = null,
        offset: Long? = null,
    ): WeReadEinkArticlesPage {
        require(accountId.isNotBlank()) { "accountId must not be blank" }
        require(count in 1..MAX_COUNT) { "count must be between 1 and $MAX_COUNT" }
        require(offset == null || offset >= 0) { "offset must not be negative" }
        require(synckey == null || synckey >= 0) { "synckey must not be negative" }
        require(offset == null || synckey == null) {
            "synckey and offset are mutually exclusive for /mp/chapters"
        }

        val requestedOffset = offset ?: 0L
        val activeCredentials = session?.credentialsForRequest() ?: credentials

        return try {
            requestPageWithRetries(accountId, count, synckey, offset, requestedOffset, activeCredentials)
        } catch (error: WeReadEinkApiException) {
            if (error.kind != WeReadEinkResponseKind.AUTH_EXPIRED || session == null) throw error
            val refreshed = session.refreshIfAccessTokenWas(activeCredentials.accessToken)
            try {
                requestPageWithRetries(accountId, count, synckey, offset, requestedOffset, refreshed)
            } catch (retryError: WeReadEinkApiException) {
                if (retryError.kind == WeReadEinkResponseKind.AUTH_EXPIRED) {
                    session.reportAuthenticationFailure(retryError)
                }
                throw retryError
            }
        }
    }

    private fun requestPageWithRetries(
        accountId: String,
        count: Int,
        synckey: Long?,
        offset: Long?,
        requestedOffset: Long,
        requestCredentials: WeReadEinkCredentials,
    ): WeReadEinkArticlesPage {
        var transientFailures = 0
        while (true) {
            try {
                return requestGate(requestCredentials.vid) {
                    requestPage(accountId, count, synckey, offset, requestedOffset, requestCredentials)
                }
            } catch (error: WeReadEinkApiException) {
                val retryable = error.kind == WeReadEinkResponseKind.RATE_LIMITED ||
                    error.kind == WeReadEinkResponseKind.RETRYABLE_HTTP_ERROR
                if (!retryable || transientFailures >= maxTransientRetries) throw error
                val multiplier = 1L shl transientFailures.coerceAtMost(10)
                val delayMs = retryBaseDelayMs * multiplier + Random.nextLong(0L, 250L)
                transientFailures++
                waitProvider(delayMs)
            } catch (error: IOException) {
                // DNS/connect/read failures are transient at this layer. Let the bounded
                // retry budget absorb a short network flap without changing auth state.
                if (transientFailures >= maxTransientRetries) throw error
                val multiplier = 1L shl transientFailures.coerceAtMost(10)
                val delayMs = retryBaseDelayMs * multiplier + Random.nextLong(0L, 250L)
                transientFailures++
                waitProvider(delayMs)
            }
        }
    }

    private fun requestPage(
        accountId: String,
        count: Int,
        synckey: Long?,
        offset: Long?,
        requestedOffset: Long,
        requestCredentials: WeReadEinkCredentials,
    ): WeReadEinkArticlesPage {
        val request = WeReadEinkRequest(
            method = "GET",
            url = chaptersUrl(accountId, count, synckey, offset),
            headers = profile.versionHeaders + profile.authHeaders(requestCredentials),
        )
        val response = transport.execute(request)
        val classification = WeReadEinkResponseClassifier.classify(response)
        if (classification.kind != WeReadEinkResponseKind.SUCCESS) {
            throw WeReadEinkApiException(
                kind = classification.kind,
                statusCode = response.statusCode,
                errCode = classification.errCode,
                errMsg = classification.errMsg,
            )
        }
        return parsePage(response.body, accountId, count, requestedOffset)
    }

    private fun chaptersUrl(
        accountId: String,
        count: Int,
        synckey: Long?,
        offset: Long?,
    ): HttpUrl {
        val builder = baseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("mp")
            .addPathSegment("chapters")
            .addQueryParameter("bookId", accountId)
            .addQueryParameter("count", count.toString())
        if (offset != null) {
            builder.addQueryParameter("offset", offset.toString())
        } else {
            builder.addQueryParameter("synckey", (synckey ?: 0L).toString())
        }
        return builder.build()
    }

    private fun parsePage(
        raw: String,
        accountId: String,
        count: Int,
        requestedOffset: Long,
    ): WeReadEinkArticlesPage {
        try {
            val root = JsonParser.parseString(raw)
            if (!root.isJsonObject) throw WeReadEinkParseException("/mp/chapters returned a non-object body")
            val obj = root.asJsonObject
            val data = obj.get("data")
            if (data == null || !data.isJsonArray) {
                throw WeReadEinkParseException("/mp/chapters response does not contain a data array")
            }
            val articles = data.asJsonArray.map { parseArticle(it) }
            val synckey = longField(obj, "synckey")
            val hasMore = longField(obj, "hasMore")?.toInt()?.takeIf { it == 0 || it == 1 }
            val nextOffset = if (articles.size >= count) {
                Math.addExact(requestedOffset, articles.size.toLong())
            } else {
                null
            }
            return WeReadEinkArticlesPage(
                accountId = accountId,
                articles = articles,
                returnedCount = articles.size,
                requestedOffset = requestedOffset,
                nextOffset = nextOffset,
                synckey = synckey,
                hasMore = hasMore,
            )
        } catch (error: WeReadEinkParseException) {
            throw error
        } catch (error: Exception) {
            throw WeReadEinkParseException("failed to parse /mp/chapters response", error)
        }
    }

    private fun parseArticle(element: JsonElement): WeReadEinkArticle {
        if (!element.isJsonObject) return WeReadEinkArticle()
        val obj = element.asJsonObject
        val mpInfo = obj.get("mpInfo")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.let(::parseMpInfo)
        return WeReadEinkArticle(
            reviewId = stringField(obj, "reviewId"),
            title = stringField(obj, "title"),
            createTime = longField(obj, "createTime"),
            mpInfo = mpInfo,
        )
    }

    private fun parseMpInfo(obj: JsonObject): WeReadEinkMpInfo = WeReadEinkMpInfo(
        originalId = stringField(obj, "originalId"),
        docUrl = stringField(obj, "doc_url"),
        picUrl = stringField(obj, "pic_url"),
        title = stringField(obj, "title"),
        content = stringField(obj, "content"),
        mpName = stringField(obj, "mp_name"),
        avatar = stringField(obj, "avatar"),
        time = longField(obj, "time"),
        payType = longField(obj, "payType"),
        inner = longField(obj, "inner"),
    )

    private fun stringField(obj: JsonObject, name: String): String? = obj.get(name)
        ?.takeIf { it.isJsonPrimitive }
        ?.let { runCatching { it.asString }.getOrNull() }

    private fun longField(obj: JsonObject, name: String): Long? = obj.get(name)
        ?.takeIf { it.isJsonPrimitive }
        ?.let { runCatching { it.asLong }.getOrNull() }

    private companion object {
        const val DEFAULT_BASE_URL = "https://i.weread.qq.com"
        const val DEFAULT_COUNT = 50
        const val MAX_COUNT = 100
    }
}
