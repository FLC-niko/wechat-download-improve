package com.wamp.download.task

import com.wamp.download.auth.LoginChecker
import com.wamp.download.config.AppConfig
import com.wamp.download.rabbitmq.GzhAutoBta
import com.wamp.download.rabbitmq.GzhAutoFromBackend
import com.wamp.download.rabbitmq.RabbitBusiness
import com.wamp.download.rabbitmq.RabbitProducer
import com.wamp.download.rabbitmq.CaptureWindow
import com.wamp.download.rabbitmq.CollectionOptions
import com.wamp.download.rabbitmq.GzhAccount
import com.wamp.download.rabbitmq.GzhAutoBtaPayload
import com.wamp.download.rabbitmq.AtdError
import com.wamp.download.rabbitmq.RABBIT_SCHEMA_VERSION
import com.wamp.download.rabbitmq.ResultQueuePublisher
import com.wamp.download.rabbitmq.RabbitTaskMessage
import com.wamp.download.rabbitmq.SeedArticle
import com.wamp.download.store.Article
import com.wamp.download.store.ArticleCoverageStore
import com.wamp.download.store.ArticleStore
import com.wamp.download.store.Subscription
import com.wamp.download.store.SubscriptionStore
import com.wamp.download.weread.WeReadWebClient
import com.wamp.download.weread.eink.WeReadEinkSyncService
import com.wamp.download.weread.eink.WeReadEinkRuntime
import com.wamp.download.article.ArticleIdentityResolver
import com.wamp.download.wx.WxApiClient
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * 公众号任务处理器
 *
 * 处理流程：本地身份与缓存优先，只对没有可信覆盖记录的时间缺口请求远端；远端结果
 * 先写缓存与覆盖记录，再从统一缓存视图发布结果。
 *
 * 与 Worker 的区别：
 * - 直接调微信 API，不经过适配壳 HTTP 中转
 * - 文章存 SQLite，不存 JSON 文件
 * - 只获取文章链接元数据，不抓正文
 * - 微信读书官方直连是当前主采集通道（第三方代理已废弃）
 */
