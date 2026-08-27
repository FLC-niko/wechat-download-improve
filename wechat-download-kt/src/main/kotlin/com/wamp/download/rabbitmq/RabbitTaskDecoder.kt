package com.wamp.download.rabbitmq

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

sealed interface RabbitTaskMessage {
    val eventId: String
    val workflowId: String
    val idempotencyKey: String

    data class V2(val task: GzhAutoBta) : RabbitTaskMessage {
        override val eventId: String = task.eventId
        override val workflowId: String = task.workflowId
        override val idempotencyKey: String = task.idempotencyKey
    }

    data class Legacy(val task: GzhAutoFromBackend) : RabbitTaskMessage {
        override val eventId: String = "legacy-${task.correlationId}"
        override val workflowId: String = task.correlationId
        override val idempotencyKey: String = "$workflowId:BTA"
    }
}

internal class RabbitTaskFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal object RabbitTaskDecoder {
    private val v2Type = object : TypeToken<RabbitV2Envelope<GzhAutoBtaPayload>>() {}.type
    private val legacyType = object : TypeToken<GzhAutoFromBackend>() {}.type

    fun decode(body: ByteArray, gson: Gson = Gson()): RabbitTaskMessage {
        return try {
            decodeInternal(body, gson)
        } catch (e: RabbitTaskFormatException) {
            throw e
        } catch (e: Exception) {
            throw RabbitTaskFormatException(e.message ?: "RabbitMQ 任务格式非法", e)
        }
    }

    private fun decodeInternal(body: ByteArray, gson: Gson): RabbitTaskMessage {
        val root = JsonParser.parseString(body.toString(Charsets.UTF_8))
        require(root.isJsonObject) { "RabbitMQ 任务必须是 JSON 对象" }
        val rootObject = root.asJsonObject

        return if (rootObject.has("payload")) {
            requireNonBlankString(rootObject, "eventId", "V2 任务")
            requireNonBlankString(rootObject, "workflowId", "V2 任务")
            requireNonBlankString(rootObject, "idempotencyKey", "V2 任务")
            val payload = requireObject(rootObject, "payload", "V2 任务")
            val account = requireObject(payload, "account", "V2 任务")
            requireNonBlankString(account, "name", "V2 任务 account")
            val captureWindow = requireObject(payload, "captureWindow", "V2 任务")
            requireNonBlankString(captureWindow, "startsAt", "V2 任务 captureWindow")
            requireNonBlankString(captureWindow, "endsAt", "V2 任务 captureWindow")

            val task = gson.fromJson<GzhAutoBta>(root, v2Type)
            RabbitTaskMessage.V2(task)
        } else {
            requireNonBlankString(rootObject, "gzhName", "旧版任务")
            requireNonBlankString(rootObject, "correlationId", "旧版任务")
            val timestamps = rootObject.get("tempTimeStamp")
            require(timestamps != null && timestamps.isJsonArray && timestamps.asJsonArray.size() >= 2) {
                "旧版任务缺少完整 tempTimeStamp"
            }

            val task = gson.fromJson<GzhAutoFromBackend>(root, legacyType)
            RabbitTaskMessage.Legacy(task)
        }
    }

    private fun requireObject(root: JsonObject, fieldName: String, messageType: String): JsonObject {
        val value = root.get(fieldName)
        require(value != null && value.isJsonObject) { "${messageType}缺少 $fieldName" }
        return value.asJsonObject
    }

    private fun requireNonBlankString(root: JsonObject, fieldName: String, messageType: String): String {
        val value = root.get(fieldName)
        require(
            value != null && value.isJsonPrimitive && !value.isJsonNull && value.asJsonPrimitive.isString
        ) { "${messageType}缺少 $fieldName" }
        return value.asString.also { require(it.isNotBlank()) { "${messageType}缺少 $fieldName" } }
    }
}