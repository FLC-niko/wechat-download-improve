package com.wamp.download.weread

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.wamp.download.config.AppConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * 微信读书账号池（多账号存储 + 调度）
 *
 * - 持久化：data/weread_accounts/{vid}.json，每个账号一个文件
 * - 调度：least_used（lastUsedTime 升序 + freqControlCount 升序）
 * - 互斥：同账号同一时间只允许一个请求（inUse 标记，不持久化）
 * - 状态机：ACTIVE → COOLDOWN（频控，到期自动恢复）→ EXPIRED（失效）/ DISABLED（手动禁）
 * - 兼容：旧单账号文件 data/.weread_web_credentials.json 启动时自动迁移为首个账号
 */
object WeReadAccountPool {
    private val logger = LoggerFactory.getLogger(WeReadAccountPool::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val accounts = ConcurrentHashMap<String, WeReadAccount>()  // vid -> account
    private val inUse = ConcurrentHashMap<String, Boolean>()           // vid -> 占用标记
    private val saveLock = Any()
    private val scheduleLock = Any()

    private fun accountDir(): File = File(AppConfig.wereadAccountDir).apply { mkdirs() }

    init {
        loadAll()
    }

    // ── 加载 / 迁移 ──────────────────────────────

    fun loadAll() {
        migrateLegacy()
        accountDir().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.forEach { file ->
                try {
                    val acc = gson.fromJson(file.readText(), WeReadAccount::class.java)
                    if (acc.vid.isNotBlank()) {
                        // 落盘时可能为 COOLDOWN，读入时若已过冷却则恢复 ACTIVE
                        if (acc.status == AccountStatus.COOLDOWN && System.currentTimeMillis() >= acc.cooldownUntil) {
                            acc.status = AccountStatus.ACTIVE
                            acc.cooldownUntil = 0L
                        }
                        accounts[acc.vid] = acc
                        logger.info("微信读书账号已加载: vid=${acc.vid}, username=${acc.credentials.username}, status=${acc.status}")
                    }
                } catch (e: Exception) {
                    logger.warn("微信读书账号解析失败: ${file.name}, ${e.message}")
                }
            }
    }

    /** 迁移旧单账号文件 → 账号池（作为首个账号），迁移后删除旧文件 */
    private fun migrateLegacy() {
        val legacy = File(AppConfig.wereadCredentialsFile)
        if (!legacy.exists()) return
        try {
            val creds = gson.fromJson(legacy.readText(), WeReadWebCredentials::class.java)
            if (creds.wrVid.isNotBlank() && !accounts.containsKey(creds.wrVid)) {
                val acc = WeReadAccount(vid = creds.wrVid, credentials = creds)
                accounts[creds.wrVid] = acc
                save(acc)
                logger.info("已从旧单账号文件迁移: vid=${creds.wrVid}, username=${creds.username}")
            }
            legacy.delete()
            logger.info("旧单账号文件已清理: ${legacy.absolutePath}")
        } catch (e: Exception) {
            logger.warn("旧单账号文件迁移失败: ${e.message}")
        }
    }

    // ── 查询 ────────────────────────────────────

    fun list(): List<WeReadAccount> = accounts.values.sortedBy { it.vid }

    fun get(vid: String): WeReadAccount? = accounts[vid]

    fun size(): Int = accounts.size

    /** 是否存在可用账号（只读探测，不占用） */
    fun hasAvailableAccount(now: Long = System.currentTimeMillis()): Boolean = synchronized(scheduleLock) {
        accounts.values.any { !inUse.getOrDefault(it.vid, false) && it.isAvailable(now) }
    }

    /** 是否存在登录有效的账号，不受当前请求占用状态影响。 */
    fun hasLoggedInAccount(now: Long = System.currentTimeMillis()): Boolean =
        accounts.values.any { it.hasWebCredentials() && it.isAvailable(now) }

    /** 兼容层：主账号（最早登录），供旧单账号逻辑使用 */
    fun primaryAccount(): WeReadAccount? =
        accounts.values.minByOrNull { it.credentials.loginTime }

    fun primaryCredentials(): WeReadWebCredentials =
        primaryAccount()?.credentials ?: WeReadWebCredentials()

    // ── 写操作 ──────────────────────────────────

    /** 新增账号（vid 已存在则返回 false） */
    fun addAccount(account: WeReadAccount): Boolean {
        if (account.vid.isBlank() || accounts.containsKey(account.vid)) return false
        accounts[account.vid] = account
        save(account)
        logger.info("微信读书账号已添加: vid=${account.vid}, username=${account.credentials.username}")
        return true
    }

    /** 新增或更新账号凭证（登录成功后调用） */
    fun upsertCredentials(creds: WeReadWebCredentials): Boolean {
        val existing = accounts[creds.wrVid]
        return if (existing != null) {
            existing.credentials = creds
            if (existing.status == AccountStatus.EXPIRED || existing.status == AccountStatus.COOLDOWN) {
                existing.status = AccountStatus.ACTIVE
                existing.cooldownUntil = 0L
                existing.disabledReason = ""
            }
            save(existing)
            true
        } else {
            addAccount(WeReadAccount(vid = creds.wrVid, credentials = creds))
        }
    }

    /** 更新指定账号的凭证 */
    fun updateCredentials(vid: String, creds: WeReadWebCredentials): Boolean {
        val acc = accounts[vid] ?: return false
        acc.credentials = creds
        save(acc)
        return true
    }

    /** 持久化恢复流程更新过的账号元数据。 */
    fun saveForRecovery(account: WeReadAccount) {
        if (accounts[account.vid] === account) save(account)
    }

    /** 主动刷新成功后调用：更新时间戳、清零失败计数 */
    fun markRefreshed(vid: String) {
        val acc = accounts[vid] ?: return
        updateCredentials(
            vid,
            acc.credentials.copy(
                lastRefreshTime = System.currentTimeMillis(),
                refreshFailCount = 0,
            ),
        )
    }

    /** 刷新失败时调用：递增连续失败计数 */
    fun markRefreshFailed(vid: String): Int {
        val acc = accounts[vid] ?: return 0
        val count = acc.credentials.refreshFailCount + 1
        updateCredentials(vid, acc.credentials.copy(refreshFailCount = count))
        return count
    }

    /**
     * 从响应 set-cookie 头合并更新账号凭证（主动刷新 / session/init 都用到）
     *
     * 服务端刷新时可能只返回部分字段，因此"合并更新"而非整体替换。
     *
     * @return 是否发生变更（拿到至少一个有效字段）
     */
    fun updateFromSetCookie(vid: String, setCookies: List<String>, username: String? = null): Boolean {
        if (setCookies.isEmpty()) return false
        val acc = accounts[vid] ?: return false
        val creds = acc.credentials
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

        updateCredentials(
            vid,
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

    /** 删除账号 */
    fun delete(vid: String): Boolean {
        val removed = accounts.remove(vid) != null
        inUse.remove(vid)
        val file = File(accountDir(), "$vid.json")
        if (file.exists()) file.delete()
        if (removed) logger.info("微信读书账号已删除: vid=$vid")
        return removed
    }

    /** 持久化单个账号 */
    private fun save(account: WeReadAccount) {
        synchronized(saveLock) {
            val dir = accountDir()
            val file = File(dir, "${account.vid}.json")
            val temp = File.createTempFile(".${account.vid}.", ".json.tmp", dir)
            try {
                temp.writeText(gson.toJson(account))
                try {
                    Files.move(
                        temp.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(
                        temp.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } catch (e: Exception) {
                temp.delete()
                logger.error("微信读书账号保存失败: vid=${account.vid}, ${e.message}")
            }
        }
    }

    // ── 调度：取号 / 释放 ────────────────────────

    /**
     * 取号（least_used 调度）
     *
     * 过滤条件：未占用（inUse）+ ACTIVE + 未在冷却。
     * 排序：lastUsedTime 升序（最少使用优先）→ freqControlCount 升序（低频控优先）。
     *
     * @return 可用账号；无可用账号返回 null
     */
    fun acquire(): WeReadAccount? = synchronized(scheduleLock) {
        val now = System.currentTimeMillis()
        val chosen = accounts.values
            .filter { !inUse.getOrDefault(it.vid, false) && it.isAvailable(now) }
            .minWithOrNull(compareBy<WeReadAccount> { it.lastUsedTime }.thenBy { it.freqControlCount })
        if (chosen != null) {
            if (chosen.status == AccountStatus.COOLDOWN) {
                chosen.status = AccountStatus.ACTIVE
                chosen.cooldownUntil = 0L
                chosen.disabledReason = ""
                save(chosen)
                logger.info("微信读书账号冷却到期，已自动恢复: vid=${chosen.vid}")
            }
            inUse[chosen.vid] = true
        }
        chosen
    }

    /** 释放占用（请求结束无论成败都调用） */
    fun release(vid: String) {
        synchronized(scheduleLock) { inUse.remove(vid) }
    }

    // ── 状态标记 ────────────────────────────────

    /** 请求成功：累计次数 + 更新最后使用时间 */
    fun markSuccess(vid: String) {
        val acc = accounts[vid] ?: return
        acc.totalRequests++
        acc.lastUsedTime = System.currentTimeMillis()
        save(acc)
    }

    /** 疑似频控：累计频控次数 + 进入冷却（到期自动恢复 ACTIVE） */
    fun markFreqControl(vid: String, cooldownSec: Long = AppConfig.wereadFreqControlCooldown) {
        val acc = accounts[vid] ?: return
        acc.freqControlCount++
        acc.lastUsedTime = System.currentTimeMillis()
        acc.cooldownUntil = System.currentTimeMillis() + cooldownSec * 1000
        acc.status = AccountStatus.COOLDOWN
        acc.disabledReason = "频控冷却 ${cooldownSec}s"
        save(acc)
        logger.warn("微信读书账号频控冷却: vid=$vid, 累计 ${acc.freqControlCount} 次, 冷却 ${cooldownSec}s")
    }

    /** 凭证失效：标记 EXPIRED（需重新扫码） */
    fun markExpired(vid: String, reason: String = "凭证失效") {
        val acc = accounts[vid] ?: return
        acc.status = AccountStatus.EXPIRED
        acc.disabledReason = reason
        save(acc)
        logger.warn("微信读书账号标记失效: vid=$vid, reason=$reason")
    }

    /** 手动启用 / 禁用 */
    fun setEnabled(vid: String, enabled: Boolean): Boolean {
        val acc = accounts[vid] ?: return false
        acc.status = if (enabled) AccountStatus.ACTIVE else AccountStatus.DISABLED
        acc.disabledReason = if (enabled) "" else "手动禁用"
        acc.cooldownUntil = 0L
        save(acc)
        logger.info("微信读书账号已${if (enabled) "启用" else "禁用"}: vid=$vid")
        return true
    }
}
