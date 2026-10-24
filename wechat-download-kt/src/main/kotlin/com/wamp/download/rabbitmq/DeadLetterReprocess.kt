package com.wamp.download.rabbitmq

import com.google.gson.JsonParser

data class DeadLetterReprocessItem(
    val accountName: String,
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

internal data class DeadLetterTaskHint(
    val accountName: String,
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
                resolveKey = entryUrl ?: account.name,
            )
        }
        is RabbitTaskMessage.Legacy -> DeadLetterTaskHint(
            accountName = message.task.gzhName,
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
