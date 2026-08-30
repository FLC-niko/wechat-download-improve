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
import com.wamp.download.store.ArticleStore
import com.wamp.download.store.Subscription
import com.wamp.download.store.SubscriptionStore
import com.wamp.download.weread.WeReadWebClient
import com.wamp.download.weread.eink.WeReadEinkSyncService
import com.wamp.download.wx.WxApiClient
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * 公众号任务处理器
 *
 * 处理流程（三阶段执行计划：阶段一官方直连 + 工业级回退）：
 * 1. 等待登录恢复（如果正在恢复）
 * 2. 检查 token 有效性，无效则触发登录恢复
 * 3. 搜索公众号名称 → 获取 fakeid
 * 4. L1 主通道：appmsgpublish（默认关闭；200013 永久失效）
 * 5. L2 微信读书官方直连（weread.qq.com，空页重试 + 翻页抖动）
 * 6. L3 本地缓存兜底 + 告警
 * 7. 按日期范围过滤 → 保存到数据库 → 发布结果到 RabbitMQ
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
        logger.info("【任务处理】开始: gzh=${task.gzhName}, 日期=${task.dateRange}, jobId=${task.jobId}")

        val (startTs, endTs) = parseDateRange(task.dateRange)
        if (startTs == null || endTs == null) {
            publishFailed(task, "INVALID_CAPTURE_WINDOW", "采集时间窗口格式非法或起止时间反向", retryable = false)
            return
        }

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
        val canUseEink = AppConfig.wereadEinkEnabled &&
            einkSyncService != null &&
            einkSyncService.canSync(subscription)

        val sourceResults = mutableListOf<SourceFetchResult>()
        if (canUseEink) {
            fetchFromEink(subscription, startTs, endTs)?.let(sourceResults::add)
        }
        if (sourceResults.none { it.coverageComplete }) {
            sourceResults += fetchViaMainChannel(fakeid, startTs)
        }
        if (sourceResults.none { it.coverageComplete }) {
            sourceResults += fetchFromWeRead(task.gzhName, fakeid, subscription, startTs)
        }

        // 缓存永远参与合并，可补远端部分失败时已经采集过的数据；但缓存本身不能证明空窗口。
        val cached = fetchFromLocalCache(fakeid, startTs, endTs)
        val articles = (sourceResults.flatMap { it.articles } + cached)
            .filter { it.link.isNotBlank() }
            .distinctBy { it.link }
        val coverageComplete = sourceResults.any { it.coverageComplete }
        val sourceName = sourceResults.filter { it.articles.isNotEmpty() || it.coverageComplete }
            .joinToString("+") { it.source }
            .ifBlank { if (cached.isNotEmpty()) "cache" else "none" }

        logger.info(
            "【任务处理】采集汇总: gzh=${task.gzhName}, source=$sourceName, " +
                "articles=${articles.size}, coverageComplete=$coverageComplete",
        )

        val filtered = articles.filter { it.publishTime in startTs..endTs }

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

        val urls = filtered.map { it.link }.distinct()
        if (CollectionCoverage.terminalDecision(urls.size, coverageComplete) ==
            CollectionTerminalDecision.FAILED_INCOMPLETE
        ) {
            val details = sourceResults.joinToString("; ") { "${it.source}:${it.detail}" }
            logger.error("【任务处理】采集范围未覆盖，不能误报 EMPTY: gzh=${task.gzhName}, $details")
            publishFailed(
                task,
                "COVERAGE_INCOMPLETE",
                "采集通道未能覆盖请求时间窗口，无法确认该窗口为空",
                retryable = false,
            )
            return
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
                // 当前上线模式不依赖 ADB；无自动恢复能力时快速返回明确终态，避免任务阻塞数十分钟。
                if (!AppConfig.adbEnabled) {
                    publishFailed(task, "ACCOUNT_LOOKUP_AUTH_REQUIRED", "公众号尚未入库，需要人工恢复主微信登录", false)
                    false
                } else {
                    loginChecker.startRecoveryAndWait()
                    val recovered = loginChecker.checkTokenStatus(forceRefresh = true) == WxApiClient.TokenStatus.VALID
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

    private fun fetchFromEink(
        subscription: Subscription,
        startTs: Long,
        endTs: Long,
    ): SourceFetchResult? {
        val service = einkSyncService ?: return null
        return try {
            service.syncIfNeeded(subscription, startTs, endTs)

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
