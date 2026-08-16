package com.wamp.download.rabbitmq

/**
 * 全局 RabbitMQ 客户端单例入口（懒加载）
 *
 * 用法示例：
 * ```kotlin
 * val mgr = RabbitMqClient.getManager()
 * runBlocking { mgr.connect(host = "localhost") }
 * mgr.startConsumer("queue-name") { body -> println(String(body)) }
 * runBlocking { mgr.publish("queue-name", "hello".toByteArray()) }
 * ```
 */
object RabbitMqClient {
    private var rabbitMqClientManager: RabbitMqClientManager? = null
    private var testRabbitMqClientManager: RabbitMqClientManager? = null
    private var thdagRabbitMqClientManager: RabbitMqClientManager? = null

    /** 返回 pro 虚拟主机管理器实例（懒加载） */
    fun getManager(): RabbitMqClientManager {
        if (rabbitMqClientManager == null) {
            rabbitMqClientManager = RabbitMqClientManager()
        }
        return rabbitMqClientManager!!
    }

    /** 返回 test 虚拟主机管理器实例（懒加载） */
    fun getTestManager(): RabbitMqClientManager {
        if (testRabbitMqClientManager == null) {
            testRabbitMqClientManager = RabbitMqClientManager()
        }
        return testRabbitMqClientManager!!
    }

    /** 返回 thdag 虚拟主机管理器实例（懒加载） */
    fun getThdagManager(): RabbitMqClientManager {
        if (thdagRabbitMqClientManager == null) {
            thdagRabbitMqClientManager = RabbitMqClientManager()
        }
        return thdagRabbitMqClientManager!!
    }
}
