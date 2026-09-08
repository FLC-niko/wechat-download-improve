package com.wamp.download.task

import com.wamp.download.config.AppConfig
import com.wamp.download.store.SubscriptionStore
import com.wamp.download.weread.eink.WeReadEinkRuntime
import com.wamp.download.weread.eink.WeReadEinkSyncResult
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
    
    // We only poll if the application indicates it's idle (e.g. no RabbitMQ tasks for the last X seconds)
    @Volatile
    private var lastActiveTaskTime = System.currentTimeMillis()

    fun recordTaskActivity() {
        lastActiveTaskTime = System.currentTimeMillis()
    }

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

    private suspend fun pollOnce() {
        if (!AppConfig.wereadEinkEnabled) return
        
        // Skip if active tasks were processed recently (e.g., in the last 2 minutes)
        if (System.currentTimeMillis() - lastActiveTaskTime < 120_000L) {
            logger.debug("近期有活动任务，跳过本轮历史回源")
            return
        }

        // Fetch subscriptions that are due for polling (e.g. lastPoll > 12 hours ago)
        val now = System.currentTimeMillis() / 1000L
        val (subscriptions, _) = SubscriptionStore.list(limit = 20, offset = 0)
        
        val dueSubscriptions = subscriptions.filter { sub ->
            now - sub.lastPoll > 43200 // 12 hours
        }.sortedBy { it.lastPoll }

        if (dueSubscriptions.isEmpty()) return
        
        val einkService = WeReadEinkRuntime.syncService()
        if (einkService == null || !WeReadEinkRuntime.canAttemptTaskSync()) {
            return
        }

        logger.info("闲时轮询开始，挑选 ${dueSubscriptions.size} 个公众号进行回源")
        
        for (sub in dueSubscriptions) {
            if (job?.isActive != true) break
            
            // Check again before each subscription in case a real-time task arrived
            if (System.currentTimeMillis() - lastActiveTaskTime < 10_000L) {
                logger.info("检测到实时任务压入，主动让出队列并中断当前轮询轮次")
                break
            }
            
            if (!einkService.canSync(sub)) continue

            try {
                // Here we fetch up to a certain max pages to enrich cache. 
                // Using sync() without strict time limits allows it to fetch pages.
                // We use syncIfNeeded to fetch recent gaps or sync() if completely un-covered.
                // It will naturally fetch offset=0, offset=20 etc., up to maxPages.
                val result: WeReadEinkSyncResult = einkService.sync(sub)
                
                WeReadEinkRuntime.recordTaskRequestSuccess()
                
                logger.info("闲时回源成功: fakeid=${sub.fakeid.take(8)}..., 获取 ${result.fetchedArticles} 篇")
                
                // Sleep between accounts to avoid hitting API limits
                delay(Random.nextLong(10000L, 30000L))
            } catch (e: Exception) {
                logger.warn("闲时回源异常: fakeid=${sub.fakeid.take(8)}..., ${e.message}")
                WeReadEinkRuntime.recordTaskRequestFailure(e)
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
