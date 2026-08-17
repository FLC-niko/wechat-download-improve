package com.wamp.download.weread

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.wamp.download.config.AppConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
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
    )

    private enum class PageKind { SUCCESS, EXPIRED, ERROR }

    // --- 认证 ---

    /** 兼容入口：主账号是否已登录 */
    fun isLoggedIn(): Boolean = WeReadCookieStore.isLoggedIn()

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
            .header("User-Agent", UA)
    }

    // --- 请求节流（防多任务并发叠加触发风控） ---
    // 单账号在途并发控制：每个账号一把信号量，同一账号同时最多 1 个请求在途，
    // 多任务/多 worker 并发抓不同公众号时，同一账号的请求完全串行排队。
    private val accountSemaphores = ConcurrentHashMap<String, Semaphore>()

    private fun semaphoreFor(vid: String): Semaphore =
        accountSemaphores.computeIfAbsent(vid) { Semaphore(AppConfig.wereadMaxConcurrentPerAccount) }

    // 单账号两次请求最小间隔：记录每个账号上次实际发请求的时刻，不足则 sleep 补齐，
    // 防止"串行排队后仍密集连发"（如多个 worker 各抓一个公众号时相互叠加）。
    private val lastRequestAt = ConcurrentHashMap<String, Long>()

    private fun throttleAccount(vid: String) {
        val minIntervalMs = AppConfig.wereadMinRequestInterval * 1000
        while (true) {
            val now = System.currentTimeMillis()
            val prev = lastRequestAt[vid] ?: 0L
            val wait = prev + minIntervalMs - now
            if (wait <= 0) {
                // CAS 抢占更新时间戳；并发时只有一个线程成功进入
                if (lastRequestAt.replace(vid, prev, now) || lastRequestAt.putIfAbsent(vid, now) == null) {
                    return
                }
            } else {
                Thread.sleep(wait + Random.nextLong(0, 500))
            }
        }
    }

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
     * 2. 用该账号请求；凭证失效（401 / errCode=-2012）→ 尝试被动刷新 → 失败则标记 EXPIRED 并换号重试
     * 3. 非失效错误不换号，直接返回空
     *
     * @return 本页文章列表 + 实际使用的账号 vid（空页时上层可据此做频控冷却）
     */
    fun fetchArticles(mpId: String, offset: Int): WeReadPage {
        val attemptLimit = WeReadAccountPool.size().coerceAtLeast(1)
        repeat(attemptLimit) {
            val account = WeReadAccountPool.acquire() ?: return WeReadPage(emptyList())
            try {
                val (kind, articles) = fetchArticlesForAccount(account, mpId, offset)
                when (kind) {
                    PageKind.SUCCESS -> {
                        WeReadAccountPool.markSuccess(account.vid)
                        return WeReadPage(articles, account.vid)
                    }
                    PageKind.EXPIRED -> {
                        // 尝试被动刷新一次，成功则用新凭证重试本页
                        val refreshed = WeReadWebAuth.proactiveRefresh(account.vid)
                        if (refreshed) {
                            val (k2, a2) = fetchArticlesForAccount(account, mpId, offset)
                            if (k2 == PageKind.SUCCESS) {
                                WeReadAccountPool.markSuccess(account.vid)
                                return WeReadPage(a2, account.vid)
                            }
                        }
                        // 刷新失败：标记失效，异步重扫码（按账号防重入），换号继续
                        WeReadAccountPool.markExpired(account.vid, "凭证失效(401/-2012)")
                        WeReadWebAuth.requestReloginIfNeeded(account.vid)
                    }
                    PageKind.ERROR -> return WeReadPage(emptyList())  // 非失效错误，不换号
                }
            } catch (e: Exception) {
                logger.warn("微信读书请求异常（换号重试）: vid=${account.vid}, ${e.message}")
            } finally {
                WeReadAccountPool.release(account.vid)
            }
        }
        return WeReadPage(emptyList())
    }

    /**
     * 用指定账号发起单页请求（底层，不涉及账号池调度）
     */
    private fun fetchArticlesForAccount(
        account: WeReadAccount,
        mpId: String,
        offset: Int,
    ): Pair<PageKind, List<WeReadArticle>> {
        val sem = semaphoreFor(account.vid)
        sem.acquire()
        try {
            // 同一账号两次实际请求保持最小间隔（跨 worker 兜底，防密集连发）
            throttleAccount(account.vid)
            return doFetchArticlesForAccount(account, mpId, offset)
        } finally {
            sem.release()
        }
    }

    /** 单账号单页请求（不含节流，供信号量包裹调用） */
    private fun doFetchArticlesForAccount(
        account: WeReadAccount,
        mpId: String,
        offset: Int,
    ): Pair<PageKind, List<WeReadArticle>> {
        val creds = account.credentials
        if (creds.wrSkey.isEmpty() || creds.wrVid.isEmpty()) {
            return PageKind.EXPIRED to emptyList()
        }
        val url = "$baseUrl/web/mp/articles".toHttpUrl()
            .newBuilder()
            .addQueryParameter("bookId", mpId)
            .addQueryParameter("offset", offset.toString())
            .build()
        val req = authRequest(Request.Builder().url(url), creds).build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val loginExpired = resp.code == 401 ||
                (resp.isSuccessful && raw.contains("\"errCode\":-2012"))
            if (loginExpired) {
                logger.warn("微信读书凭证失效 (http=${resp.code}): vid=${creds.wrVid}")
                return PageKind.EXPIRED to emptyList()
            }
            if (!resp.isSuccessful) {
                logger.warn("微信读书获取文章失败: HTTP ${resp.code}, offset=$offset, vid=${creds.wrVid}")
                return PageKind.ERROR to emptyList()
            }
            return PageKind.SUCCESS to parseArticleList(raw)
        }
    }

    private fun parseArticleList(raw: String): List<WeReadArticle> {
        return try {
            val obj = JsonParser.parseString(raw)
            if (!obj.isJsonObject) return emptyList()
            val root = obj.asJsonObject

            // 新版结构：reviews[].subReviews[].review.mpInfo
            if (root.get("reviews")?.isJsonArray == true) {
                val reviews: List<Map<String, Any?>> =
                    gson.fromJson(root.get("reviews").toString(), listType)
                return reviews.flatMap { review ->
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
                return emptyList()
            }
            listJson.mapNotNull { m ->
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
        } catch (e: Exception) {
            logger.warn("文章列表解析失败: ${e.message}")
            emptyList()
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
    ): List<WeReadArticle> {
        val all = mutableListOf<WeReadArticle>()
        var offset = 0
        var pageIndex = 0
        var emptyRetryCount = 0
        val maxEmptyRetries = AppConfig.wereadEmptyRetryMax
        val emptyBackoffMs = AppConfig.wereadEmptyRetryBackoff * 1000L

        while (pageIndex < maxPages) {
            val pageResult = try {
                fetchArticles(mpId, offset)
            } catch (e: Exception) {
                logger.warn("微信读书抓取异常（按空页处理重试）: ${e.message}")
                WeReadPage(emptyList())
            }

            if (pageResult.articles.isEmpty()) {
                emptyRetryCount++
                if (emptyRetryCount >= maxEmptyRetries) {
                    // 连续空页疑似频控：冷却该账号并换号，空页计数重置
                    pageResult.accountVid?.let { WeReadAccountPool.markFreqControl(it) }
                    emptyRetryCount = 0
                    if (!WeReadAccountPool.hasAvailableAccount()) {
                        logger.warn("微信读书所有账号不可用（冷却/失效），停止抓取: mpId=$mpId, offset=$offset")
                        break
                    }
                    logger.info("微信读书连续空页，已冷却账号并换号: mpId=$mpId, offset=$offset")
                    continue
                }
                logger.warn("微信读书空页（可能频控），第 ${emptyRetryCount}/${maxEmptyRetries} 次重试: offset=$offset, 等待 ${emptyBackoffMs}ms")
                Thread.sleep(emptyBackoffMs + Random.nextLong(0, 5000))
                continue  // 重试同一 offset
            }

            // 有数据，重置空页计数
            emptyRetryCount = 0
            all.addAll(pageResult.articles)

            // 已翻到早于目标起始日期的文章，可提前停止
            if (targetStartTs != null && pageResult.articles.any { it.publishTime in 1..targetStartTs }) {
                logger.info("微信读书已翻到目标起始日期之前的文章，提前停止: mpId=$mpId, offset=$offset")
                break
            }

            // offset 按 reviews 数量步进
            offset += pageResult.articles.size.coerceAtLeast(1)
            pageIndex++
            // 翻页间隔 + 随机抖动
            val sleepMs = AppConfig.wereadContentInterval * 1000L + Random.nextLong(0, AppConfig.wereadContentJitter * 1000L)
            Thread.sleep(sleepMs)
        }
        return all.distinctBy { it.id }
    }

    private companion object {
        const val UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
