package com.wamp.download.task

import com.wamp.download.config.AppConfig
import com.wamp.download.store.Subscription
import com.wamp.download.store.SubscriptionStore
import com.wamp.download.weread.eink.WeReadEinkRuntime
import com.wamp.download.weread.eink.WeReadEinkBackgroundYieldException
import com.wamp.download.weread.eink.WeReadEinkSyncResult
import com.wamp.download.weread.eink.WeReadEinkSyncService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.random.Random

object IdleHistoryPoller {
    private val logger = LoggerFactory.getLogger(IdleHistoryPoller::class.java)
    private var job: Job? = null
    
    fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        if (job?.isActive == true) return
        job = scope.launch {
            logger.info("闲时历史回源轮询器 (IdleHistoryPoller) 启动")
            while (isActive) {
                try {
                    pollOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("IdleHistoryPoller 未捕获异常: ${e.message}", e)
                }
                
                // Sleep between rounds (5-10 minutes)
                val sleepMs = Random.nextLong(300000L, 600000L)
                delay(sleepMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    internal fun dueSubscriptions(
        subscriptions: List<Subscription>,
        service: WeReadEinkSyncService,
        now: Long = System.currentTimeMillis() / 1000L,
    ): List<Subscription> = subscriptions.filter { sub ->
        now - sub.lastPoll > 43200 && service.canSync(sub) &&
            service.isSyncDue(sub, AppConfig.wereadEinkSyncInterval, now)
    }.sortedBy { it.lastPoll }

    private suspend fun pollOnce() {
        if (!AppConfig.wereadEinkEnabled) return
        
        // Real-time RabbitMQ work always wins over cache warming.
        if (!RealtimeTaskActivity.isBackgroundWorkAllowed()) {
            logger.debug("近期有活动任务，跳过本轮历史回源")
            return
        }

        // Fetch subscriptions that are due for polling (e.g. lastPoll > 12 hours ago)
        val now = System.currentTimeMillis() / 1000L
        val (subscriptions, _) = SubscriptionStore.list(limit = 20, offset = 0)
        
        val einkService = WeReadEinkRuntime.backgroundSyncService() ?: return
        val dueSubscriptions = dueSubscriptions(subscriptions, einkService, now)
        if (dueSubscriptions.isEmpty()) return

        logger.info("闲时轮询开始，挑选 ${dueSubscriptions.size} 个公众号进行回源")
        
        for (sub in dueSubscriptions) {
            if (job?.isActive != true) break
            
            // Check again before each subscription in case a real-time task arrived
            if (!RealtimeTaskActivity.isBackgroundWorkAllowed() ||
                !WeReadEinkRuntime.canContinueBackgroundSync(einkService)) {
                logger.info("后台调度条件已变化，中断当前轮询轮次")
                break
            }
            
            if (!einkService.canSync(sub)) continue
            // E-Ink sync does not advance the generic lastPoll field. Use its own checkpoint
            // so the idle poller does not rescan subscriptions just handled by the scheduler.
            if (!einkService.isSyncDue(sub, AppConfig.wereadEinkSyncInterval)) continue

            try {
                val result: WeReadEinkSyncResult = einkService.sync(
                    subscription = sub,
                    shouldContinue = {
                        RealtimeTaskActivity.isBackgroundWorkAllowed() &&
                            WeReadEinkRuntime.canContinueBackgroundSync(einkService)
                    },
                )
                
                WeReadEinkRuntime.recordTaskRequestSuccess(einkService)
                
                logger.info("闲时回源成功: fakeid=${sub.fakeid.take(8)}..., 获取 ${result.fetchedArticles} 篇")
                
                // Sleep between accounts to avoid hitting API limits
                delay(Random.nextLong(10000L, 30000L))
            } catch (_: WeReadEinkBackgroundYieldException) {
                logger.info("后台调度条件已变化，中断当前历史回源并让出 E-Ink 通道")
                break
            } catch (e: Exception) {
                logger.warn("闲时回源异常: fakeid=${sub.fakeid.take(8)}..., ${e.message}")
                WeReadEinkRuntime.recordTaskRequestFailure(einkService, e)
                // If it was a human verification error, break the entire poll loop
                if (!WeReadEinkRuntime.canAttemptTaskSync()) {
                    logger.warn("E-Ink 进入熔断，暂停本轮闲时回源")
                    break
                }
                delay(10000L)
            }
        }
    }
}
