package com.wamp.download.weread

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.wamp.download.config.AppConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 微信读书官方 Web 接口客户端（2026 新版，多账号池化）
 *
 * 直接调用 weread.qq.com，不经过任何第三方代理。
 * 凭证从 WeReadAccountPool 动态取号，支持多账号轮询 + 频控换号。
 *
 * 认证方式（双保险）：
 * - Header: x-vid / x-skey（新版前端）
 * - Cookie: wr_vid / wr_skey（旧接口兼容）
 *
 * 已实测（2026-08）可用接口：
 * - GET /web/mp/articles?bookId={mpId}&offset={offset}
 *   分页获取公众号文章，返回结构为 reviews[].subReviews[].review.mpInfo
 *   文章字段：originalId(=链接token) / title / pic_url / time / mp_name
 *
 * 公众号 bookId 推导（无需解析接口）：fakeid 为 base64，解码后加前缀 "MP_WXS_"
 */
class WeReadWebClient {
    private val logger = LoggerFactory.getLogger(WeReadWebClient::class.java)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = AppConfig.wereadWebBaseUrl

    private val listType = object : TypeToken<List<Map<String, Any?>>>() {}.type

    data class MpInfo(
        val mpId: String,       // 微信读书 bookId
        val name: String,
        val cover: String = "",
        val intro: String = "",
    )

    data class WeReadArticle(
        val id: String,          // 短链 token
        val title: String,
        val link: String,        // https://mp.weixin.qq.com/s/{id}
        val cover: String = "",
        val publishTime: Long,   // 秒级时间戳
    )

    /**
     * 单页抓取结果（含使用的账号，供上层做频控冷却）
     */
    data class WeReadPage(
        val articles: List<WeReadArticle>,
        val accountVid: String? = null,   // 实际使用的账号；无可用账号时为 null
        val outcome: PageOutcome = if (articles.isEmpty()) PageOutcome.NO_MORE_DATA else PageOutcome.DATA,
        val nextOffset: Int? = null,
        val endReached: Boolean = false,
    )

    enum class PageOutcome {
        DATA,
        NO_MORE_DATA,
        AUTH_EXPIRED,
        RATE_LIMITED,
        HUMAN_VERIFY,
        NO_AVAILABLE_ACCOUNT,
        RETRYABLE_ERROR,
        ERROR,
        PARSE_ERROR,
    }

    private enum class PageKind { SUCCESS, EXPIRED, RATE_LIMITED, HUMAN_VERIFY, RETRYABLE_ERROR, ERROR, PARSE_ERROR }

    data class WeReadCollectionResult(
        val articles: List<WeReadArticle>,
        /** true 表示已经翻到请求窗口起点之前，或服务端明确表示没有下一页。 */
        val coverageComplete: Boolean,
        val lastOutcome: PageOutcome,
        val pagesFetched: Int,
    )

    internal data class ArticleParseResult(
        val articles: List<WeReadArticle>,
        val validResponse: Boolean,
        val nextOffset: Int? = null,
        val endReached: Boolean = false,
    )

    private data class AccountPageResult(
        val kind: PageKind,
        val parsed: ArticleParseResult = ArticleParseResult(emptyList(), false),
    )

    // --- 认证 ---

    /** 兼容入口：主账号是否已登录 */
    fun isLoggedIn(): Boolean = WeReadCookieStore.isLoggedIn()

