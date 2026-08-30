package com.wamp.download.weread

import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * 微信读书官方凭证持久化（单账号兼容层）
 *
 * 多账号化后，此对象仅作为"主账号"（最早登录账号）的便捷访问入口，
 * 底层全部委托 WeReadAccountPool，保持旧调用方（WeReadWebClient / WeReadWebAuth 无参版 / 路由）
 * 无需改动即可继续工作。
 *
 * - get()            → 主账号凭证
 * - save()           → 更新/新增账号凭证
 * - updateFromSetCookie() / markRefreshed() / markRefreshFailed() → 操作主账号
 * - setLoggedIn()    → 登录成功后写入账号池（以 webLoginVid 为账号标识）
 */
object WeReadCookieStore {
    private val logger = LoggerFactory.getLogger(WeReadCookieStore::class.java)

    /** 已由 WeReadAccountPool.loadAll() 负责加载与迁移 */
    fun load() { /* no-op */ }

    fun get(): WeReadWebCredentials = WeReadAccountPool.primaryCredentials()

    fun isLoggedIn(): Boolean = WeReadAccountPool.hasLoggedInAccount()

    fun save(creds: WeReadWebCredentials) {
        WeReadAccountPool.upsertCredentials(creds)
    }

    /** 清除主账号（单账号语义保留） */
    fun clear() {
        WeReadAccountPool.primaryAccount()?.let { WeReadAccountPool.delete(it.vid) }
        logger.info("微信读书主账号凭证已清除")
    }

    /**
     * 从响应 set-cookie 头更新主账号凭证（合并更新，服务端可能只返回部分字段）
     *
     * @return 是否发生变更（拿到至少一个有效字段）
     */
    fun updateFromSetCookie(setCookies: List<String>, username: String? = null): Boolean {
        if (setCookies.isEmpty()) return false
        val creds = get()
        if (creds.wrVid.isBlank()) return false

        var newSkey = creds.wrSkey
        var newVid = creds.wrVid
        var newRt = creds.wrRt
        var changed = false

        for (header in setCookies) {
            val pair = header.substringBefore(';').trim()
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val name = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1).trim()
            when (name) {
                "wr_skey" -> if (value.isNotEmpty()) { newSkey = value; changed = true }
                "wr_vid" -> if (value.isNotEmpty()) { newVid = value; changed = true }
                "wr_rt" -> if (value.isNotEmpty()) { newRt = value; changed = true }
            }
        }

        if (!changed) return false

        WeReadAccountPool.updateCredentials(
            creds.wrVid,
            creds.copy(
                wrSkey = newSkey,
                wrVid = newVid,
                wrRt = newRt,
                username = username ?: creds.username,
                lastRefreshTime = System.currentTimeMillis(),
                refreshFailCount = 0,
            ),
        )
        return true
    }

    /** 主动刷新成功后调用（操作主账号） */
    fun markRefreshed() {
        val acc = WeReadAccountPool.primaryAccount() ?: return
        WeReadAccountPool.updateCredentials(
            acc.vid,
            acc.credentials.copy(
                lastRefreshTime = System.currentTimeMillis(),
                refreshFailCount = 0,
            ),
        )
    }

    /** 刷新失败时调用（操作主账号），返回递增后的失败计数 */
    fun markRefreshFailed(): Int {
        val acc = WeReadAccountPool.primaryAccount() ?: return 0
        val count = acc.credentials.refreshFailCount + 1
        WeReadAccountPool.updateCredentials(
            acc.vid,
            acc.credentials.copy(refreshFailCount = count),
        )
        return count
    }

    /** 登录成功后设置完整凭证（以 webLoginVid 写入账号池） */
    fun setLoggedIn(wrSkey: String, wrVid: String, wrRt: String, username: String) {
        WeReadAccountPool.upsertCredentials(
            WeReadWebCredentials(
                wrSkey = wrSkey,
                wrVid = wrVid,
                wrRt = wrRt,
                username = username,
                loginTime = Instant.now().toEpochMilli(),
                lastRefreshTime = Instant.now().toEpochMilli(),
                refreshFailCount = 0,
            ),
        )
    }
}
