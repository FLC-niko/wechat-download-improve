package com.wamp.download.auth

import com.wamp.download.notify.WebhookNotifier
import com.wamp.download.config.AppConfig
import com.wamp.download.task.ChannelStateManager
import com.wamp.download.wx.WxApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.math.min

/**
 * 微信登录状态检查与恢复
 *
 * 仿照 we-mp-rss-kotlin 的 LoginChecker，但做了以下改进：
 * - token 有效性探测带 5 分钟缓存，避免频繁请求
 * - 恢复期间用 CompletableDeferred 阻塞任务处理，恢复完成后自动放行
 * - 登录流程委托给 LoginService（服务端主动扫码）
 *
 * 工作流程：
 * 1. 每 10 分钟检查一次 token 是否有效（用 searchbiz 探测）
 * 2. 如果失效，标记 isRecovering=true，触发 LoginService 重新登录
 * 3. 登录成功后标记 isRecovering=false，唤醒等待的任务
 * 4. 任务处理前调用 waitForRecoveryComplete()，确保不会在登录失效时处理任务
 */
class LoginChecker(
    private val wxClient: WxApiClient = WxApiClient(),
    private val loginService: LoginService = LoginService(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val checkIntervalMs: Long = 10 * 60 * 1000L,  // 10 分钟
    private val tokenCacheMs: Long = 5 * 60 * 1000L,      // token 探测缓存 5 分钟
    private val loginRetryDelayMs: Long = 30_000L,
    private val recoveryBackoffInitialMs: Long = 5 * 60_000L,
    private val recoveryBackoffMaxMs: Long = 60 * 60_000L,
    private val tokenStatusProvider: () -> WxApiClient.TokenStatus = { wxClient.checkTokenStatus() },
    private val loginAttempt: suspend () -> Boolean = { loginService.startLogin(requireNotification = true) },
    private val mainEnabledProvider: () -> Boolean = { AppConfig.mainEnabled },
    private val autoRecoveryEnabledProvider: () -> Boolean = { AppConfig.authAutoRecoveryEnabled },
) {
    companion object {
        private val logger = LoggerFactory.getLogger(LoginChecker::class.java)
    }

    @Volatile
    var isRecovering = false
        private set

    private var recoverySignal = CompletableDeferred<Boolean>()
    private var watcherJob: Job? = null

    @Volatile
    private var recoveryFailureCount: Int = 0

    @Volatile
    private var nextRecoveryAttemptAt: Long = 0L

    private data class RecoveryHandle(
        val owner: Boolean,
        val signal: CompletableDeferred<Boolean>,
    )

    // token 有效性缓存
    @Volatile
    private var lastCheckTime: Long = 0L
    @Volatile
    private var lastCheckStatus: WxApiClient.TokenStatus = WxApiClient.TokenStatus.EXPIRED

    fun start() {
        synchronized(this) {
            if (watcherJob?.isActive == true) return
            watcherJob = scope.launch {
                logger.info("登录状态检查服务已启动（检查间隔: ${checkIntervalMs / 1000}秒）")
                while (isActive) {
                    if (!isRecovering) {
                        checkLoginStatus()
                    }
                    delay(checkIntervalMs)
                }
            }
        }
    }

    /**
     * 快速检查 token 状态（带缓存）
     *
     * @param forceRefresh 强制重新探测（不使用缓存）
     * @return token 状态
     */
    fun checkTokenStatus(forceRefresh: Boolean = false): WxApiClient.TokenStatus {
        if (isRecovering) return WxApiClient.TokenStatus.EXPIRED
        if (ChannelStateManager.isMainChannelInvalidated()) return WxApiClient.TokenStatus.INVALIDATED
        if (!mainEnabledProvider()) return WxApiClient.TokenStatus.UNAVAILABLE

        val now = System.currentTimeMillis()
        if (!forceRefresh && now - lastCheckTime < tokenCacheMs) {
            return lastCheckStatus
        }

        lastCheckStatus = probeTokenStatus()
        lastCheckTime = now
        return lastCheckStatus
    }

    fun isTokenValid(forceRefresh: Boolean = false): Boolean =
        checkTokenStatus(forceRefresh) == WxApiClient.TokenStatus.VALID

    /**
     * 等待登录恢复完成（如果正在恢复）
     */
    suspend fun waitForRecoveryComplete(): Boolean {
        val signal = synchronized(this) {
            if (isRecovering) recoverySignal else null
        } ?: return true
        logger.info("【登录检查】检测到正在恢复登录，等待恢复完成...")
        val success = signal.await()
        logger.info("【登录检查】恢复流程结束: success=$success")
        return success
    }

    /**
     * 强制触发恢复流程并等待完成
     */
    suspend fun startRecoveryAndWait(): Boolean {
        if (!autoRecoveryEnabledProvider()) {
            logger.warn("【登录检查】自动扫码已关闭，跳过恢复流程")
            return false
        }
        if (System.currentTimeMillis() < nextRecoveryAttemptAt) {
            logger.warn("【登录检查】恢复处于退避期，nextRetryAt=$nextRecoveryAttemptAt")
            return false
        }
        val recovery = beginRecoveryOrJoin()
        if (!recovery.owner) {
            logger.info("【登录检查】已有恢复流程在进行中，等待当前轮次结束")
            return recovery.signal.await()
        }
        logger.warn("【登录检查】触发恢复流程并等待完成...")
        var success = false
        try {
            success = performRecovery()
            return success
        } finally {
            finishRecovery(recovery.signal, success)
        }
    }

    /** Runs one scheduled check immediately; useful for deterministic health checks and tests. */
    internal suspend fun checkNow() {
        checkLoginStatus()
    }

    private suspend fun checkLoginStatus() {
        // A disabled main channel must not make network calls or emit expired-login alerts.
        // This also keeps a later explicit re-enable from inheriting a stale UNAVAILABLE cache.
        if (!mainEnabledProvider()) {
            lastCheckTime = 0L
            lastCheckStatus = WxApiClient.TokenStatus.UNAVAILABLE
            return
        }
        val now = System.currentTimeMillis()
        // 用缓存判断，避免每次都发请求
        if (now - lastCheckTime < tokenCacheMs && lastCheckStatus == WxApiClient.TokenStatus.VALID) {
            return  // 缓存有效，跳过
        }

        val status = probeTokenStatus()

        lastCheckTime = now
        lastCheckStatus = status

        when (status) {
            WxApiClient.TokenStatus.VALID -> logger.debug("【登录检查】微信登录状态有效")
            WxApiClient.TokenStatus.RATE_LIMITED -> {
                logger.warn("【登录检查】token 探测触发未知临时限流，标记主通道降级并跳过登录恢复")
                ChannelStateManager.markMainChannelFreqControl(AppConfig.mainDegradedDuration * 1000)
            }
            WxApiClient.TokenStatus.INVALIDATED -> {
                logger.error("【登录检查】主微信接口返回 200013，通道已失效，不再自动重试")
                ChannelStateManager.markMainChannelInvalid("ret=200013")
            }
            WxApiClient.TokenStatus.UNAVAILABLE ->
                logger.warn("【登录检查】token 探测暂不可用，跳过登录恢复")
            WxApiClient.TokenStatus.EXPIRED -> {
                if (!autoRecoveryEnabledProvider()) {
                    logger.warn("【登录检查】微信登录已失效，自动扫码已关闭，请人工恢复")
                    WebhookNotifier.notify("login_expired", mapOf(
                        "message" to "微信登录已失效，自动扫码已关闭，请调用登录接口人工恢复",
                    ))
                    return
                }
                if (System.currentTimeMillis() < nextRecoveryAttemptAt) {
                    logger.warn("【登录检查】微信登录仍失效，恢复退避至 $nextRecoveryAttemptAt")
                    return
                }
                logger.warn("【登录检查】微信登录失效，开始恢复流程...")
                val recovery = beginRecoveryOrJoin()
                if (!recovery.owner) {
                    logger.info("【登录检查】已有恢复流程在进行中，跳过重复触发")
                    return
                }
                var success = false
                try {
                    success = performRecovery()
                } finally {
                    finishRecovery(recovery.signal, success)
                }
            }
        }
    }

    private suspend fun performRecovery(): Boolean {
        // 通知登录过期
        WebhookNotifier.notify("login_expired", mapOf(
            "message" to "微信登录已失效，正在发起扫码登录..."
        ))

        val maxRetries = 3
        for (attempt in 1..maxRetries) {
            logger.info("【登录检查】发起扫码登录（第 $attempt 次）...")
            try {
                val loginCompleted = loginAttempt()
                if (loginCompleted) {
                    // 登录成功后强制刷新缓存
                    lastCheckTime = 0L
                    lastCheckStatus = probeTokenStatus()
                    when (lastCheckStatus) {
                        WxApiClient.TokenStatus.VALID -> {
                            logger.info("【登录检查】扫码登录成功，登录状态已恢复")
                            WebhookNotifier.notify("login_success", mapOf(
                                "message" to "微信扫码登录已恢复"
                            ))
                            return true
                        }
                        WxApiClient.TokenStatus.EXPIRED -> {
                            logger.warn("【登录检查】扫码流程返回成功，但凭证复验仍显示失效")
                        }
                        WxApiClient.TokenStatus.INVALIDATED -> {
                            logger.error("【登录检查】扫码流程返回成功，但主微信接口仍返回 200013，不再重复扫码")
                            ChannelStateManager.markMainChannelInvalid("ret=200013")
                            return false
                        }
                        WxApiClient.TokenStatus.RATE_LIMITED -> {
                            logger.warn("【登录检查】扫码流程已完成，但凭证复验触发限流，不重复扫码")
                            ChannelStateManager.markMainChannelFreqControl(AppConfig.mainDegradedDuration * 1000)
                            return false
                        }
                        WxApiClient.TokenStatus.UNAVAILABLE -> {
                            logger.warn("【登录检查】扫码流程已完成，但凭证复验暂不可用，不重复扫码")
                            return false
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("【登录检查】登录流程异常: ${e.message}", e)
            }

            if (attempt < maxRetries) {
                logger.warn("【登录检查】登录失败，30 秒后重试...")
                delay(loginRetryDelayMs)
            }
        }

        logger.error("【登录检查】连续 $maxRetries 次登录失败，等待下次定时检查")
        WebhookNotifier.notify("login_failed", mapOf(
            "message" to "连续 $maxRetries 次扫码登录失败，将在下次检查时重试"
        ))
        return false
    }

    private fun probeTokenStatus(): WxApiClient.TokenStatus = try {
        tokenStatusProvider()
    } catch (error: Exception) {
        logger.warn("【登录检查】探测失败: ${error.message}")
        WxApiClient.TokenStatus.UNAVAILABLE
    }

    private fun beginRecoveryOrJoin(): RecoveryHandle = synchronized(this) {
        if (isRecovering) {
            RecoveryHandle(owner = false, signal = recoverySignal)
        } else {
            isRecovering = true
            recoverySignal = CompletableDeferred()
            RecoveryHandle(owner = true, signal = recoverySignal)
        }
    }

    private fun finishRecovery(signal: CompletableDeferred<Boolean>, success: Boolean) {
        val shouldComplete = synchronized(this) {
            if (recoverySignal !== signal) return@synchronized false
            isRecovering = false
            if (success) {
                recoveryFailureCount = 0
                nextRecoveryAttemptAt = 0L
            } else {
                recoveryFailureCount++
                val multiplier = 1L shl (recoveryFailureCount - 1).coerceIn(0, 10)
                val delayMs = min(
                    runCatching { Math.multiplyExact(recoveryBackoffInitialMs, multiplier) }
                        .getOrDefault(recoveryBackoffMaxMs),
                    recoveryBackoffMaxMs,
                )
                nextRecoveryAttemptAt = System.currentTimeMillis() + delayMs
            }
            true
        }
        if (shouldComplete) signal.complete(success)
    }

    fun stop() {
        watcherJob?.cancel()
        watcherJob = null
        val signal = synchronized(this) {
            isRecovering = false
            val activeSignal = recoverySignal
            recoverySignal = CompletableDeferred()
            activeSignal
        }
        signal.complete(false)
    }

    fun recoveryStatus(): Map<String, Any?> = mapOf(
        "failure_count" to recoveryFailureCount,
        "next_retry_at" to nextRecoveryAttemptAt.takeIf { it > 0L },
    )
}
