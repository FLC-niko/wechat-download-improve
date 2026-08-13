package com.wamp.download.poller

import com.wamp.download.article.ArticleFetcher
import com.wamp.download.auth.AuthManager
import com.wamp.download.config.AppConfig
import com.wamp.download.store.SubscriptionStore
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

/**
 * RSS 后台轮询器 — 对齐 Python 版 rss_poller.py
 *
 * 定时通过微信 appmsgpublish 接口拉取订阅号的最新文章元数据，
 * 保存到 SQLite。仅获取元数据（标题/链接/摘要），不抓正文，零风控风险。
 */
object RssPoller {
    private val logger = LoggerFactory.getLogger(RssPoller::class.java)
    private val fetcher = ArticleFetcher()

    @Volatile
    private var running = false

    suspend fun start() {
        if (running) return
        running = true
        logger.info("RSS 轮询器已启动（间隔=${AppConfig.rssPollInterval}秒）")

        while (running) {
            try {
                pollAll()
            } catch (e: Exception) {
                logger.error("轮询周期异常: ${e.message}", e)
            }
            delay(AppConfig.rssPollInterval * 1000)
        }
    }

    fun stop() {
        running = false
        logger.info("RSS 轮询器已停止")
    }

    /**
     * 轮询所有订阅号
     */
    private suspend fun pollAll() {
        val fakeids = SubscriptionStore.allFakeids()
        if (fakeids.isEmpty()) return

        if (!AuthManager.isLoggedIn()) {
            logger.warn("轮询跳过: 微信未登录")
            return
        }

        logger.info("开始轮询 ${fakeids.size} 个订阅号")

        for (fakeid in fakeids) {
            try {
                fetcher.fetchAndSave(fakeid, begin = 0, count = AppConfig.articlesPerPoll)
            } catch (e: Exception) {
                logger.error("轮询失败: fakeid=${fakeid.take(8)}..., ${e.message}")
            }
            // 每个号之间等待 3 秒，避免请求过快
            delay(3000)
        }

        logger.info("轮询周期完成")
    }

    /**
     * 手动触发一次轮询
     */
    suspend fun pollNow() {
        pollAll()
    }
}
