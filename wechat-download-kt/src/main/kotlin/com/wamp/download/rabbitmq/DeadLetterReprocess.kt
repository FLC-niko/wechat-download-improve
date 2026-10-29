package com.wamp.download.rabbitmq

import com.google.gson.JsonParser

data class DeadLetterReprocessItem(
    val accountName: String,
    val messageType: String,
    val resolutionSource: String,
    val resolvedAs: String? = null,
    val action: String,
    val reason: String? = null,
)

data class DeadLetterReprocessResult(
    val vhost: String,
    val dryRun: Boolean,
    val scanned: Int,
    val requeued: Int,
    val skipped: Int,
    val failed: Int,
    val items: List<DeadLetterReprocessItem>,
)

class DeadLetterAccountOverrides private constructor(
    private val byName: Map<String, String>,
) {
    fun targetFor(accountName: String): String? = byName[accountName.trim()]

    companion object {
        val EMPTY = DeadLetterAccountOverrides(emptyMap())

        fun parse(pairs: Iterable<Pair<String, String>>): DeadLetterAccountOverrides {
            val map = LinkedHashMap<String, String>()
            pairs.forEach { (rawKey, rawValue) ->
                val key = rawKey.trim()
                val value = rawValue.trim()
                require(key.isNotEmpty()) { "account_map 的源名称不能为空" }
                require(value.isNotEmpty()) { "account_map 的目标不能为空: $key" }
                require(key.none { it.isISOControl() } && value.none { it.isISOControl() }) {
                    "account_map 不能包含控制字符"
                }
                require(map.put(key, value) == null) { "account_map 存在重复源名称: $key" }
            }
            require(map.size <= 100) { "account_map 最多 100 条" }
            return DeadLetterAccountOverrides(map)
        }

        fun fromMap(map: Map<String, String>): DeadLetterAccountOverrides =
            parse(map.entries.map { it.key to it.value })
    }
}

internal data class DeadLetterTaskHint(
    val accountName: String,
    val messageType: String,
    val resolveKey: String,
)

internal object DeadLetterTaskInspector {
    fun hint(body: ByteArray): DeadLetterTaskHint? = when (val message = RabbitTaskDecoder.decode(body)) {
        is RabbitTaskMessage.V2 -> {
            val account = message.task.payload.account
            val entryUrl = account.entryUrl?.trim()?.takeIf {
                it.startsWith("http://") || it.startsWith("https://")
            }
            DeadLetterTaskHint(
                accountName = account.name,
                messageType = "v2",
                resolveKey = entryUrl ?: account.name,
            )
        }
        is RabbitTaskMessage.Legacy -> DeadLetterTaskHint(
            accountName = message.task.gzhName,
            messageType = "legacy",
            resolveKey = message.task.gzhName,
        )
    }
}

internal object DeadLetterTaskRewriter {
    fun rewriteAccountName(body: ByteArray, accountName: String): ByteArray {
        if (accountName.isBlank()) return body
        val root = runCatching {
            JsonParser.parseString(body.toString(Charsets.UTF_8))
        }.getOrNull() ?: return body
        if (!root.isJsonObject) return body
        val obj = root.asJsonObject
        val account = obj.getAsJsonObject("payload")?.getAsJsonObject("account")
        when {
            account != null -> account.addProperty("name", accountName)
            obj.has("gzhName") -> obj.addProperty("gzhName", accountName)
            else -> return body
        }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }
}
