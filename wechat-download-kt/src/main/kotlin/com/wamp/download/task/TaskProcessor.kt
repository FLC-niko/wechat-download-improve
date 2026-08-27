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
 * 4. L1 主通道：appmsgpublish（重试 + 退避 + 频控降级标记）
 * 5. L2 微信读书官方直连（weread.qq.com，空页重试 + 翻页抖动）
 * 6. L3 本地缓存兜底 + 告警
 * 7. 按日期范围过滤 → 保存到数据库 → 发布结果到 RabbitMQ
 *
 * 与 Worker 的区别：
 * - 直接调微信 API，不经过适配壳 HTTP 中转
 * - 文章存 SQLite，不存 JSON 文件
 * - 只获取文章链接元数据，不抓正文
 * - 微信读书官方直连作为 appmsgpublish 频控时的回退通道（第三方代理已废弃）
 */
class TaskProcessor(
    private val wxClient: WxApiClient = WxApiClient(),
    private val weReadWebClient: WeReadWebClient = WeReadWebClient(),
    private val loginChecker: LoginChecker = LoginChecker(),
    private val resultPublisher: ResultQueuePublisher? = null,
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

        // 1. 等待登录恢复（如果正在恢复）
        loginChecker.waitForRecoveryComplete()

        // 2. 检查 token 有效性
        if (!loginChecker.isTokenValid()) {
            logger.warn("【任务处理】token 无效，触发登录恢复: gzh=${task.gzhName}")
            loginChecker.startRecoveryAndWait()
            // 恢复后再次检查
            if (!loginChecker.isTokenValid(forceRefresh = true)) {
                logger.error("【任务处理】登录恢复失败，发布失败结果: gzh=${task.gzhName}")
                publishFailed(task, "AUTH_RECOVERY_FAILED", "登录恢复失败")
                return
            }
        }

        // 3. 搜索公众号 → 获取 fakeid
        val fakeid = try {
            findOrCreateSubscription(task.gzhName)
        } catch (e: Exception) {
            logger.error("【任务处理】搜索公众号失败: gzh=${task.gzhName}, error=${e.message}")
            // 如果是登录失效导致的搜索失败，触发恢复
            if (e.message?.contains("ret=") == true || e.message?.contains("未登录") == true) {
                loginChecker.startRecoveryAndWait()
            }
            publishFailed(task, "ACCOUNT_LOOKUP_FAILED", e.message ?: "公众号搜索失败")
            return
        }

        if (fakeid.isNullOrEmpty()) {
            logger.warn("【任务处理】公众号不存在: gzh=${task.gzhName}，发布失败结果")
            publishFailed(task, "ACCOUNT_NOT_FOUND", "公众号不存在")
            return
        }

        val subscription = SubscriptionStore.get(fakeid)
        val (startTs, endTs) = parseDateRange(task.dateRange)

        // --- 工业级回退链路 L1 → L2 → L3 ---
        val (articles, sourceName) =
            // L1: 主通道（带频控降级检查 + 重试 + 退避）
            fetchViaMainChannel(fakeid)?.takeIf { it.isNotEmpty() }?.let { it to "main" }
                // L2: 微信读书官方直连
                ?: fetchFromWeRead(task.gzhName, fakeid, subscription, startTs).takeIf { it.isNotEmpty() }?.let { it to "weread-web" }
                // L3: 本地缓存兜底
                ?: fetchFromLocalCache(fakeid, startTs, endTs).takeIf { it.isNotEmpty() }?.let { it to "cache" }
                ?: run {
                    // 全链路失败：告警 + FAILED
                    logger.error("【任务处理】L1/L2/L3 全部失败，发布失败结果: gzh=${task.gzhName}")
                    publishFailed(task, "ARTICLE_FETCH_FAILED", "L1/L2/L3 全部采集失败")
                    return
                }

        logger.info("【任务处理】获取到 ${articles.size} 篇文章: gzh=${task.gzhName} (source=$sourceName)")

        // 按日期范围过滤
        val filtered = if (startTs != null && endTs != null) {
            articles.filter { it.publishTime in startTs..endTs }
        } else {
            articles
        }

        // 保存到数据库
        val articlesToSave = filtered.map { article ->
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

        // 发布结果
        val urls = filtered.map { it.link }
        task.publisher?.let { publisher ->
            if (task.v2Task != null) {
                publisher.publish(
                    input = task.v2Task,
                    status = if (urls.isEmpty()) "EMPTY" else "SUCCEEDED",
                    seedArticles = urls.distinct().map { SeedArticle(url = it) },
                )
            } else {
                publisher.publishArticles(task.gzhName, task.jobId, task.correlationId, task.dateRange, urls)
            }
        }
        logger.info("【任务处理】完成: gzh=${task.gzhName}, 匹配 ${filtered.size}/${articles.size} 篇, 发布 ${urls.size} 条结果")
    }

    private data class ProcessingTask(
        val gzhName: String,
        val dateRange: List<String>,
        val jobId: Long,
        val correlationId: String,
        val publisher: ResultQueuePublisher?,
        val v2Task: GzhAutoBta?,
    )

    /**
     * L1 主通道：appmsgpublish 获取文章列表（带降级检查 + 重试 + 退避）
     *
     * - 主通道处于降级期 → 直接返回 null（走回退，避免浪费请求）
     * - 触发频控（ret=200013）→ 标记降级，立即返回 null
     * - 其他异常/空结果 → 按退避策略重试（retry_max 次）
     */
    private suspend fun fetchViaMainChannel(fakeid: String): List<WxApiClient.WxArticle>? {
        if (!ChannelStateManager.isMainChannelAvailable()) {
            logger.info("【L1 主通道】处于频控降级期（剩余 ${ChannelStateManager.remainingDegradedSeconds()}s），直接走回退")
            return null
        }

        var backoffMs = AppConfig.mainBackoffBase * 1000
        repeat(AppConfig.mainRetryMax) { attempt ->
            val round = attempt + 1
            try {
                val articles = wxClient.fetchArticles(fakeid, begin = 0, count = 20)
                if (articles.isNotEmpty()) {
                    return articles
                }
                logger.warn("【L1 主通道】返回空列表 (第 $round/${AppConfig.mainRetryMax} 次)")
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("200013") || msg.contains("freq") || msg.contains("频控")) {
                    // 频控：标记降级，直接走回退（不继续重试，避免加剧风控）
                    logger.warn("【L1 主通道】触发频控(ret=200013)，标记降级 ${AppConfig.mainDegradedDuration}s")
                    ChannelStateManager.markMainChannelFreqControl(AppConfig.mainDegradedDuration * 1000)
                    return null
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
        return null
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
    ): List<WxApiClient.WxArticle> {
        if (com.wamp.download.weread.WeReadAccountPool.size() == 0) {
            logger.warn("【L2 微信读书】账号池为空，跳过回退通道")
            return emptyList()
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
            return emptyList()
        }

        // 翻页抓取（空页重试 + 退避 + 翻页抖动在 fetchAllArticles 内部实现）
        val articles = try {
            weReadWebClient.fetchAllArticles(mpId, targetStartTs)
        } catch (e: Exception) {
            logger.warn("【L2 微信读书】抓取失败: ${e.message}")
            return emptyList()
        }

        return articles.map {
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
            ArticleStore.getByFakeidAndRange(fakeid, startTs, endTs, limit = 200)
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

    private suspend fun publishEmpty(task: GzhAutoFromBackend, publisher: ResultQueuePublisher? = resultPublisher) {
        publisher?.publishEmptyResult(
            gzhName = task.gzhName,
            jobId = task.jobId,
            correlationId = task.correlationId,
            tempTimeStamp = task.tempTimeStamp,
        )
    }

    private suspend fun publishFailed(
        task: ProcessingTask,
        code: String,
        message: String,
    ) {
        val error = AtdError(code = code, message = message, retryable = code != "ACCOUNT_NOT_FOUND")
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
