package com.wamp.download.tools

import com.google.gson.GsonBuilder
import com.wamp.download.config.AppConfig
import com.wamp.download.rabbitmq.RABBITMQ_TEST_VIRTUAL_HOST
import com.wamp.download.rabbitmq.RABBITMQ_VIRTUAL_HOST
import com.wamp.download.rabbitmq.RabbitMqClientManager
import com.wamp.download.store.Database
import com.wamp.download.weread.WeReadDirectoryResolver
import com.wamp.download.weread.WeReadSubscriptionRegistrar
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    var vhost = "pro"
    var limit = 10
    var dryRun = true
    var index = 0
    while (index < args.size) {
        when (val arg = args[index]) {
            "--vhost" -> {
                vhost = args.getOrNull(++index) ?: error("--vhost 需要 pro/test")
            }
            "--limit" -> {
                limit = args.getOrNull(++index)?.toIntOrNull() ?: error("--limit 需要数字")
            }
            "--apply" -> dryRun = false
            "--dry-run" -> dryRun = true
            "--help", "-h" -> {
                println("用法: DeadLetterReprocessMainKt [--vhost pro|test] [--limit N] [--dry-run|--apply]")
                return
            }
            else -> error("未知参数: $arg")
        }
        index++
    }
    require(vhost == "pro" || vhost == "test") { "仅支持 pro/test vhost" }

    AppConfig.load()
    Database.init()
    val manager = RabbitMqClientManager()
    try {
        runBlocking {
            manager.connect(virtualHost = if (vhost == "pro") RABBITMQ_VIRTUAL_HOST else RABBITMQ_TEST_VIRTUAL_HOST)
            val registrar = WeReadSubscriptionRegistrar(WeReadDirectoryResolver())
            val result = manager.reprocessDeadLetters(limit, dryRun, vhost) { name ->
                if (dryRun) {
                    registrar.resolveOnly(name)?.nickname
                } else {
                    registrar.resolveAndSave(name)?.nickname
                }
            }
            println(GsonBuilder().setPrettyPrinting().create().toJson(result))
        }
    } finally {
        manager.close()
    }
}
