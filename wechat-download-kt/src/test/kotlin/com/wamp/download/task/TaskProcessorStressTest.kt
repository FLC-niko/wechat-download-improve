package com.wamp.download.task

import com.wamp.download.config.AppConfig
import com.wamp.download.rabbitmq.GzhAutoFromBackend
import com.wamp.download.store.Database
import com.wamp.download.weread.WeReadAccountPool
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import java.io.File

class TaskProcessorStressTest {

    @Test
    @Disabled("真实外部服务压测，不属于可重复的单元测试套件")
    fun `测试单条真实消息处理流程与异常观测`() = runBlocking {
        println("===== [1] 初始化数据库与配置 =====")
        AppConfig.load()
        Database.init()
        WeReadAccountPool.loadAll()

        println("WeRead 账号池状态: size=${WeReadAccountPool.size()}")
        WeReadAccountPool.list().forEach {
            println(" -> 账号: vid=${it.vid}, status=${it.status}, reason=${it.disabledReason}")
        }

        println("\n===== [2] 构造真实消息队列中的测试样本 (以已缓存的公众号为例) =====")
        val testTask = GzhAutoFromBackend(
            queueName = "gzh-auto-BTA-queue",
            image = "",
            gzhName = "共青团中央",
            tempTimeStamp = listOf("2025-12-01", "2025-12-15"),
            url = "",
            jobId = 1888888888888L,
            correlationId = "stress-test-uuid-002"
        )

        println("测试任务: $testTask")

        val processor = TaskProcessor(
            resultPublisher = null // 本地测试不发回远端 MQ
        )

        println("\n===== [3] 执行任务处理 (全链路压测与观测) =====")
        val startTime = System.currentTimeMillis()
        try {
            processor.process(testTask)
            val cost = System.currentTimeMillis() - startTime
            println("===== [4] 处理结束，总耗时: ${cost}ms =====")
        } catch (e: Throwable) {
            println("===== [4] 处理发生顶层未捕获异常: ${e.message} =====")
            e.printStackTrace()
        }
    }
}
