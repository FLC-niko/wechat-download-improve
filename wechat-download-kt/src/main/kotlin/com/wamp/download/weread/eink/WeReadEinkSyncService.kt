package com.wamp.download.weread.eink

import com.wamp.download.store.Article
import com.wamp.download.store.ArticleStore
import com.wamp.download.store.PublicAccountCollectionCheckpointStore
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

class WeReadEinkBackgroundYieldException : RuntimeException("real-time task requires the E-Ink channel")

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
        if (PublicAccountCollectionCheckpointStore.covers(subscription.fakeid, startTime, endTime)) {
            return true
        }
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
            syncUnlocked(
                subscription = subscription,
                mpId = mpId,
                requestedStartTime = startTime,
                requestedEndTime = endTime,
            )
        }
    }

    /** Fetches all pages and commits the new cursor only after the entire cycle succeeds. */
    fun sync(
        subscription: Subscription,
        shouldContinue: () -> Boolean = { true },
    ): WeReadEinkSyncResult {
        val mpId = resolveMpId(subscription)
        if (mpId.isBlank()) {
            throw WeReadEinkSyncException("cannot derive E-Ink mpId for fakeid=${subscription.fakeid.take(8)}...")
        }
        return withSyncLock(session.credentials().vid, mpId) {
            ensureShouldContinue(shouldContinue)
            syncUnlocked(subscription, mpId, shouldContinue)
        }
    }

    private fun syncUnlocked(
        subscription: Subscription,
        mpId: String,
        shouldContinue: () -> Boolean = { true },
        requestedStartTime: Long? = null,
        requestedEndTime: Long? = null,
    ): WeReadEinkSyncResult {
        require(subscription.fakeid.isNotBlank()) { "subscription fakeid must not be blank" }
        require((requestedStartTime == null) == (requestedEndTime == null)) {
            "requestedStartTime and requestedEndTime must both be set or both be null"
        }
        require(requestedStartTime == null || requestedStartTime <= requestedEndTime!!) {
            "requestedStartTime must not be after requestedEndTime"
        }

        val fakeid = subscription.fakeid
        val accountVid = session.credentials().vid
        val scanStartedAt = System.currentTimeMillis() / 1000
        val storedState = WeReadEinkSyncStateStore.get(accountVid, mpId)
        // Version 0 includes historical rows that could be marked complete even when every
        // returned record failed mapping. Re-baseline those rows from synckey=0.
        val previousState = storedState?.takeIf {
            it.coverageVersion == WeReadEinkSyncStateStore.CURRENT_COVERAGE_VERSION
        }
        val initialSynckey = previousState?.synckey ?: 0L
        val client = clientFactory(session, baseUrl)
        var pages = 0
        var fetchedArticles = 0
        var skippedArticles = 0
        var savedArticles = 0
        var requestedOffset: Long? = null
        var nextSynckey: Long? = previousState?.synckey
        var cycleComplete = false
        var targetedRangeComplete = false
        var filteredForRequestedRange = false
        var cycleOldestCollectedAt: Long? = null

        try {
            while (pages < maxPages) {
                ensureShouldContinue(shouldContinue)
                val page = if (requestedOffset == null) {
                    client.fetchArticles(mpId, count = pageSize, synckey = initialSynckey)
                } else {
                    client.fetchArticles(mpId, count = pageSize, offset = requestedOffset)
                }
                pages++
                fetchedArticles += page.articles.size
                nextSynckey = page.synckey ?: nextSynckey

                val nextOffset = nextOffset(page)
                val naturalEnd = nextOffset == null
                if (nextOffset != null && nextOffset <= page.requestedOffset) {
                    throw WeReadEinkSyncException(
                        "E-Ink pagination did not advance: offset=${page.requestedOffset}, next=$nextOffset",
                    )
                }

                val pageTimes = page.articles.mapNotNull { it.collectionTime() }.filter { it > 0L }
                val reachedRequestedStart = requestedStartTime != null &&
                    (naturalEnd || pageTimes.any { it <= requestedStartTime })
                val articlesToPersist = if (reachedRequestedStart) {
                    page.articles.filter { article ->
                        val publishTime = article.collectionTime()
                        publishTime == null || publishTime >= requestedStartTime!!
                    }
                } else {
                    page.articles
                }
                filteredForRequestedRange = filteredForRequestedRange ||
                    articlesToPersist.size != page.articles.size

                val cachedArticles = ArticleStore.getByFakeidAndAids(
                    fakeid,
                    articlesToPersist.mapNotNull(WeReadEinkArticle::reviewId),
                )
                val mapped = articlesToPersist.mapNotNull { article ->
                    ensureShouldContinue(shouldContinue)
                    article.toArticle(fakeid, client, cachedArticles[article.reviewId]) ?: run {
                        logger.warn(
                            "跳过无效的 E-Ink 文章: fakeid=${fakeid.take(8)}..., " +
                                "reviewId=${article.reviewId.orEmpty().take(32)}",
                        )
                        null
                    }
                }
                val pageSkippedArticles = articlesToPersist.size - mapped.size
                skippedArticles += pageSkippedArticles
                if (pageSkippedArticles > 0) {
                    throw WeReadEinkSyncException(
                        "E-Ink returned $pageSkippedArticles invalid article payloads out of " +
                            "${articlesToPersist.size} relevant records on page $pages; " +
                            "this page checkpoint was not advanced",
                    )
                }

                savedArticles += ArticleStore.saveAll(fakeid, mapped, source = "eink")

                val pageOldestCollectedAt = when {
                    reachedRequestedStart -> requestedStartTime
                    pageTimes.isNotEmpty() -> pageTimes.min()
                    naturalEnd -> requestedStartTime ?: 0L
                    else -> null
                }
                if (pageOldestCollectedAt != null) {
                    cycleOldestCollectedAt = minOf(
                        cycleOldestCollectedAt ?: pageOldestCollectedAt,
                        pageOldestCollectedAt,
                    )
                    val checkpoint = PublicAccountCollectionCheckpointStore.recordProgress(
                        fakeid = fakeid,
                        mpId = mpId,
                        oldestCollectedAt = cycleOldestCollectedAt!!,
                        newestCollectedAt = scanStartedAt,
                        pagesDelta = 1,
                        articlesDelta = mapped.size,
                    )
                    targetedRangeComplete = requestedStartTime != null && requestedEndTime != null &&
                        checkpoint.covers(requestedStartTime, requestedEndTime)
                }

                if (naturalEnd) cycleComplete = true
                if (targetedRangeComplete || naturalEnd) break
                requestedOffset = nextOffset
            }

            if (!cycleComplete && !targetedRangeComplete) {
                throw WeReadEinkSyncException("E-Ink pagination exceeded maxPages=$maxPages")
            }

            val completedFullCycle = cycleComplete && !filteredForRequestedRange
            val historyComplete = previousState?.historyComplete == true ||
                (completedFullCycle && initialSynckey == 0L)
            if (completedFullCycle) {
                val coverageStart = if (historyComplete) 0L else previousState?.coverageStart
                val coverageEnd = max(scanStartedAt, previousState?.coverageEnd ?: 0L)
                WeReadEinkSyncStateStore.saveSuccess(
                    accountVid = accountVid,
                    fakeid = fakeid,
                    mpId = mpId,
                    synckey = nextSynckey,
                    coverageStart = coverageStart,
                    coverageEnd = coverageEnd,
                    historyComplete = historyComplete,
                )
            }
            SubscriptionStore.updateLastPoll(fakeid)

            logger.info(
                "E-Ink 同步完成: fakeid=${fakeid.take(8)}..., pages=$pages, " +
                    "fetched=$fetchedArticles, skipped=$skippedArticles, saved=$savedArticles, " +
                    "targetCovered=$targetedRangeComplete, fullCycle=$completedFullCycle",
            )
            return WeReadEinkSyncResult(
                fakeid = fakeid,
                accountVid = accountVid,
                mpId = mpId,
                pages = pages,
                fetchedArticles = fetchedArticles,
                skippedArticles = skippedArticles,
                savedArticles = savedArticles,
                synckey = if (completedFullCycle) nextSynckey else previousState?.synckey,
                historyComplete = historyComplete,
            )
        } catch (error: Exception) {
            if (error is WeReadEinkBackgroundYieldException) throw error
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

    private fun ensureShouldContinue(shouldContinue: () -> Boolean) {
        if (!shouldContinue()) throw WeReadEinkBackgroundYieldException()
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

    private fun WeReadEinkArticle.collectionTime(): Long? = createTime ?: mpInfo?.time

    private fun WeReadEinkArticle.toArticle(
        fakeid: String,
        client: WeReadEinkChaptersClient,
        cachedArticle: Article? = null,
    ): Article? {
        var link = mpInfo?.docUrl
            ?.trim()
            ?.let { candidate -> candidate.toHttpUrlOrNull()?.toString() }

        if (link == null && cachedArticle?.link?.isNotBlank() == true) {
            link = cachedArticle.link
        }

        if (link == null && !reviewId.isNullOrBlank()) {
            logger.info("文章缺少 docUrl，尝试通过 /review/single 补全: reviewId=$reviewId")
            val backfilledUrl = try {
                client.fetchSingleReviewArticleUrl(reviewId)
            } catch (e: Exception) {
                logger.warn("回填 /review/single 失败: ${e.message}")
                null
            }
            link = backfilledUrl?.trim()?.takeIf { it.isNotBlank() }
        }
        
        if (link == null) return null
        
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
