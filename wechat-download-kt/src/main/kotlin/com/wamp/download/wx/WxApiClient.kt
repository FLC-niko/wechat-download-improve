package com.wamp.download.wx

import com.google.gson.Gson
import com.wamp.download.auth.AuthManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * 微信公众号后台 API 客户端
 *
 * 直接调用 mp.weixin.qq.com 接口，需要有效的微信登录 token + cookie。
 * 提取自适配壳和 ArticleFetcher 的公共逻辑，供任务处理复用。
 */
class WxApiClient {
    private val logger = LoggerFactory.getLogger(WxApiClient::class.java)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    data class AccountSearchResult(
        val fakeid: String,
        val nickname: String,
        val alias: String,
        val headImg: String,
    )

    data class WxArticle(
        val aid: String,
        val title: String,
        val link: String,
        val digest: String,
        val cover: String,
        val author: String,
        val publishTime: Long,  // 秒级时间戳
    )

    enum class TokenStatus {
        VALID,
        EXPIRED,
        INVALIDATED,
        RATE_LIMITED,
        UNAVAILABLE,
    }

    class WxApiException(
        val operation: String,
        val retCode: String,
        val errMsg: String,
    ) : RuntimeException("$operation API 错误: ret=$retCode, err_msg=$errMsg") {
        val isRateLimited: Boolean
            get() = classifyTokenFailure(retCode, errMsg) == TokenStatus.RATE_LIMITED

        val isAuthExpired: Boolean
            get() = classifyTokenFailure(retCode, errMsg) == TokenStatus.EXPIRED

        val isMainChannelInvalidated: Boolean
            get() = classifyTokenFailure(retCode, errMsg) == TokenStatus.INVALIDATED
    }

    companion object {
        fun classifyTokenFailure(retCode: String?, errMsg: String): TokenStatus {
            val normalizedMessage = errMsg.lowercase()
            return when {
                retCode == "200013" -> TokenStatus.INVALIDATED
                normalizedMessage.contains("freq") ||
                    normalizedMessage.contains("频控") ||
                    normalizedMessage.contains("频繁") -> TokenStatus.RATE_LIMITED
                retCode == "200003" ||
                    retCode == "200040" ||
                    normalizedMessage.contains("invalid session") ||
                    normalizedMessage.contains("csrf") ||
                    normalizedMessage.contains("login") ||
                    normalizedMessage.contains("登录超时") ||
                    normalizedMessage.contains("登录失效") ||
                    normalizedMessage.contains("登录过期") ||
                    normalizedMessage.contains("请重新登录") -> TokenStatus.EXPIRED
                else -> TokenStatus.UNAVAILABLE
            }
        }

        private fun normalizeRetCode(ret: Any?): String? = when (ret) {
            null -> null
            is Number -> ret.toLong().toString()
            else -> ret.toString().trim().removeSuffix(".0")
        }
    }

    /**
     * 检查 token 状态（轻量探测：搜索"微信"取 1 条）。
     *
     * 成功响应即表示凭证可用，空搜索结果不应被误判为登录失效。
     */
    fun checkTokenStatus(): TokenStatus {
        if (!AuthManager.isLoggedIn() || AuthManager.isExpired()) return TokenStatus.EXPIRED
        return try {
            searchAccounts("微信", 1)
            TokenStatus.VALID
        } catch (e: WxApiException) {
            val status = if (e.isRateLimited || e.isAuthExpired || e.isMainChannelInvalidated) {
                classifyTokenFailure(e.retCode, e.errMsg)
            } else {
                TokenStatus.UNAVAILABLE
            }
            logger.warn("token 有效性探测失败: status=$status, ret=${e.retCode}, err_msg=${e.errMsg}")
            status
        } catch (e: Exception) {
            logger.warn("token 有效性探测失败: ${e.message}")
            TokenStatus.UNAVAILABLE
        }
    }

    fun checkTokenValid(): Boolean = checkTokenStatus() == TokenStatus.VALID

    /**
     * 搜索公众号
     *
     * @param query 关键词（公众号名称）
     * @param count 返回数量
     * @return 搜索结果列表
     */
    fun searchAccounts(query: String, count: Int = 5): List<AccountSearchResult> {
        val creds = AuthManager.get()
        if (creds.token.isEmpty() || creds.cookie.isEmpty()) {
            throw IllegalStateException("微信未登录，无法搜索公众号")
        }

        val url = "https://mp.weixin.qq.com/cgi-bin/searchbiz".toHttpUrl().newBuilder()
            .addQueryParameter("action", "search_biz")
            .addQueryParameter("token", creds.token)
            .addQueryParameter("lang", "zh_CN")
            .addQueryParameter("f", "json")
            .addQueryParameter("ajax", "1")
            .addQueryParameter("query", query)
            .addQueryParameter("begin", "0")
            .addQueryParameter("count", count.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Cookie", creds.cookie)
            .get()
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("searchbiz HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            val result = gson.fromJson(body, Map::class.java) ?: return emptyList()

            ensureApiSuccess(result, "微信搜索")

            val list = result["list"] as? List<*> ?: return emptyList()
            return list.mapNotNull { item ->
                val a = item as? Map<*, *> ?: return@mapNotNull null
                AccountSearchResult(
                    fakeid = a["fakeid"] as? String ?: "",
                    nickname = a["nickname"] as? String ?: "",
                    alias = a["alias"] as? String ?: "",
                    headImg = a["round_head_img"] as? String ?: "",
                )
            }
        }
    }

    /**
     * 按名称精确/模糊匹配公众号，返回 fakeid
     *
     * @param name 公众号名称
     * @return 匹配的 fakeid（找不到返回 null）
     */
    fun findFakeidByName(name: String): String? {
        val results = searchAccounts(name)
        if (results.isEmpty()) return null

        // 优先精确匹配
        val exact = results.firstOrNull { it.nickname == name }
        if (exact != null) return exact.fakeid

        // 其次取第一个结果
        return results.first().fakeid
    }

    /**
     * 获取公众号文章列表（appmsgpublish 接口）
     *
     * @param fakeid 公众号 fakeid
     * @param begin 偏移量
     * @param count 数量（最大 100）
     * @return 文章列表
     */
    fun fetchArticles(fakeid: String, begin: Int = 0, count: Int = 10): List<WxArticle> {
        val creds = AuthManager.get()
        if (creds.token.isEmpty() || creds.cookie.isEmpty()) {
            throw IllegalStateException("微信未登录，无法获取文章列表")
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
                throw RuntimeException("appmsgpublish HTTP ${resp.code}")
            }

            val body = resp.body?.string().orEmpty()
            val result = gson.fromJson(body, Map::class.java) ?: return emptyList()

            ensureApiSuccess(result, "微信文章(fakeid=${fakeid.take(8)}...)")

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
                    WxArticle(
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

    private fun ensureApiSuccess(result: Map<*, *>, operation: String) {
        val baseResp = result["base_resp"] as? Map<*, *> ?: return
        val retCode = normalizeRetCode(baseResp["ret"]) ?: return
        if (retCode == "0") return
        val errMsg = baseResp["err_msg"]?.toString() ?: "未知错误"
        throw WxApiException(operation, retCode, errMsg)
    }
}
