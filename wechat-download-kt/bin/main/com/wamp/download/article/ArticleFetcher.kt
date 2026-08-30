package com.wamp.download.article

import com.google.gson.Gson
import com.wamp.download.auth.AuthManager
import com.wamp.download.config.AppConfig
import com.wamp.download.store.Article
import com.wamp.download.store.ArticleStore
import com.wamp.download.store.SubscriptionStore
import com.wamp.download.task.ChannelStateManager
import com.wamp.download.weread.WeReadWebClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * 文章列表抓取器 — 对齐 Python 版 rss_poller.py 的 _fetch_article_list
 *
 * 调微信 appmsgpublish 接口获取文章列表元数据（不抓正文，零风控风险）
 */
class ArticleFetcher {
    private val logger = LoggerFactory.getLogger(ArticleFetcher::class.java)
    private val gson = Gson()
    private val weReadWebClient = WeReadWebClient()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    /**
     * 抓取单个公众号的文章列表
     *
     * @param fakeid 公众号 fakeid
     * @param begin 偏移量
     * @param count 获取数量（最大 100）
     * @return 文章列表
     */
    fun fetchList(fakeid: String, begin: Int = 0, count: Int = 10): List<Article> {
        val creds = AuthManager.get()
        if (!AuthManager.isLoggedIn()) {
            logger.warn("未登录，无法抓取文章列表")
            return emptyList()
        }

        val url = "https://mp.weixin.qq.com/cgi-bin/appmsgpublish".toHttpUrl().newBuilder()
            .addQueryParameter("sub", "list")
            .addQueryParameter("search_field", "null")
            .addQueryParameter("begin", begin.toString())
            .addQueryParameter("count", count.toString())
            .addQueryParameter("query", "")
            .addQueryParameter("fakeid", fakeid)
            .addQueryParameter("type", "101_1")
            .addQueryParameter("free_publish_type", "1")
            .addQueryParameter("sub_action", "list_ex")
            .addQueryParameter("token", creds.token)
            .addQueryParameter("lang", "zh_CN")
            .addQueryParameter("f", "json")
            .addQueryParameter("ajax", "1")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "https://mp.weixin.qq.com/")
            .header("Cookie", creds.cookie)
            .get()
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                logger.error("appmsgpublish HTTP ${resp.code}")
                return emptyList()
            }

            val body = resp.body?.string().orEmpty()
            val result = gson.fromJson(body, Map::class.java) ?: return emptyList()

            // 检查 base_resp
            val baseResp = result["base_resp"] as? Map<*, *>
            val ret = baseResp?.get("ret")
            if (ret != 0.0 && ret != 0 && ret != "0") {
                val errMsg = baseResp?.get("err_msg") ?: "未知错误"
                logger.warn("微信 API 错误: fakeid=${fakeid.take(8)}..., ret=$ret, err_msg=$errMsg")
                // 频控（ret=200013）→ 标记主通道降级，后续直接走微信读书回退
                if (ret == 200013.0 || ret == 200013 || "200013" == ret.toString() ||
                    errMsg.toString().contains("freq", ignoreCase = true)
                ) {
                    ChannelStateManager.markMainChannelFreqControl(AppConfig.mainDegradedDuration * 1000)
                }
                return emptyList()
            }

            // 解析 publish_page → publish_list → publish_info → appmsgex
            var publishPage = result["publish_page"]
            if (publishPage is String) {
                publishPage = gson.fromJson(publishPage, Map::class.java)
            }
            if (publishPage !is Map<*, *>) return emptyList()

            val publishList = publishPage["publish_list"] as? List<*> ?: return emptyList()

            return publishList.mapNotNull outer@{ item ->
                val publishInfo = (item as? Map<*, *>)?.get("publish_info")
                val info = when (publishInfo) {
                    is String -> gson.fromJson(publishInfo, Map::class.java)
                    is Map<*, *> -> publishInfo
                    else -> null
                } ?: return@outer null

                val appmsgex = info["appmsgex"] as? List<*> ?: return@outer null
                appmsgex.mapNotNull inner@{ article ->
                    val a = article as? Map<*, *> ?: return@inner null
                    Article(
                        fakeid = fakeid,
                        aid = a["aid"] as? String ?: "",
                        title = a["title"] as? String ?: "",
                        link = a["link"] as? String ?: "",
                        digest = a["digest"] as? String ?: "",
                        cover = a["cover"] as? String ?: "",
                        author = a["author"] as? String ?: "",
                        publishTime = (a["update_time"] as? Number)?.toLong() ?: 0L,
                    )
                }
            }.flatten()
        }
    }

    /**
     * 抓取并保存单个公众号的文章（带回退：主通道频控 → 微信读书）
     *
     * @return 新增文章数
     */
    fun fetchAndSave(fakeid: String, begin: Int = 0, count: Int = 10): Int {
        val articles = fetchListWithFallback(fakeid, begin, count)
        if (articles.isEmpty()) return 0

        val newCount = ArticleStore.saveAll(fakeid, articles, source = "poll")
        SubscriptionStore.updateLastPoll(fakeid)

        if (newCount > 0) {
            logger.info("抓取完成: fakeid=${fakeid.take(8)}..., 新增 $newCount 篇")
        }
        return newCount
    }

    /**
     * 带回退的文章列表抓取：主通道（appmsgpublish）→ 微信读书官方回退
     *
     * 主通道被官方限制（ret=200013 频控）后返回空，此时标记降级并走
     * 微信读书回退，保证后台轮询也能持续入库最新文章。
     */
    fun fetchListWithFallback(fakeid: String, begin: Int = 0, count: Int = 10): List<Article> {
        // 主通道处于降级期 → 直接走微信读书回退，避免反复打主通道
        if (!ChannelStateManager.isMainChannelAvailable()) {
            val fallback = fetchFromWeRead(fakeid)
            if (fallback.isNotEmpty()) return fallback
            return emptyList()
        }

        val main = fetchList(fakeid, begin, count)
        if (main.isNotEmpty()) return main

        // 主通道空/失败（多为频控）→ 微信读书回退
        val fallback = fetchFromWeRead(fakeid)
        return if (fallback.isNotEmpty()) fallback else emptyList()
    }

    /**
     * 微信读书官方直连回退（appmsgpublish 频控时）
     *
     * mpId 定位：缓存 → fakeid 推导。只翻最近若干页拿增量文章。
     */
    private fun fetchFromWeRead(fakeid: String): List<Article> {
        if (!weReadWebClient.isLoggedIn()) {
            logger.warn("回退跳过: 没有可用的微信读书账号, fakeid=${fakeid.take(8)}...")
            return emptyList()
        }

        var mpId = SubscriptionStore.get(fakeid)?.wereadMpId.orEmpty()
        if (mpId.isBlank()) {
            mpId = weReadWebClient.computeMpId(fakeid)
            if (mpId.isNotBlank()) {
                SubscriptionStore.saveWereadMpId(fakeid, mpId)
                logger.info("回退: 从 fakeid 推导 mpId: $fakeid -> $mpId")
            }
        }
        if (mpId.isBlank()) {
            logger.warn("回退失败: 无法定位 mpId, fakeid=${fakeid.take(8)}...")
            return emptyList()
        }

        // 只翻最近 7 天增量，避免长时间翻页
        val recentTs = System.currentTimeMillis() / 1000 - 7 * 86400L
        val articles = try {
            weReadWebClient.fetchAllArticles(mpId, recentTs, maxPages = 10)
        } catch (e: Exception) {
            logger.warn("回退抓取异常: ${e.message}")
            return emptyList()
        }

        logger.info("回退: 微信读书抓取 ${articles.size} 篇: fakeid=${fakeid.take(8)}...")
        return articles.map {
            Article(
                fakeid = fakeid,
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
}
