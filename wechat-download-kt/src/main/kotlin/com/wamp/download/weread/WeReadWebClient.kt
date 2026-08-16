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
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 微信读书官方 Web 接口客户端（2026 新版）
 *
 * 直接调用 weread.qq.com，不经过任何第三方代理。
 *
 * 认证方式（双保险）：
 * - Header: x-vid / x-skey（新版前端）
 * - Cookie: wr_vid / wr_skey（旧接口兼容）
 * 凭证由 WeReadCookieStore 统一管理。
 *
 * 已实测（2026-08）可用接口：
 * - GET /web/mp/articles?bookId={mpId}&offset={offset}
 *   分页获取公众号文章，返回结构为 reviews[].subReviews[].review.mpInfo
 *   文章字段：originalId(=链接token) / title / pic_url / time / mp_name
 *
 * 已实测失效（官方下线，勿再使用）：
 * - POST /web/mp/wxs2mp（404，公众号解析）
 * - GET  /web/mp/info（404，公众号信息）
 *
 * 公众号 bookId 推导（无需解析接口）：
 *   微信 fakeid 为 base64，解码后即 bookId 数字段，前缀 "MP_WXS_"
 *   例：fakeid MzA3MzMwMzEwNg== → base64解码 "3073303106" → MP_WXS_3073303106
 *   见 computeMpId(fakeid)
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

    // --- 认证 ---

    fun isLoggedIn(): Boolean = WeReadCookieStore.isLoggedIn()

    private fun authRequest(builder: Request.Builder): Request.Builder {
        val creds = WeReadCookieStore.get()
        require(creds.wrSkey.isNotEmpty() && creds.wrVid.isNotEmpty()) { "微信读书未登录" }
        return builder
            .header("x-vid", creds.wrVid)
            .header("x-skey", creds.wrSkey)
            .header("Cookie", creds.toCookieString())
            .header("Referer", "$baseUrl/")
            .header("User-Agent", UA)
    }

    /**
     * 从微信 fakeid 推导微信读书 bookId（官方解析接口 wxs2mp 已下线）
     *
     * fakeid 是 base64 编码的 biz 数字段，解码后加前缀 "MP_WXS_" 即 bookId。
     * 例：fakeid "MzA3MzMwMzEwNg==" → "3073303106" → "MP_WXS_3073303106"
     *
     * @return bookId，fakeid 非法/解码失败返回 ""
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
     * 分页获取公众号文章（官方 /web/mp/articles）
     *
     * 实际响应结构（2026）：
     * {
     *   "reviews": [
     *     { "subReviews": [ { "review": { "mpInfo": {
     *          "originalId": "<文章token>", "title": "...", "pic_url": "...", "time": 秒级 } } } ] }
     *   ],
     *   "synckey": ...
     * }
     * reviews = 群发事件，subReviews[].review.mpInfo = 单篇文章。
     *
     * @param mpId 微信读书 bookId
     * @param offset 偏移量（按 reviews 数量，从 0 开始，每页约 20 条）
     * @return 文章列表
     */
    fun fetchArticles(mpId: String, offset: Int): List<WeReadArticle> {
        if (!isLoggedIn()) return emptyList()
        val url = "$baseUrl/web/mp/articles".toHttpUrl()
            .newBuilder()
            .addQueryParameter("bookId", mpId)
            .addQueryParameter("offset", offset.toString())
            .build()
        val req = authRequest(Request.Builder().url(url)).build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val loginExpired = resp.code == 401 ||
                (resp.isSuccessful && raw.contains("\"errCode\":-2012"))

            if (loginExpired) {
                // 凭证失效（HTTP 401 或业务码 -2012 登录超时）→ 触发被动刷新
                logger.warn("微信读书凭证失效，触发被动刷新 (http=${resp.code})")
                val ok = WeReadWebAuth.proactiveRefresh()
                if (ok) {
                    // 刷新后重试一次
                    return fetchArticles(mpId, offset)
                }
                // 刷新失败：达到阈值则异步触发重新扫码（防重入）
                WeReadWebAuth.requestReloginIfNeeded()
                return emptyList()
            }
            if (!resp.isSuccessful) {
                logger.warn("微信读书获取文章失败: HTTP ${resp.code}, offset=$offset")
                return emptyList()
            }
            return parseArticleList(raw)
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
            val page = try {
                fetchArticles(mpId, offset)
            } catch (e: Exception) {
                logger.warn("微信读书抓取异常（按空页处理重试）: ${e.message}")
                emptyList()
            }

            if (page.isEmpty()) {
                emptyRetryCount++
                if (emptyRetryCount >= maxEmptyRetries) {
                    logger.info("微信读书连续 ${maxEmptyRetries} 次空页，判定无更多文章: mpId=$mpId, offset=$offset")
                    break
                }
                logger.warn("微信读书空页（可能频控），第 ${emptyRetryCount}/${maxEmptyRetries} 次重试: offset=$offset, 等待 ${emptyBackoffMs}ms")
                Thread.sleep(emptyBackoffMs + Random.nextLong(0, 5000))
                continue  // 重试同一 offset
            }

            // 有数据，重置空页计数
            emptyRetryCount = 0
            all.addAll(page)

            // 已翻到早于目标起始日期的文章，可提前停止
            if (targetStartTs != null && page.any { it.publishTime in 1..targetStartTs }) {
                logger.info("微信读书已翻到目标起始日期之前的文章，提前停止: mpId=$mpId, offset=$offset")
                break
            }

            // offset 按 reviews 数量步进（每页 reviews 数不固定，直接按返回条数推进 + 保守取整）
            offset += page.size.coerceAtLeast(1)
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
