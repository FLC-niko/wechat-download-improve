package com.wamp.download.auth

import com.wamp.download.notify.WebhookNotifier
import com.wamp.download.config.AppConfig
import com.wamp.download.task.ChannelStateManager
import com.wamp.download.wx.WxApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

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
) {
    companion object {
        private val logger = LoggerFactory.getLogger(LoginChecker::class.java)
    }

    @Volatile
    var isRecovering = false
        private set

    private val recovering = AtomicBoolean(false)
    private var recoverySignal = CompletableDeferred<Unit>()

    // token 有效性缓存
    @Volatile
    private var lastCheckTime: Long = 0L
    @Volatile
    private var lastCheckStatus: WxApiClient.TokenStatus = WxApiClient.TokenStatus.EXPIRED

    fun start() {
        scope.launch {
            logger.info("登录状态检查服务已启动（检查间隔: ${checkIntervalMs / 1000}秒）")
            while (isActive) {
                if (!isRecovering) {
                    checkLoginStatus()
                }
                delay(checkIntervalMs)
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
        if (!AppConfig.mainEnabled) return WxApiClient.TokenStatus.UNAVAILABLE

        val now = System.currentTimeMillis()
        if (!forceRefresh && now - lastCheckTime < tokenCacheMs) {
            return lastCheckStatus
        }

        lastCheckStatus = wxClient.checkTokenStatus()
        lastCheckTime = now
        return lastCheckStatus
    }

    fun isTokenValid(forceRefresh: Boolean = false): Boolean =
        checkTokenStatus(forceRefresh) == WxApiClient.TokenStatus.VALID

    /**
     * 等待登录恢复完成（如果正在恢复）
     */
    suspend fun waitForRecoveryComplete() {
        if (!isRecovering) return
        logger.info("【登录检查】检测到正在恢复登录，等待恢复完成...")
        recoverySignal.await()
        logger.info("【登录检查】恢复完成，继续处理任务")
    }

    /**
     * 强制触发恢复流程并等待完成
     */
    suspend fun startRecoveryAndWait() {
        if (!tryBeginRecovery()) {
            waitForRecoveryComplete()
            return
        }
        logger.warn("【登录检查】触发恢复流程并等待完成...")
        try {
            performRecovery()
        } finally {
            finishRecovery()
        }
    }

    private suspend fun checkLoginStatus() {
        val now = System.currentTimeMillis()
        // 用缓存判断，避免每次都发请求
        if (now - lastCheckTime < tokenCacheMs && lastCheckStatus == WxApiClient.TokenStatus.VALID) {
            return  // 缓存有效，跳过
        }

        val status = try {
            wxClient.checkTokenStatus()
        } catch (e: Exception) {
            logger.warn("【登录检查】探测失败: ${e.message}")
            return
        }

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
                logger.warn("【登录检查】微信登录失效，开始恢复流程...")
                if (!tryBeginRecovery()) {
                    logger.info("【登录检查】已有恢复流程在进行中，跳过重复触发")
                    return
                }
                try {
                    performRecovery()
                } finally {
                    finishRecovery()
                }
            }
        }
    }

    private suspend fun performRecovery() {
        // 通知登录过期
        WebhookNotifier.notify("login_expired", mapOf(
            "message" to "微信登录已失效，正在发起扫码登录..."
        ))

        val maxRetries = 3
        for (attempt in 1..maxRetries) {
            logger.info("【登录检查】发起扫码登录（第 $attempt 次）...")
            try {
                val success = loginService.startLogin()
                if (success) {
                    // 登录成功后强制刷新缓存
                    lastCheckTime = 0L
                    lastCheckStatus = wxClient.checkTokenStatus()
                    logger.info("【登录检查】扫码登录成功，登录状态已恢复")
                    WebhookNotifier.notify("login_success", mapOf(
                        "message" to "微信扫码登录已恢复"
                    ))
                    return
                }
            } catch (e: Exception) {
                logger.error("【登录检查】登录流程异常: ${e.message}", e)
            }

            if (attempt < maxRetries) {
                logger.warn("【登录检查】登录失败，30 秒后重试...")
                delay(30_000)
            }
        }

        logger.error("【登录检查】连续 $maxRetries 次登录失败，等待下次定时检查")
        WebhookNotifier.notify("login_failed", mapOf(
            "message" to "连续 $maxRetries 次扫码登录失败，将在下次检查时重试"
        ))
    }

    private fun tryBeginRecovery(): Boolean = synchronized(this) {
        if (isRecovering) {
            false
        } else {
            isRecovering = true
            recoverySignal = CompletableDeferred()
            true
        }
    }

    private fun finishRecovery() {
        synchronized(this) {
            isRecovering = false
        }
        recoverySignal.complete(Unit)
    }

    fun stop() {
        scope.cancel()
        recoverySignal.complete(Unit)
    }
}
