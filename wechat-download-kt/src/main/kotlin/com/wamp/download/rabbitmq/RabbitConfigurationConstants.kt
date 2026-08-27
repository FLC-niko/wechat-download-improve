package com.wamp.download.rabbitmq

import com.wamp.download.config.AppConfig

// RabbitMQ 拓扑和任务策略常量
// 连接凭据从 AppConfig 读取（支持环境变量和 config.yaml）

// --- RabbitMQ 连接配置（从 AppConfig 读取）---
val RABBITMQ_HOST: String
    get() = AppConfig.rabbitmqHost

val RABBITMQ_PORT: Int
    get() = AppConfig.rabbitmqPort

val RABBITMQ_USERNAME: String
    get() = AppConfig.rabbitmqUsername

val RABBITMQ_PASSWORD: String
    get() = AppConfig.rabbitmqPassword

val RABBITMQ_VIRTUAL_HOST: String
    get() = AppConfig.rabbitmqProVirtualHost

val RABBITMQ_TEST_VIRTUAL_HOST: String
    get() = AppConfig.rabbitmqTestVirtualHost

val RABBITMQ_XDAG_VIRTUAL_HOST: String
    get() = AppConfig.rabbitmqXdagVirtualHost

val RABBITMQ_THDAG_VIRTUAL_HOST: String
    get() = AppConfig.rabbitmqThdagVirtualHost

// --- RabbitMQ 拓扑（业务常量，不需要外部配置）---
const val EXCHANGE_NEW_MEDIA = "new-media-backend"
const val GZH_TASK_QUEUE = "gzh-auto-BTA-queue"
const val GZH_TASK_ROUTING = "gzh-auto-BTA-routing"
const val GZH_RESULT_QUEUE = "gzh-auto-ATD-queue"
const val GZH_RESULT_ROUTING = "gzh-auto-ATD-routing"
const val SERVER_STATUS_QUEUE = "server.queue.provideLog"

// --- 任务策略（从 AppConfig 读取）---
val TASK_RETRY_DELAY_MS: Long
    get() = AppConfig.taskRetryDelayMs

val MAX_TASK_RETRY: Int
    get() = AppConfig.maxTaskRetry
