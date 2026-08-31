package com.wamp.download.weread.eink

import com.wamp.download.store.Article
import com.wamp.download.store.ArticleStore
import com.wamp.download.store.Subscription
import com.wamp.download.store.SubscriptionStore
import com.wamp.download.store.WeReadEinkSyncStateStore
import com.wamp.download.config.AppConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

class WeReadEinkSyncException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class WeReadEinkSyncResult(
    val fakeid: String,
    val accountVid: String,
    val mpId: String,
    val pages: Int,
    val fetchedArticles: Int,
    val skippedArticles: Int,
    val savedArticles: Int,
    val synckey: Long?,
    val historyComplete: Boolean,
    val cacheCovered: Boolean = false,
)

/** Synchronizes one subscribed public account through the E-Ink chapters endpoint. */
class WeReadEinkSyncService(
    private val session: WeReadEinkSession,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val clientFactory: (WeReadEinkSession, String) -> WeReadEinkChaptersClient = { activeSession, url ->
        WeReadEinkChaptersClient(
            credentials = activeSession.credentials(),
            baseUrl = url,
            session = activeSession,
        )
    },
) {
    private val logger = LoggerFactory.getLogger(WeReadEinkSyncService::class.java)

    init {
        require(pageSize in 1..100) { "pageSize must be between 1 and 100" }
        require(maxPages >= 1) { "maxPages must be at least 1" }
    }

    /** Returns true only when persisted state proves the complete requested range was checked. */
    fun isRangeCovered(subscription: Subscription, startTime: Long, endTime: Long): Boolean {
        require(startTime <= endTime) { "startTime must not be after endTime" }
        val mpId = resolveMpId(subscription)
        if (mpId.isBlank()) return false
        val state = WeReadEinkSyncStateStore.get(session.credentials().vid, mpId) ?: return false
        return state.coverageVersion == WeReadEinkSyncStateStore.CURRENT_COVERAGE_VERSION &&
            state.historyComplete &&
            state.coverageStart != null &&
            state.coverageEnd != null &&
            state.coverageStart <= startTime &&
            state.coverageEnd >= endTime
    }

    /** Returns whether this subscription has enough local identity to use E-Ink sync. */
    fun canSync(subscription: Subscription): Boolean = resolveMpId(subscription).isNotBlank()

    fun isSyncDue(
        subscription: Subscription,
        intervalSeconds: Long,
        now: Long = System.currentTimeMillis() / 1000,
    ): Boolean {
        require(intervalSeconds >= 0) { "intervalSeconds must not be negative" }
        val mpId = resolveMpId(subscription)
        if (mpId.isBlank()) return false
        val state = WeReadEinkSyncStateStore.get(session.credentials().vid, mpId) ?: return true
        if (state.status == "FAILED" && state.lastSyncAt > 0L) {
            return now - state.lastSyncAt >= AppConfig.wereadEinkSyncRetryBackoff
        }
        return state.lastSuccessAt <= 0L || now - state.lastSuccessAt >= intervalSeconds
    }

    /** Performs one authenticated request without advancing the persisted cursor. */
    fun probe(subscription: Subscription): Boolean {
        val mpId = resolveMpId(subscription)
        if (mpId.isBlank()) return false
        val accountVid = session.credentials().vid
        val synckey = WeReadEinkSyncStateStore.get(accountVid, mpId)?.synckey ?: 0L
        clientFactory(session, baseUrl).fetchArticles(mpId, count = 1, synckey = synckey)
        return true
    }

    /** Uses cache when the requested range is already covered, otherwise performs one complete sync cycle. */
    fun syncIfNeeded(
        subscription: Subscription,
        startTime: Long,
        endTime: Long,
    ): WeReadEinkSyncResult {
        require(startTime <= endTime) { "startTime must not be after endTime" }
        val mpId = resolveMpId(subscription)
        if (mpId.isBlank()) {
            throw WeReadEinkSyncException("cannot derive E-Ink mpId for fakeid=${subscription.fakeid.take(8)}...")
        }
        return withSyncLock(session.credentials().vid, mpId) {
            if (isRangeCovered(subscription, startTime, endTime)) {
                return@withSyncLock WeReadEinkSyncResult(
                    fakeid = subscription.fakeid,
                    accountVid = session.credentials().vid,
                    mpId = mpId,
                    pages = 0,
                    fetchedArticles = 0,
                    skippedArticles = 0,
                    savedArticles = 0,
                    synckey = WeReadEinkSyncStateStore.get(session.credentials().vid, mpId)?.synckey,
                    historyComplete = true,
                    cacheCovered = true,
                )
            }
            syncUnlocked(subscription, mpId)
        }
    }

    /** Fetches all pages and commits the new cursor only after the entire cycle succeeds. */
    fun sync(subscription: Subscription): WeReadEinkSyncResult {
        val mpId = resolveMpId(subscription)
        if (mpId.isBlank()) {
            throw WeReadEinkSyncException("cannot derive E-Ink mpId for fakeid=${subscription.fakeid.take(8)}...")
        }
        return withSyncLock(session.credentials().vid, mpId) {
            syncUnlocked(subscription, mpId)
        }
    }

    private fun syncUnlocked(subscription: Subscription, mpId: String): WeReadEinkSyncResult {
        require(subscription.fakeid.isNotBlank()) { "subscription fakeid must not be blank" }

        val fakeid = subscription.fakeid
        val accountVid = session.credentials().vid
        val storedState = WeReadEinkSyncStateStore.get(accountVid, mpId)
        // Version 0 includes historical rows that could be marked complete even when every
        // returned record failed mapping. Re-baseline those rows from synckey=0.
        val previousState = storedState?.takeIf {
            it.coverageVersion == WeReadEinkSyncStateStore.CURRENT_COVERAGE_VERSION
        }
        val initialSynckey = previousState?.synckey ?: 0L
        val client = clientFactory(session, baseUrl)
        val collected = mutableListOf<WeReadEinkArticle>()
        var pages = 0
        var requestedOffset: Long? = null
        var nextSynckey: Long? = previousState?.synckey
        var cycleComplete = false

        try {
            while (pages < maxPages) {
                val page = if (requestedOffset == null) {
                    client.fetchArticles(mpId, count = pageSize, synckey = initialSynckey)
                } else {
                    client.fetchArticles(mpId, count = pageSize, offset = requestedOffset)
                }
                pages++
                collected += page.articles
                nextSynckey = page.synckey ?: nextSynckey

                val nextOffset = nextOffset(page)
                if (nextOffset == null) {
                    cycleComplete = true
                    break
                }
                if (nextOffset <= page.requestedOffset) {
                    throw WeReadEinkSyncException(
                        "E-Ink pagination did not advance: offset=${page.requestedOffset}, next=$nextOffset",
                    )
                }
                requestedOffset = nextOffset
            }

            if (!cycleComplete) {
                throw WeReadEinkSyncException("E-Ink pagination exceeded maxPages=$maxPages")
            }

            val mapped = collected.mapNotNull { article ->
                article.toArticle(fakeid) ?: run {
                    logger.warn(
                        "跳过无效的 E-Ink 文章: fakeid=${fakeid.take(8)}..., " +
                            "reviewId=${article.reviewId.orEmpty().take(32)}",
                    )
                    null
                }
            }
            val skippedArticles = collected.size - mapped.size
            val savedArticles = ArticleStore.saveAll(fakeid, mapped, source = "eink")
            if (skippedArticles > 0) {
                throw WeReadEinkSyncException(
                    "E-Ink returned $skippedArticles invalid article payloads out of ${collected.size}; " +
                        "cursor and coverage were not advanced",
                )
            }
            val historyComplete = previousState?.historyComplete == true || initialSynckey == 0L
            val coverageStart = if (historyComplete) 0L else previousState?.coverageStart
            val coverageEnd = max(
                System.currentTimeMillis() / 1000,
                previousState?.coverageEnd ?: 0L,
            )

            WeReadEinkSyncStateStore.saveSuccess(
                accountVid = accountVid,
                fakeid = fakeid,
                mpId = mpId,
                synckey = nextSynckey,
                coverageStart = coverageStart,
                coverageEnd = coverageEnd,
                historyComplete = historyComplete,
            )
            SubscriptionStore.updateLastPoll(fakeid)

            logger.info(
                "E-Ink 同步完成: fakeid=${fakeid.take(8)}..., pages=$pages, " +
                    "fetched=${collected.size}, skipped=$skippedArticles, saved=$savedArticles",
            )
            return WeReadEinkSyncResult(
                fakeid = fakeid,
                accountVid = accountVid,
                mpId = mpId,
                pages = pages,
                fetchedArticles = collected.size,
                skippedArticles = skippedArticles,
                savedArticles = savedArticles,
                synckey = nextSynckey,
                historyComplete = historyComplete,
            )
        } catch (error: Exception) {
            val errorType = when (error) {
                is WeReadEinkApiException -> error.kind.name
                is WeReadEinkRefreshBackoffException -> "REFRESH_BACKOFF"
                is WeReadEinkParseException -> "PARSE_ERROR"
                is WeReadEinkSyncException -> "SYNC_ERROR"
                else -> "ERROR"
            }
            runCatching {
                WeReadEinkSyncStateStore.saveFailure(
                    accountVid = accountVid,
                    fakeid = fakeid,
                    mpId = mpId,
                    errorType = errorType,
                    errorMessage = error.message ?: errorType,
                )
            }.onFailure { storeError ->
                logger.warn("保存 E-Ink 同步失败状态异常: ${storeError.message}")
            }
            throw error
        }
    }

    private fun <T> withSyncLock(accountVid: String, mpId: String, block: () -> T): T {
        val key = "$accountVid:$mpId"
        val lock = SYNC_LOCKS.computeIfAbsent(key) { ReentrantLock() }
        return lock.withLock(block)
    }

    private fun nextOffset(page: WeReadEinkArticlesPage): Long? = when (page.hasMore) {
        0 -> null
        1 -> page.nextOffset
            ?: throw WeReadEinkSyncException("E-Ink response says hasMore=1 but has no next offset")
        else -> page.nextOffset
    }

    private fun resolveMpId(subscription: Subscription): String {
        val existing = subscription.wereadMpId.trim()
        if (existing.isNotBlank()) return existing

        val decoded = runCatching {
            Base64.getDecoder().decode(subscription.fakeid).toString(Charsets.UTF_8).trim()
        }.getOrNull().orEmpty()
        if (decoded.isBlank()) return ""

        val mpId = "MP_WXS_$decoded"
        SubscriptionStore.saveWereadMpId(subscription.fakeid, mpId)
        return mpId
    }

    private fun WeReadEinkArticle.toArticle(fakeid: String): Article? {
        val link = mpInfo?.docUrl
            ?.trim()
            ?.let { candidate -> candidate.toHttpUrlOrNull()?.toString() }
            ?: return null
        val title = title?.takeIf { it.isNotBlank() }
            ?: mpInfo?.title?.takeIf { it.isNotBlank() }
            ?: return null
        return Article(
            fakeid = fakeid,
            aid = reviewId?.takeIf { it.isNotBlank() } ?: mpInfo?.originalId.orEmpty(),
            title = title,
            link = link,
            digest = mpInfo?.content.orEmpty(),
            cover = mpInfo?.picUrl.orEmpty(),
            author = mpInfo?.mpName.orEmpty(),
            publishTime = createTime ?: mpInfo?.time ?: 0L,
        )
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://i.weread.qq.com"
        const val DEFAULT_PAGE_SIZE = 50
        const val DEFAULT_MAX_PAGES = 1000
        val SYNC_LOCKS = ConcurrentHashMap<String, ReentrantLock>()
    }
}