    /**
     * 启动健康检查用：轻量探测账号凭证是否存活（GET /web/shelf/sync）。
     * 只判死不判活异常：AUTH_EXPIRED 之外的结果（频控/网络异常）均不触发账号下线。
     */
    internal fun probeAccount(account: WeReadAccount): WeReadWebResponseKind {
        val creds = account.credentials
        if (!account.hasWebCredentials()) return WeReadWebResponseKind.AUTH_EXPIRED
        val url = "$baseUrl/web/shelf/sync".toHttpUrl().newBuilder()
            .addQueryParameter("userVid", creds.wrVid)
            .addQueryParameter("synckey", "0")
            .build()
        val req = authRequest(Request.Builder().url(url), creds).get().build()
        return try {
            WeReadRequestGate.execute(account.vid) {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    classifyProbeResponse(resp.code, raw)
                }
            }
        } catch (e: Exception) {
            logger.warn("微信读书账号探测异常（不判死）: vid=${creds.wrVid}, ${e.message}")
            WeReadWebResponseKind.RETRYABLE_HTTP_ERROR
        }
    }

    /** 探测响应判定：只有 401/-2012 判死；异常 2xx 正文按临时上游错误处理。 */
    internal fun classifyProbeResponse(httpCode: Int, raw: String): WeReadWebResponseKind {
        val kind = WeReadWebResponseClassifier.classify(httpCode, raw).kind
        if (kind != WeReadWebResponseKind.SUCCESS) return kind
        val validObject = runCatching {
            JsonParser.parseString(raw).isJsonObject
        }.getOrDefault(false)
        return if (validObject) kind else WeReadWebResponseKind.RETRYABLE_HTTP_ERROR
    }

    private fun authRequest(builder: Request.Builder, creds: WeReadWebCredentials): Request.Builder {
        return builder
            .header("x-vid", creds.wrVid)
            .header("x-skey", creds.wrSkey)
            .header("Cookie", creds.toCookieString())
            // 浏览器特征伪装：补全真实浏览器会带的头，降低被识别为机器请求的概率
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .header("User-Agent", randomUA())
    }

    // --- 请求节流（防多任务并发叠加触发风控） ---
    // 单账号在途并发控制：每个账号一把信号量，同一账号同时最多 1 个请求在途，
    // 多任务/多 worker 并发抓不同公众号时，同一账号的请求完全串行排队。
    /**
     * 从微信 fakeid 推导微信读书 bookId（官方解析接口 wxs2mp 已下线）
     */
    fun computeMpId(fakeid: String): String {
        if (fakeid.isBlank()) return ""
        return try {
            val decoded = Base64.getDecoder().decode(fakeid).toString(Charsets.UTF_8)
            if (decoded.isBlank()) "" else "MP_WXS_$decoded"
        } catch (e: Exception) {
            logger.warn("微信读书 bookId 推导失败: fakeid=${fakeid.take(8)}..., ${e.message}")
            ""
        }
    }

    /**
     * 分页获取公众号文章（官方 /web/mp/articles）— 多账号池化
     *
     * 内部流程：
     * 1. acquire() 取号（least_used + inUse 互斥 + 冷却过滤）
     * 2. 用该账号请求；凭证失效（401 / errCode=-2012）→ 标记 EXPIRED 并换号
     * 3. 非失效错误不换号，直接返回空
     *
     * @return 本页文章列表 + 实际使用的账号 vid（空页时上层可据此做频控冷却）
     */
    fun fetchArticles(mpId: String, offset: Int): WeReadPage {
        val attemptLimit = WeReadAccountPool.size().coerceAtLeast(1)
        var lastAccountVid: String? = null
        var lastOutcome = PageOutcome.ERROR
        repeat(attemptLimit) {
            val account = WeReadAccountPool.acquire()
                ?: return WeReadPage(emptyList(), outcome = PageOutcome.NO_AVAILABLE_ACCOUNT)
            lastAccountVid = account.vid
            try {
                val accountResult = fetchArticlesForAccount(account, mpId, offset)
                val kind = accountResult.kind
                val parsed = accountResult.parsed
                val articles = parsed.articles
                when (kind) {
                    PageKind.SUCCESS -> {
                        WeReadAccountPool.markSuccess(account.vid)
                        return WeReadPage(
                            articles = articles,
                            accountVid = account.vid,
                            outcome = if (articles.isEmpty()) PageOutcome.NO_MORE_DATA else PageOutcome.DATA,
                            nextOffset = parsed.nextOffset,
                            endReached = parsed.endReached,
                        )
                    }
                    PageKind.EXPIRED -> {
                        lastOutcome = PageOutcome.AUTH_EXPIRED
                        // 新版 Web accessToken 没有 refreshToken，不能用 HEAD 请求伪装续签。
                        WeReadAccountPool.markAuthExpired(account.vid, "凭证失效(401/-2012)")
                        WeReadWebAuth.requestReloginIfNeeded(account.vid, force = true)
                    }
                    PageKind.RATE_LIMITED -> {
                        lastOutcome = PageOutcome.RATE_LIMITED
                        WeReadAccountPool.markFreqControl(account.vid)
                    }
                    PageKind.HUMAN_VERIFY -> {
                        // -2041 是账号级风控：同一账号继续请求只会重复失败并加重风控
                        // （2026-09-29 复盘：任务洪峰下同一账号被反复取号，-2041 日志达
                        // 56+ 条/小时）。这里把账号标记为失效并只触发一次恢复，其余任务
                        // 直接走缓存/其他账号，不再形成请求风暴。
                        logger.warn("微信读书要求人工验证，标记账号并停止自动重试: vid=${account.vid}, mpId=$mpId")
                        val recoveryCount = WeReadAccountPool.markAuthExpired(account.vid, "人工验证(-2041)")
                        if (recoveryCount <= 1) {
                            WeReadWebAuth.requestReloginIfNeeded(account.vid, force = true)
                        } else {
                            logger.info(
                                "微信读书账号已因人工验证进入恢复流程，跳过重复重登请求: " +
                                    "vid=${account.vid}, count=$recoveryCount",
                            )
                        }
                        return WeReadPage(emptyList(), account.vid, PageOutcome.HUMAN_VERIFY)
                    }
                    PageKind.RETRYABLE_ERROR -> return WeReadPage(emptyList(), account.vid, PageOutcome.RETRYABLE_ERROR)
                    PageKind.ERROR -> return WeReadPage(emptyList(), account.vid, PageOutcome.ERROR)  // 非失效错误，不换号
                    PageKind.PARSE_ERROR -> return WeReadPage(emptyList(), account.vid, PageOutcome.PARSE_ERROR)
                }
            } catch (e: Exception) {
                logger.warn("微信读书请求异常（换号重试）: vid=${account.vid}, ${e.message}")
                lastOutcome = if (e is IOException) {
                    PageOutcome.RETRYABLE_ERROR
                } else {
                    PageOutcome.ERROR
                }
            } finally {
                WeReadAccountPool.release(account.vid)
            }
        }
        return WeReadPage(emptyList(), lastAccountVid, lastOutcome)
    }

    /**
     * 用指定账号发起单页请求（底层，不涉及账号池调度）
     */
    private fun fetchArticlesForAccount(
        account: WeReadAccount,
        mpId: String,
        offset: Int,
    ): AccountPageResult {
        return WeReadRequestGate.execute(account.vid) {
            doFetchArticlesForAccount(account, mpId, offset)
        }
    }

    /** 单账号单页请求（不含节流，供信号量包裹调用） */
    private fun doFetchArticlesForAccount(
        account: WeReadAccount,
        mpId: String,
        offset: Int,
    ): AccountPageResult {
        val creds = account.credentials
        if (creds.wrSkey.isEmpty() || creds.wrVid.isEmpty()) {
            return AccountPageResult(PageKind.EXPIRED)
        }
        val url = "$baseUrl/web/mp/articles".toHttpUrl()
            .newBuilder()
            .addQueryParameter("bookId", mpId)
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("count", AppConfig.wereadPageSize.toString())
            .build()
        val req = authRequest(Request.Builder().url(url), creds).build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val classification = WeReadWebResponseClassifier.classify(resp.code, raw)
            when (classification.kind) {
                WeReadWebResponseKind.AUTH_EXPIRED -> {
                    logger.warn("微信读书凭证失效 (http=${resp.code}, errCode=${classification.errCode}): vid=${creds.wrVid}")
                    return AccountPageResult(PageKind.EXPIRED)
                }
                WeReadWebResponseKind.RATE_LIMITED -> {
                    logger.warn("微信读书请求频控 (http=${resp.code}): offset=$offset, vid=${creds.wrVid}")
                    return AccountPageResult(PageKind.RATE_LIMITED)
                }
                WeReadWebResponseKind.HUMAN_VERIFY -> {
                    logger.warn("微信读书请求需要人工验证 (errCode=${classification.errCode}): vid=${creds.wrVid}")
                    return AccountPageResult(PageKind.HUMAN_VERIFY)
                }
                WeReadWebResponseKind.RETRYABLE_HTTP_ERROR -> {
                    logger.warn("微信读书临时服务错误: HTTP ${resp.code}, offset=$offset, vid=${creds.wrVid}")
                    return AccountPageResult(PageKind.RETRYABLE_ERROR)
                }
                WeReadWebResponseKind.APPLICATION_ERROR -> {
                    logger.warn(
                        "微信读书业务错误: HTTP ${resp.code}, errCode=${classification.errCode}, " +
                            "errMsg=${classification.errMsg}, offset=$offset, vid=${creds.wrVid}",
                    )
                    return AccountPageResult(PageKind.ERROR)
                }
                WeReadWebResponseKind.HTTP_ERROR -> {
                    logger.warn("微信读书获取文章失败: HTTP ${resp.code}, errCode=${classification.errCode}, offset=$offset, vid=${creds.wrVid}")
                    return AccountPageResult(PageKind.ERROR)
                }
                WeReadWebResponseKind.SUCCESS -> Unit
            }
            val parsed = parseArticleList(raw)
            if (!parsed.validResponse) {
                // A gateway may return HTTP 200 HTML/empty/array content. Treat only a JSON
                // object with an unknown schema as a parse failure; malformed content is
                // transient and should use the bounded page retry path.
                val isJsonObject = runCatching { JsonParser.parseString(raw).isJsonObject }
                    .getOrDefault(false)
                return AccountPageResult(
                    if (isJsonObject) PageKind.PARSE_ERROR else PageKind.RETRYABLE_ERROR,
                )
            }
            return AccountPageResult(PageKind.SUCCESS, parsed)
        }
    }

    internal fun parseArticleList(raw: String): ArticleParseResult {
        return try {
            val obj = JsonParser.parseString(raw)
            if (!obj.isJsonObject) return ArticleParseResult(emptyList(), false)
            val root = obj.asJsonObject

            // 新版结构：reviews[].subReviews[].review.mpInfo
            if (root.get("reviews")?.isJsonArray == true) {
                val reviews: List<Map<String, Any?>> =
                    gson.fromJson(root.get("reviews").toString(), listType)
                val articles = reviews.flatMap { review ->
                    val subs = review["subReviews"] as? List<*> ?: emptyList<Any?>()
                    subs.mapNotNull { sub ->
                        val subMap = sub as? Map<*, *> ?: return@mapNotNull null
                        val mpInfo = (subMap["review"] as? Map<*, *>)?.get("mpInfo") as? Map<*, *>
                            ?: return@mapNotNull null
                        val id = mpInfo["originalId"] as? String
                            ?: return@mapNotNull null
                        if (id.isEmpty()) return@mapNotNull null
                        WeReadArticle(
                            id = id,
                            title = mpInfo["title"] as? String ?: "",
                            link = "https://mp.weixin.qq.com/s/$id",
                            cover = mpInfo["pic_url"] as? String ?: "",
                            publishTime = (mpInfo["time"] as? Number)?.toLong()
                                ?: (subMap["createTime"] as? Number)?.toLong() ?: 0L,
                        )
                    }
                }
                val nextOffset = reviews.mapNotNull { (it["createTime"] as? Number)?.toLong() }
                    .filter { it in 1..Int.MAX_VALUE }
                    .minOrNull()
                    ?.toInt()
                val clearAll = root.get("clearAll")?.let { value ->
                    runCatching { value.asInt != 0 }.getOrDefault(false)
                } ?: false
                return ArticleParseResult(
                    articles = articles,
                    validResponse = true,
                    nextOffset = nextOffset,
                    endReached = clearAll || reviews.size < AppConfig.wereadPageSize,
                )
            }

            // 兼容旧结构：{ data: { list: [...] } } / { list: [...] } / { articles: [...] }
            var listJson: List<Map<String, Any?>>? = null
            val dataObj = root.get("data")?.asJsonObject
            if (dataObj != null && dataObj.get("list")?.isJsonArray == true) {
                listJson = gson.fromJson(dataObj.get("list").toString(), listType)
            } else if (root.get("list")?.isJsonArray == true) {
                listJson = gson.fromJson(root.get("list").toString(), listType)
            } else if (root.get("articles")?.isJsonArray == true) {
                listJson = gson.fromJson(root.get("articles").toString(), listType)
            }
            if (listJson == null) {
                logger.debug("微信读书返回无列表: ${raw.take(200)}")
                return ArticleParseResult(emptyList(), false)
            }
            val articles = listJson.mapNotNull { m ->
                val id = (m["id"] as? String) ?: (m["articleId"] as? String) ?: (m["article_id"] as? String)
                    ?: return@mapNotNull null
                WeReadArticle(
                    id = id,
                    title = m["title"] as? String ?: "",
                    link = "https://mp.weixin.qq.com/s/$id",
                    cover = (m["picUrl"] as? String) ?: (m["coverUrl"] as? String) ?: "",
                    publishTime = ((m["publishTime"] as? Number) ?: (m["publish_time"] as? Number))?.toLong() ?: 0L,
                )
            }
            ArticleParseResult(
                articles = articles,
                validResponse = true,
                endReached = listJson.size < AppConfig.wereadPageSize,
            )
        } catch (e: Exception) {
            logger.warn("文章列表解析失败: ${e.message}")
            ArticleParseResult(emptyList(), false)
        }
    }

    /**
     * 翻页抓取公众号文章直到目标日期范围已覆盖或没有更多
     *
     * 工业级特性：
     * - 空页重试：频控会返回空数组，连续 N 次空页才判定"没有更多"
     * - 退避：空页等待 empty_retry_backoff + 随机抖动
     * - 频控换号：连续空页达到阈值 → 冷却当前账号（下次翻页自动换号）
     * - 翻页间隔：content_interval + 随机抖动，降低风控
     *
     * @param mpId 微信读书 bookId
     * @param targetStartTs 目标起始时间戳（秒），用于提前停止
     * @return 收集到的文章列表（按发布时间倒序）
     */
    fun fetchAllArticles(
        mpId: String,
        targetStartTs: Long? = null,
        maxPages: Int = AppConfig.wereadMaxPages,
    ): List<WeReadArticle> = fetchAllArticlesResult(mpId, targetStartTs, maxPages).articles

    fun fetchAllArticlesResult(
        mpId: String,
        targetStartTs: Long? = null,
        maxPages: Int = AppConfig.wereadMaxPages,
    ): WeReadCollectionResult {
        val all = mutableListOf<WeReadArticle>()
        var offset = 0
        var pageIndex = 0
        var transientRetryCount = 0
        var coverageComplete = false
        var lastOutcome = PageOutcome.ERROR
        val seenArticleIds = mutableSetOf<String>()
        val seenOffsets = mutableSetOf<Int>()
        val maxTransientRetries = AppConfig.wereadEmptyRetryMax
        val retryBackoffMs = AppConfig.wereadEmptyRetryBackoff * 1000L

        while (pageIndex < maxPages) {
            if (!seenOffsets.add(offset)) {
                logger.warn("微信读书分页游标重复，停止抓取: mpId=$mpId, offset=$offset")
                break
            }
            val pageResult = try {
                fetchArticles(mpId, offset)
            } catch (e: Exception) {
                logger.warn("微信读书抓取异常（按空页处理重试）: ${e.message}")
                WeReadPage(emptyList(), outcome = PageOutcome.ERROR)
            }
            lastOutcome = pageResult.outcome

            if (pageResult.articles.isEmpty()) {
                when (pageResult.outcome) {
                    PageOutcome.NO_MORE_DATA -> {
                        coverageComplete = true
                        break
                    }
                    PageOutcome.HUMAN_VERIFY -> break
                    PageOutcome.ERROR, PageOutcome.PARSE_ERROR -> {
                        logger.warn("微信读书返回不可重试结果，停止抓取: mpId=$mpId, offset=$offset, outcome=${pageResult.outcome}")
                        break
                    }
                    PageOutcome.AUTH_EXPIRED -> {
                        if (!WeReadAccountPool.hasAvailableAccount()) break
                        logger.warn("微信读书账号凭证均失效，等待账号恢复: mpId=$mpId, offset=$offset")
                        seenOffsets.remove(offset)
                        continue
                    }
                    PageOutcome.NO_AVAILABLE_ACCOUNT -> break
                    PageOutcome.RATE_LIMITED -> {
                        if (!WeReadAccountPool.hasAvailableAccount()) break
                        logger.info("微信读书账号频控，切换可用账号: mpId=$mpId, offset=$offset")
                        seenOffsets.remove(offset)
                        continue
                    }
                    PageOutcome.RETRYABLE_ERROR -> {
                        transientRetryCount++
                        if (transientRetryCount >= maxTransientRetries) {
                            logger.warn("微信读书临时错误重试耗尽: mpId=$mpId, offset=$offset")
                            break
                        }
                        seenOffsets.remove(offset)
                        logger.warn("微信读书临时错误，第 $transientRetryCount/$maxTransientRetries 次重试: offset=$offset")
                        Thread.sleep(retryBackoffMs + Random.nextLong(0, 1000))
                        continue
                    }
                    PageOutcome.DATA -> Unit
                }
                continue
            }

            transientRetryCount = 0
            val pageIds = pageResult.articles.map { it.id }.filter { it.isNotBlank() }
            if (pageIds.isNotEmpty() && pageIds.all { it in seenArticleIds }) {
                logger.warn("微信读书返回重复页，停止抓取: mpId=$mpId, offset=$offset")
                break
            }
            seenArticleIds.addAll(pageIds)
            all.addAll(pageResult.articles)
            pageIndex++

            // 已翻到早于目标起始日期的文章，可提前停止
            if (targetStartTs != null && pageResult.articles.any { it.publishTime in 1..targetStartTs }) {
                logger.info("微信读书已翻到目标起始日期之前的文章，提前停止: mpId=$mpId, offset=$offset")
                coverageComplete = true
                break
            }

            // reviews 短页或 clearAll 表示到底；频控已有 -2014/429 明确语义。
            if (pageResult.endReached) {
                coverageComplete = true
                break
            }

            // weread-mp 实现与线上响应均使用 reviews 中最老的 createTime 作为下一页游标。
            // 旧结构没有该字段时才回退到文章时间或数量游标。
            val oldestTime = pageResult.articles.map { it.publishTime }.filter { it > 0 }.minOrNull()
            val nextOffset = pageResult.nextOffset
                ?: oldestTime?.takeIf { it <= Int.MAX_VALUE }?.toInt()
                ?: (offset.toLong() + pageResult.articles.size).takeIf { it <= Int.MAX_VALUE }?.toInt()
            if (nextOffset == null || nextOffset == offset) {
                logger.warn("微信读书分页游标未推进，停止抓取: mpId=$mpId, offset=$offset")
                break
            }
            offset = nextOffset
            // 翻页间隔 + 随机抖动
            val sleepMs = AppConfig.wereadContentInterval * 1000L + Random.nextLong(0, AppConfig.wereadContentJitter * 1000L)
            Thread.sleep(sleepMs)
        }
        return WeReadCollectionResult(
            articles = all.distinctBy { it.id },
            coverageComplete = coverageComplete,
            lastOutcome = lastOutcome,
            pagesFetched = pageIndex,
        )
    }

    private companion object {
        // UA 池：覆盖主流桌面浏览器，每次请求随机选取，防 UA 维度画像
        val UA_POOL = listOf(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        )

        fun randomUA(): String = UA_POOL[Random.nextInt(UA_POOL.size)]
    }
}
