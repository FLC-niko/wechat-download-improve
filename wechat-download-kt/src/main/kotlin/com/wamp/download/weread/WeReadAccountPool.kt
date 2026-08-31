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
    private val persistenceErrors = ConcurrentHashMap<String, String>()
    private val probeSuccessAt = ConcurrentHashMap<String, Long>()
    private val registryLock = Any()
    private val saveLock = Any()
    private val scheduleLock = Any()

    private fun accountDir(): File = File(AppConfig.wereadAccountDir).apply { mkdirs() }

    init {
        loadAll()
    }

    // ── 加载 / 迁移 ──────────────────────────────

    fun loadAll() {
        accounts.clear()
        inUse.clear()
        persistenceErrors.clear()
        probeSuccessAt.clear()
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
                check(save(acc)) { "failed to persist migrated account ${creds.wrVid}" }
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

    fun persistenceError(vid: String): String? = persistenceErrors[vid]

    fun size(): Int = accounts.size

    /** 是否存在可用账号（只读探测，不占用） */
    fun hasAvailableAccount(now: Long = System.currentTimeMillis()): Boolean = synchronized(scheduleLock) {
        accounts.values.any { !inUse.getOrDefault(it.vid, false) && it.isAvailable(now) }
    }

    /** 是否存在登录有效的账号，不受健康探测新鲜度影响。 */
    fun hasLoggedInAccount(now: Long = System.currentTimeMillis()): Boolean =
        accounts.values.any { account -> account.hasWebCredentials() && account.isAvailable(now) }

    /** 是否存在近期真实探测成功、可用于声明服务 ready 的账号。 */
    fun hasOperationalAccount(now: Long = System.currentTimeMillis()): Boolean =
        accounts.values.any { account ->
            val lastProbe = probeSuccessAt[account.vid]
            account.hasWebCredentials() &&
                account.isAvailable(now) &&
                lastProbe != null &&
                now >= lastProbe &&
                now - lastProbe <= AppConfig.wereadWebProbeFreshness * 1000L
        }

    /** 兼容层：主账号（最早登录），供旧单账号逻辑使用 */
    fun primaryAccount(): WeReadAccount? =
        accounts.values.minByOrNull { it.credentials.loginTime }

    fun primaryCredentials(): WeReadWebCredentials =
        primaryAccount()?.credentials ?: WeReadWebCredentials()

    // ── 写操作 ──────────────────────────────────

    /** 新增账号（vid 已存在则返回 false） */
    fun addAccount(account: WeReadAccount): Boolean {
        if (account.vid.isBlank()) return false
        synchronized(registryLock) {
            if (accounts.containsKey(account.vid)) return false
            synchronized(account) {
                if (!save(account)) return false
                accounts[account.vid] = account
            }
        }
        logger.info("微信读书账号已添加: vid=${account.vid}, username=${account.credentials.username}")
        return true
    }

    /** 新增或更新账号凭证（登录成功后调用） */
    fun upsertCredentials(creds: WeReadWebCredentials): Boolean {
        val existing = accounts[creds.wrVid]
        return if (existing != null) {
            mutate(existing.vid) { account ->
                account.credentials = creds
                if (account.status == AccountStatus.EXPIRED || account.status == AccountStatus.COOLDOWN) {
                    account.status = AccountStatus.ACTIVE
                    account.cooldownUntil = 0L
                    account.disabledReason = ""
                }
            }
        } else {
            addAccount(WeReadAccount(vid = creds.wrVid, credentials = creds))
        }
    }

    /** 更新指定账号的凭证 */
    fun updateCredentials(vid: String, creds: WeReadWebCredentials): Boolean {
        return mutate(vid) { it.credentials = creds }
    }

    fun completeRecovery(
        vid: String,
        credentials: WeReadWebCredentials,
        boundSerial: String,
        boundUserId: Int,
    ): Boolean = mutate(vid) { account ->
        account.credentials = credentials
        account.status = AccountStatus.ACTIVE
        account.disabledReason = ""
        account.cooldownUntil = 0L
        account.boundSerial = boundSerial
        account.boundUserId = boundUserId
    }

    fun bindRecoveryDevice(vid: String, serial: String, userId: Int, strategy: String): Boolean =
        mutate(vid) { account ->
            account.boundSerial = serial
            account.boundUserId = userId
            account.bindingStrategy = strategy
        }

    /** 主动刷新成功后调用：更新时间戳、清零失败计数 */
    fun markRefreshed(vid: String) {
        mutate(vid) { account ->
            account.credentials = account.credentials.copy(
                lastRefreshTime = System.currentTimeMillis(),
                refreshFailCount = 0,
            )
        }
    }

    /** A successful authenticated probe clears stale failures without pretending a token was renewed. */
    fun markProbeSuccess(vid: String) {
        if (accounts.containsKey(vid)) {
            probeSuccessAt[vid] = System.currentTimeMillis()
        }
        mutateIf(vid) { account ->
            if (account.credentials.refreshFailCount == 0) return@mutateIf false
            account.credentials = account.credentials.copy(refreshFailCount = 0)
            true
        }
    }

    /** 刷新失败时调用：递增连续失败计数 */
    fun markRefreshFailed(vid: String): Int {
        var count = 0
        val updated = mutate(vid) { account ->
            count = account.credentials.refreshFailCount + 1
            account.credentials = account.credentials.copy(refreshFailCount = count)
        }
        return if (updated) count else 0
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

        val parsed = setCookies.mapNotNull { header ->
            val pair = header.substringBefore(';').trim()
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val name = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1).trim()
            if (value.isEmpty()) return@mapNotNull null
            name to value
        }.toMap()
        if (parsed.keys.none { it == "wr_skey" || it == "wr_vid" || it == "wr_rt" }) return false

        return mutateIf(vid) { account ->
            val creds = account.credentials
            if (creds.wrVid.isBlank()) return@mutateIf false
            account.credentials = creds.copy(
                wrSkey = parsed["wr_skey"] ?: creds.wrSkey,
                wrVid = parsed["wr_vid"] ?: creds.wrVid,
                wrRt = parsed["wr_rt"] ?: creds.wrRt,
                username = username ?: creds.username,
                lastRefreshTime = System.currentTimeMillis(),
                refreshFailCount = 0,
            )
            true
        }
    }

    /** 删除账号 */
    fun delete(vid: String): Boolean {
        var removed = false
        synchronized(registryLock) {
            val account = accounts[vid]
            if (account != null) {
                synchronized(account) {
                    val file = File(accountDir(), "$vid.json")
                    val persisted = try {
                        Files.deleteIfExists(file.toPath())
                        true
                    } catch (error: Exception) {
                        persistenceErrors[vid] = error.message ?: error.javaClass.simpleName
                        logger.error("微信读书账号删除失败: vid=$vid, ${error.message}")
                        false
                    }
                    if (persisted) {
                        removed = accounts.remove(vid, account)
                        if (removed) {
                            inUse.remove(vid)
                            probeSuccessAt.remove(vid)
                            persistenceErrors.remove(vid)
                        }
                    }
                }
            }
        }
        if (removed) logger.info("微信读书账号已删除: vid=$vid")
        return removed
    }

    /** 持久化单个账号 */
    private fun save(account: WeReadAccount): Boolean {
        synchronized(saveLock) {
            val dir = accountDir()
            val file = File(dir, "${account.vid}.json")
            var temp: File? = null
            try {
                temp = File.createTempFile(".${account.vid}.", ".json.tmp", dir)
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
                persistenceErrors.remove(account.vid)
                return true
            } catch (e: Exception) {
                temp?.delete()
                persistenceErrors[account.vid] = e.message ?: e.javaClass.simpleName
                logger.error("微信读书账号保存失败: vid=${account.vid}, ${e.message}")
                return false
            }
        }
    }

    private fun mutate(vid: String, change: (WeReadAccount) -> Unit): Boolean {
        return mutateIf(vid) {
            change(it)
            true
        }
    }

    private fun mutateIf(vid: String, change: (WeReadAccount) -> Boolean): Boolean {
        val account = accounts[vid] ?: return false
        synchronized(account) {
            val before = account.copy()
            if (!change(account)) return false
            if (save(account)) return true
            restore(account, before)
            return false
        }
    }

    private fun restore(target: WeReadAccount, source: WeReadAccount) {
        target.credentials = source.credentials
        target.boundSerial = source.boundSerial
        target.boundUserId = source.boundUserId
        target.bindingStrategy = source.bindingStrategy
        target.status = source.status
        target.totalRequests = source.totalRequests
        target.freqControlCount = source.freqControlCount
        target.lastUsedTime = source.lastUsedTime
        target.cooldownUntil = source.cooldownUntil
        target.disabledReason = source.disabledReason
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
                val restored = mutate(chosen.vid) {
                    it.status = AccountStatus.ACTIVE
                    it.cooldownUntil = 0L
                    it.disabledReason = ""
                }
                if (!restored) return@synchronized null
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
        mutate(vid) {
            it.totalRequests++
            it.lastUsedTime = System.currentTimeMillis()
        }
    }

    /** 疑似频控：累计频控次数 + 进入冷却（到期自动恢复 ACTIVE） */
    fun markFreqControl(vid: String, cooldownSec: Long = AppConfig.wereadFreqControlCooldown) {
        val updated = mutate(vid) { account ->
            account.freqControlCount++
            account.lastUsedTime = System.currentTimeMillis()
            account.cooldownUntil = System.currentTimeMillis() + cooldownSec * 1000
            account.status = AccountStatus.COOLDOWN
            account.disabledReason = "频控冷却 ${cooldownSec}s"
        }
        if (updated) {
            logger.warn("微信读书账号频控冷却: vid=$vid, 累计 ${accounts[vid]?.freqControlCount} 次, 冷却 ${cooldownSec}s")
        }
    }

    /** 凭证失效：标记 EXPIRED（需重新扫码） */
    fun markExpired(vid: String, reason: String = "凭证失效"): Boolean {
        probeSuccessAt.remove(vid)
        val updated = mutate(vid) {
            it.status = AccountStatus.EXPIRED
            it.disabledReason = reason
        }
        if (updated) logger.warn("微信读书账号标记失效: vid=$vid, reason=$reason")
        return updated
    }

    /** Decisive 401/-2012 transition: expire and increment recovery attempts in one durable write. */
    fun markAuthExpired(vid: String, reason: String = "凭证失效"): Int {
        probeSuccessAt.remove(vid)
        var count = 0
        val updated = mutate(vid) { account ->
            // Health checks can observe the same expired account repeatedly. Count the
            // transition once; otherwise a long outage eventually overflows the counter and
            // makes the metric look like repeated refresh attempts.
            count = if (account.status == AccountStatus.EXPIRED) {
                account.credentials.refreshFailCount
            } else {
                account.credentials.refreshFailCount + 1
            }
            if (account.status != AccountStatus.EXPIRED) {
                account.credentials = account.credentials.copy(refreshFailCount = count)
            }
            account.status = AccountStatus.EXPIRED
            account.disabledReason = reason
        }
        if (updated) logger.warn("微信读书账号鉴权失效: vid=$vid, count=$count, reason=$reason")
        return if (updated) count else 0
    }

    /** 手动启用 / 禁用 */
    fun setEnabled(vid: String, enabled: Boolean): Boolean {
        if (!enabled) probeSuccessAt.remove(vid)
        val updated = mutate(vid) { account ->
            account.status = if (enabled) AccountStatus.ACTIVE else AccountStatus.DISABLED
            account.disabledReason = if (enabled) "" else "手动禁用"
            account.cooldownUntil = 0L
        }
        if (updated) logger.info("微信读书账号已${if (enabled) "启用" else "禁用"}: vid=$vid")
        return updated
    }
}