class TaskProcessor(
    private val wxClient: WxApiClient = WxApiClient(),
    private val weReadWebClient: WeReadWebClient = WeReadWebClient(),
    private val loginChecker: LoginChecker = LoginChecker(),
    private val resultPublisher: ResultQueuePublisher? = null,
    private val einkSyncService: WeReadEinkSyncService? = null,
    private val einkSyncServiceProvider: (() -> WeReadEinkSyncService?)? = null,
    private val einkRequestAllowed: () -> Boolean = WeReadEinkRuntime::canAttemptTaskSync,
    private val einkRequestSucceeded: (WeReadEinkSyncService) -> Unit = WeReadEinkRuntime::recordTaskRequestSuccess,
    private val einkRequestFailed: (WeReadEinkSyncService, Throwable) -> Unit = WeReadEinkRuntime::recordTaskRequestFailure,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(TaskProcessor::class.java)
    }

    /**
     * 处理一条任务
     */
    suspend fun process(task: GzhAutoFromBackend) {
        process(task, resultPublisher)
    }

    suspend fun process(task: GzhAutoFromBackend, publisher: ResultQueuePublisher?) {
        processTask(
            task = ProcessingTask(
                gzhName = task.gzhName,
                dateRange = task.tempTimeStamp,
                jobId = task.jobId,
                correlationId = task.correlationId,
                publisher = publisher,
                v2Task = null,
            )
        )
    }

    suspend fun process(task: GzhAutoBta, publisher: ResultQueuePublisher) {
        processTask(
            task = ProcessingTask(
                gzhName = task.payload.account.name,
                dateRange = listOf(task.payload.captureWindow.startsAt, task.payload.captureWindow.endsAt),
                jobId = task.business.jobId,
                correlationId = task.workflowId,
                publisher = publisher,
                v2Task = task,
            )
        )
    }

    private suspend fun processTask(task: ProcessingTask) {
        RealtimeTaskActivity.taskStarted()
        try {
            processTrackedTask(task)
        } finally {
            RealtimeTaskActivity.taskFinished()
        }
    }

    private suspend fun processTrackedTask(task: ProcessingTask) {
        logger.info("【任务处理】开始: gzh=${task.gzhName}, 日期=${task.dateRange}, jobId=${task.jobId}")

        val (startTs, endTs) = parseDateRange(task.dateRange)
        if (startTs == null || endTs == null) {
            publishFailed(task, "INVALID_CAPTURE_WINDOW", "采集时间窗口格式非法或起止时间反向", retryable = false)
            return
        }
        // 远端接口只能翻页到“当前时刻”，窗口的未来时段既采不到也不应算作缺口；
        // 与 CollectionCoverage.remoteFetchEnvelope 的语义保持一致，避免当天窗口被误判为未覆盖。
        val requestStartedAt = System.currentTimeMillis() / 1000L
        val coverageEndTs = CollectionCoverage.effectiveCoverageEnd(startTs, endTs, requestStartedAt)

        // 本地订阅是所有回退通道共享的身份，不应因主微信登录失效而被挡住。
        var subscription = SubscriptionStore.findByNickname(task.gzhName)
        if (subscription == null) {
            if (!ensureMainLoginForLookup(task)) return
            subscription = try {
                findOrCreateSubscription(task.gzhName)?.let { SubscriptionStore.get(it) }
            } catch (e: Exception) {
                logger.error("【任务处理】搜索公众号失败: gzh=${task.gzhName}, error=${e.message}")
                if (e is WxApiClient.WxApiException) {
                    when {
                        e.isMainChannelInvalidated -> ChannelStateManager.markMainChannelInvalid("ret=200013")
                        e.isRateLimited -> ChannelStateManager.markMainChannelFreqControl(AppConfig.mainDegradedDuration * 1000)
                    }
                }
                publishFailed(task, "ACCOUNT_LOOKUP_FAILED", e.message ?: "公众号搜索失败", retryable = false)
                return
            }
        }

        if (subscription == null) {
            logger.warn("【任务处理】公众号不存在: gzh=${task.gzhName}，发布失败结果")
            publishFailed(task, "ACCOUNT_NOT_FOUND", "公众号不存在", retryable = false)
            return
        }

        val fakeid = subscription.fakeid
        val currentEinkService = einkSyncServiceProvider?.invoke() ?: einkSyncService
        val cached = fetchFromLocalCache(fakeid, startTs, endTs)
        val cacheHasUsableArticles = cached.any { it.link.isNotBlank() }
        val verifiedIntervals = if (cacheHasUsableArticles) {
            emptyList()
        } else {
            loadVerifiedCoverage(fakeid, startTs, coverageEndTs)
        }
        val missingRanges = if (cacheHasUsableArticles) {
            emptyList()
        } else {
            CollectionCoverage.missingRanges(startTs, coverageEndTs, verifiedIntervals)
        }
        if (cacheHasUsableArticles) {
            logger.info(
                "【缓存优先】命中 ${cached.size} 篇可用文章，直接返回并跳过全部远端通道: " +
                    "fakeid=${fakeid.take(8)}...",
            )
        } else if (missingRanges.isEmpty()) {
            logger.info("【缓存优先】请求范围已有可信覆盖，跳过全部远端通道: fakeid=${fakeid.take(8)}...")
        } else {
            logger.info(
                "【缓存优先】需要补齐 ${missingRanges.size} 个时间缺口: fakeid=${fakeid.take(8)}..., " +
                    missingRanges.joinToString { "${it.first}..${it.last}" },
            )
        }

        // 当前远端接口只能“从最新文章向目标起点翻页”，不能指定结束时间。因此从最早缺口
        // 查询一次即可覆盖其后的全部缺口，不能为每个离散缺口重复消耗一次翻页机会。
        val remoteRange = CollectionCoverage.remoteFetchEnvelope(coverageEndTs, requestStartedAt, missingRanges)
        val rangeFetches = remoteRange?.let { range ->
            listOf(
                MissingRangeFetch(
                    range = range,
                    results = fetchMissingRange(
                        task = task,
                        subscription = subscription,
                        einkService = currentEinkService,
                        range = range,
                    ),
                ),
            )
        }.orEmpty()
        val sourceResults = rangeFetches.flatMap(MissingRangeFetch::results)
        val articles = (cached + sourceResults.flatMap { it.articles })
            .filter { it.link.isNotBlank() }
            .distinctBy { it.link }

        // 保存所有本轮有效文章，避免仅保存窗口内数据导致后续缓存断层。
        val articlesToSave = articles.filter { it.publishTime > 0 }.map { article ->
            Article(
                fakeid = fakeid,
                aid = article.aid,
                title = article.title,
                link = article.link,
                digest = article.digest,
                cover = article.cover,
                author = article.author,
                publishTime = article.publishTime,
            )
        }
        if (articlesToSave.isNotEmpty()) {
            ArticleStore.saveAll(fakeid, articlesToSave, source = "task")
        }

        // 文章必须先成功落库，之后才能声明对应远端时间范围已完整覆盖。
        rangeFetches.forEach { fetch ->
            val completedBy = fetch.results.firstOrNull { it.coverageComplete } ?: return@forEach
            if (fetch.range.first <= fetch.range.last) {
                runCatching {
                    ArticleCoverageStore.saveVerified(
                        fakeid = fakeid,
                        startTime = fetch.range.first,
                        endTime = fetch.range.last,
                        source = completedBy.source,
                    )
                }.onFailure { error ->
                    logger.warn("【缓存覆盖】保存失败，不把本轮视为完整覆盖: ${error.message}")
                }
            }
        }

        val finalCoverage = loadVerifiedCoverage(fakeid, startTs, coverageEndTs)
        val coverageComplete = CollectionCoverage.missingRanges(startTs, coverageEndTs, finalCoverage).isEmpty()
        val sourceName = buildList {
            if (cacheHasUsableArticles || missingRanges.isEmpty()) add("cache")
            addAll(sourceResults.filter { it.articles.isNotEmpty() || it.coverageComplete }.map { it.source })
        }.distinct().joinToString("+").ifBlank { "none" }
        logger.info(
            "【任务处理】采集汇总: gzh=${task.gzhName}, source=$sourceName, " +
                "articles=${articles.size}, coverageComplete=$coverageComplete",
        )

        val filtered = articles.filter { it.publishTime in startTs..endTs }

        val urls = filtered.map { it.link }.distinct()
        if (CollectionCoverage.terminalDecision(urls.size, coverageComplete) ==
            CollectionTerminalDecision.FAILED_INCOMPLETE
        ) {
            val details = sourceResults.joinToString("; ") { "${it.source}:${it.detail}" }
            logger.error("【任务处理】采集范围未覆盖，不能误报 EMPTY: gzh=${task.gzhName}, $details")
            throw AaosFallbackRequiredException("采集范围未覆盖，不能误报 EMPTY，需要转入 AAOS 兜底: $details")
        }

        publishTerminal(task, urls)
        logger.info("【任务处理】完成: gzh=${task.gzhName}, 匹配 ${filtered.size}/${articles.size} 篇, 发布 ${urls.size} 条结果")
    }

    private suspend fun ensureMainLoginForLookup(task: ProcessingTask): Boolean {
        if (!ChannelStateManager.isMainChannelAvailable()) {
            publishFailed(
                task,
                "ACCOUNT_LOOKUP_UNAVAILABLE",
                "公众号尚未入库，主微信通道不可用: ${ChannelStateManager.mainChannelUnavailableReason()}",
                false,
            )
            return false
        }
        loginChecker.waitForRecoveryComplete()
        return when (loginChecker.checkTokenStatus()) {
            WxApiClient.TokenStatus.VALID -> true
            WxApiClient.TokenStatus.RATE_LIMITED -> {
                ChannelStateManager.markMainChannelFreqControl(AppConfig.mainDegradedDuration * 1000)
                publishFailed(task, "ACCOUNT_LOOKUP_RATE_LIMITED", "公众号尚未入库，主通道频控，暂时无法解析账号", false)
                false
            }
            WxApiClient.TokenStatus.UNAVAILABLE -> {
                publishFailed(task, "ACCOUNT_LOOKUP_UNAVAILABLE", "公众号尚未入库，主通道暂不可用", false)
                false
            }
            WxApiClient.TokenStatus.INVALIDATED -> {
                ChannelStateManager.markMainChannelInvalid("ret=200013")
                publishFailed(task, "ACCOUNT_LOOKUP_CHANNEL_INVALIDATED", "公众号尚未入库，主微信接口已永久失效", false)
                false
            }
            WxApiClient.TokenStatus.EXPIRED -> {
                // 主微信登录由服务端二维码恢复，不依赖 ADB；关闭自动恢复时快速返回明确终态。
                if (!AppConfig.authAutoRecoveryEnabled) {
                    publishFailed(task, "ACCOUNT_LOOKUP_AUTH_REQUIRED", "公众号尚未入库，需要人工恢复主微信登录", false)
                    false
                } else {
                    val recoveryCompleted = loginChecker.startRecoveryAndWait()
                    val recovered = recoveryCompleted &&
                        loginChecker.checkTokenStatus(forceRefresh = true) == WxApiClient.TokenStatus.VALID
                    if (!recovered) publishFailed(task, "AUTH_RECOVERY_FAILED", "登录恢复失败", false)
                    recovered
                }
            }
        }
    }

    private suspend fun publishTerminal(task: ProcessingTask, urls: List<String>) {
        val publisher = task.publisher ?: return
        if (task.v2Task != null) {
            publisher.publish(
                input = task.v2Task,
                status = if (urls.isEmpty()) "EMPTY" else "SUCCEEDED",
                seedArticles = urls.map { SeedArticle(url = it) },
            )
        } else {
            publisher.publishArticles(task.gzhName, task.jobId, task.correlationId, task.dateRange, urls)
        }
    }

    private data class ProcessingTask(
        val gzhName: String,
        val dateRange: List<String>,
        val jobId: Long,
        val correlationId: String,
        val publisher: ResultQueuePublisher?,
        val v2Task: GzhAutoBta?,
    )

    private data class SourceFetchResult(
        val source: String,
        val articles: List<WxApiClient.WxArticle>,
        val coverageComplete: Boolean,
        val detail: String,
    )

    private data class MissingRangeFetch(
        val range: LongRange,
        val results: List<SourceFetchResult>,
    )

    private suspend fun fetchMissingRange(
        task: ProcessingTask,
        subscription: Subscription,
        einkService: WeReadEinkSyncService?,
        range: LongRange,
    ): List<SourceFetchResult> {
        val results = mutableListOf<SourceFetchResult>()
        val canUseEink = AppConfig.wereadEinkEnabled &&
            einkRequestAllowed() &&
            einkService != null &&
            einkService.canSync(subscription)
        if (canUseEink) {
            fetchFromEink(einkService, subscription, range.first, range.last)?.let(results::add)
        } else if (AppConfig.wereadEinkEnabled && einkService != null) {
            logger.info("【E-Ink】运行状态不允许请求，直接使用 Web/缓存回退")
        }
        if (results.none { it.coverageComplete }) {
            results += fetchViaMainChannel(subscription.fakeid, range.first)
        }
        if (results.none { it.coverageComplete }) {
            results += fetchFromWeRead(task.gzhName, subscription.fakeid, subscription, range.first)
        }
        return results
    }

    private fun loadVerifiedCoverage(fakeid: String, startTs: Long, endTs: Long): List<LongRange> =
        try {
            ArticleCoverageStore.intervals(fakeid, startTs, endTs)
        } catch (error: Exception) {
            logger.warn("【缓存覆盖】查询失败，按未覆盖处理: ${error.message}")
            emptyList()
        }

    private fun fetchFromEink(
        service: WeReadEinkSyncService?,
        subscription: Subscription,
        startTs: Long,
        endTs: Long,
    ): SourceFetchResult? {
        service ?: return null
        return try {
            val syncResult = service.syncIfNeeded(subscription, startTs, endTs)
            if (!syncResult.cacheCovered) einkRequestSucceeded(service)

            val (articles, total) = ArticleStore.getByFakeidAndRange(
                subscription.fakeid,
                startTime = startTs,
                endTime = endTs,
                limit = 5_000,
            )
            logger.info(
                "【E-Ink】同步结果已从缓存读取: fakeid=${subscription.fakeid.take(8)}..., " +
                    "matched=${articles.size}/$total",
            )
            val covered = service.isRangeCovered(subscription, startTs, endTs)
            SourceFetchResult(
                source = "eink",
                articles = articles.map { it.toWxArticle() },
                coverageComplete = covered,
                detail = if (covered) "range-covered" else "sync-finished-without-range-coverage",
            )
        } catch (error: Exception) {
            einkRequestFailed(service, error)
            logger.warn(
                "【E-Ink】同步失败，转 Web 回退: fakeid=${subscription.fakeid.take(8)}..., ${error.message}",
                error,
            )
            null
        }
    }

    private fun Article.toWxArticle() = WxApiClient.WxArticle(
        aid = aid,
        title = title,
        link = link,
        digest = digest,
        cover = cover,
        author = author,
        publishTime = publishTime,
    )

    /**
     * L1 主通道：appmsgpublish 获取文章列表（带降级检查 + 重试 + 退避）
     *
     * - 主通道关闭、失效或处于临时降级期 → 直接走回退
     * - ret=200013 → 永久停用当前主通道，立即走回退
     * - 其他异常/空结果 → 按退避策略重试（retry_max 次）
     */
    private suspend fun fetchViaMainChannel(fakeid: String, targetStartTs: Long): SourceFetchResult {
        if (!ChannelStateManager.isMainChannelAvailable()) {
            val reason = ChannelStateManager.mainChannelUnavailableReason()
            logger.info("【L1 主通道】不可用（$reason），直接走回退")
            return SourceFetchResult("main", emptyList(), false, "channel-unavailable:$reason")
        }

        val pageSize = 20
        var backoffMs = AppConfig.mainBackoffBase * 1000
        for (attempt in 0 until AppConfig.mainRetryMax) {
            val round = attempt + 1
            try {
                val articles = wxClient.fetchArticles(fakeid, begin = 0, count = pageSize)
                if (articles.isNotEmpty()) {
                    val covered = CollectionCoverage.pageCoversWindowStart(
                        publishTimes = articles.map { it.publishTime },
                        requestedPageSize = pageSize,
                        windowStart = targetStartTs,
                    )
                    return SourceFetchResult(
                        source = "main",
                        articles = articles,
                        coverageComplete = covered,
                        detail = if (covered) "range-covered" else "newest-page-only",
                    )
                }
                logger.warn("【L1 主通道】返回空列表 (第 $round/${AppConfig.mainRetryMax} 次)")
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if ((e is WxApiClient.WxApiException && e.isMainChannelInvalidated) || msg.contains("200013")) {
                    logger.error("【L1 主通道】ret=200013 表示接口永久失效，停止后续主通道请求")
                    ChannelStateManager.markMainChannelInvalid("ret=200013")
                    return SourceFetchResult("main", emptyList(), false, "channel-invalidated")
                }
                if ((e is WxApiClient.WxApiException && e.isRateLimited) ||
                    msg.contains("freq") || msg.contains("频控")
                ) {
                    logger.warn("【L1 主通道】触发未知临时限流，标记降级 ${AppConfig.mainDegradedDuration}s")
                    ChannelStateManager.markMainChannelFreqControl(AppConfig.mainDegradedDuration * 1000)
                    return SourceFetchResult("main", emptyList(), false, "rate-limited")
                }
                if (e is WxApiClient.WxApiException && e.isAuthExpired) {
                    logger.warn("【L1 主通道】登录已失效，不阻塞任务，立即转回退通道")
                    return SourceFetchResult("main", emptyList(), false, "auth-expired")
                }
                logger.warn("【L1 主通道】异常 (第 $round/${AppConfig.mainRetryMax} 次): ${e.message}")
            }

            // 退避重试（指数退避 + 随机抖动）
            if (round < AppConfig.mainRetryMax) {
                logger.info("【L1 主通道】${backoffMs / 1000}s 后重试...")
                delay(backoffMs + Random.nextLong(0, 2000))
                backoffMs = (backoffMs * 2).coerceAtMost(AppConfig.mainBackoffMax * 1000)
            }
        }
        return SourceFetchResult("main", emptyList(), false, "retry-exhausted-or-empty")
    }

    /**
     * L2 微信读书官方直连回退通道
     *
     * 定位 mpId（官方解析接口 wxs2mp 已下线，改用 fakeid 直接推导）：
     * 1. 本地订阅已缓存的 wereadMpId
     * 2. 从 fakeid 推导（base64 解码 + "MP_WXS_" 前缀）
     */
    private suspend fun fetchFromWeRead(
        gzhName: String,
        fakeid: String,
        subscription: Subscription?,
        targetStartTs: Long?,
    ): SourceFetchResult {
        if (com.wamp.download.weread.WeReadAccountPool.size() == 0) {
            logger.warn("【L2 微信读书】账号池为空，跳过回退通道")
            return SourceFetchResult("weread-web", emptyList(), false, "account-pool-empty")
        }

        // 1. 已缓存 mpId
        var mpId = subscription?.wereadMpId.orEmpty()

        // 2. 从 fakeid 推导（wxs2mp 已下线，无法再通过文章链接解析）
        if (mpId.isBlank()) {
            mpId = weReadWebClient.computeMpId(fakeid)
            if (mpId.isNotBlank()) {
                SubscriptionStore.saveWereadMpId(fakeid, mpId)
                logger.info("【L2 微信读书】从 fakeid 推导 mpId: $gzhName -> $mpId")
            }
        }

        if (mpId.isBlank()) {
            logger.warn("【L2 微信读书】无法定位公众号 mpId: $gzhName（可通过 /api/weread/bind 用 fakeid 预绑定）")
            return SourceFetchResult("weread-web", emptyList(), false, "mpid-unresolved")
        }

        // 翻页抓取（空页重试 + 退避 + 翻页抖动在 fetchAllArticles 内部实现）
        val result = try {
            weReadWebClient.fetchAllArticlesResult(mpId, targetStartTs)
        } catch (e: Exception) {
            logger.warn("【L2 微信读书】抓取失败: ${e.message}")
            return SourceFetchResult("weread-web", emptyList(), false, "exception:${e.javaClass.simpleName}")
        }

        val articles = result.articles.map {
            WxApiClient.WxArticle(
                aid = it.id,
                title = it.title,
                link = it.link,
                digest = "",
                cover = it.cover,
                author = "",
                publishTime = it.publishTime,
            )
        }
        return SourceFetchResult(
            source = "weread-web",
            articles = articles,
            coverageComplete = result.coverageComplete,
            detail = "outcome=${result.lastOutcome},pages=${result.pagesFetched}",
        )
    }

    /**
     * L3 本地缓存兜底：从 SQLite 按公众号 + 日期范围查询已存文章
     */
    private fun fetchFromLocalCache(
        fakeid: String,
        startTs: Long?,
        endTs: Long?,
    ): List<WxApiClient.WxArticle> {
        val (cached, total) = try {
            ArticleStore.getByFakeidAndRange(fakeid, startTs, endTs, limit = 5_000)
        } catch (e: Exception) {
            logger.warn("【L3 本地缓存】查询失败: ${e.message}")
            return emptyList()
        }
        if (cached.isEmpty()) {
            logger.warn("【L3 本地缓存】无缓存文章: fakeid=${fakeid.take(8)}...")
            return emptyList()
        }
        logger.info("【L3 本地缓存】命中 ${cached.size} 篇（总数 $total）: fakeid=${fakeid.take(8)}...")
        return cached.map {
            WxApiClient.WxArticle(
                aid = it.aid,
                title = it.title,
                link = it.link,
                digest = it.digest,
                cover = it.cover,
                author = it.author,
                publishTime = it.publishTime,
            )
        }
    }

    /**
     * 搜索公众号并确保已订阅
     *
     * @return fakeid（找不到返回 null）
     */
    private fun findOrCreateSubscription(gzhName: String): String? {
        // 支持传入文章链接作为订阅源
        if (gzhName.startsWith("http://") || gzhName.startsWith("https://")) {
            logger.info("【任务处理】检测到文章链接输入，尝试提取公众号身份...")
            return try {
                // 注意：这里用阻塞的 HTTP 客户端或 Ktor 都可以，由于我们在协程里，先用简单的 Ktor 或 OkHttp?
                // 因为我们在 TaskProcessor 里面没有现成的 OkHttp client 实例直接暴露。
                // 暂时用 Jsoup.connect 获取，Jsoup 底层是同步的，最好包装在 Dispatchers.IO，这里是在协程内。
                val doc = org.jsoup.Jsoup.connect(gzhName).userAgent("Mozilla/5.0").timeout(10000).get()
                val identity = ArticleIdentityResolver.extractIdentityFromHtml(doc.outerHtml(), gzhName)
                if (identity != null) {
                    val fakeid = identity.biz // fakeid 其实就是 base64 编码的 biz
                    val existing = SubscriptionStore.get(fakeid)
                    if (existing != null) {
                        return fakeid
                    }
                    SubscriptionStore.add(
                        fakeid = fakeid,
                        nickname = identity.accountName.ifBlank { "Unknown" },
                        alias = "",
                        headImg = ""
                    )
                    SubscriptionStore.saveWereadMpId(fakeid, identity.bookId)
                    logger.info("【任务处理】通过链接提取公众号成功: fakeid=$fakeid, mpId=${identity.bookId}, name=${identity.accountName}")
                    fakeid
                } else {
                    logger.warn("【任务处理】无法从链接提取公众号身份")
                    null
                }
            } catch (e: Exception) {
                logger.warn("【任务处理】提取链接身份失败: ${e.message}")
                null
            }
        }

        // 先查本地数据库
        val existing = SubscriptionStore.findByNickname(gzhName)
        if (existing != null) {
            logger.debug("【任务处理】公众号已在本地: gzh=$gzhName, fakeid=${existing.fakeid}")
            return existing.fakeid
        }

        // 搜索微信
        logger.info("【任务处理】搜索公众号: $gzhName")
        val fakeid = wxClient.findFakeidByName(gzhName)
        if (fakeid.isNullOrEmpty()) {
            return null
        }

        // 保存到数据库
        val searchResults = wxClient.searchAccounts(gzhName)
        val matched = searchResults.firstOrNull { it.fakeid == fakeid }
        SubscriptionStore.add(
            fakeid = fakeid,
            nickname = matched?.nickname ?: gzhName,
            alias = matched?.alias ?: "",
            headImg = matched?.headImg ?: "",
        )
        logger.info("【任务处理】公众号已添加: gzh=$gzhName, fakeid=$fakeid")
        return fakeid
    }

    private suspend fun publishFailed(
        task: ProcessingTask,
        code: String,
        message: String,
        retryable: Boolean,
    ) {
        val error = AtdError(code = code, message = message, retryable = retryable)
        val publisher = task.publisher ?: return
        if (task.v2Task != null) {
            publisher.publish(input = task.v2Task, status = "FAILED", error = error)
        } else {
            publisher.publishFailedResult(
                gzhName = task.gzhName,
                jobId = task.jobId,
                correlationId = task.correlationId,
                tempTimeStamp = task.dateRange,
                error = error,
            )
        }
    }

    /**
     * 解析日期范围 [startDate, endDate] 为时间戳
     *
     * @return Pair(startTs, endTs) 秒级时间戳，解析失败返回 null
     */
    private fun parseDateRange(range: List<String>): Pair<Long?, Long?> {
        val parsed = CaptureWindowParser.parse(range)
        if (parsed.first == null || parsed.second == null) {
            logger.warn("【任务处理】采集时间窗口解析失败: $range")
        }
        return parsed
    }
}
