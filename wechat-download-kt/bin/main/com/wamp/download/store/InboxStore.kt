package com.wamp.download.store

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.util.UUID

object InboxStore {
    private const val PROCESSING_TIMEOUT_MS = 30 * 60 * 1000L

    fun claim(message: InboxMessage): ClaimResult = Database.query {
        val existing = RabbitInbox
            .select { RabbitInbox.idempotencyKey eq message.idempotencyKey }
            .singleOrNull()
        if (existing != null) {
            val isStale = existing[RabbitInbox.status] == "PROCESSING" &&
                message.now - existing[RabbitInbox.updatedAt] >= PROCESSING_TIMEOUT_MS
            if (existing[RabbitInbox.status] == "COMPLETED" ||
                (existing[RabbitInbox.status] == "PROCESSING" && !isStale)
            ) {
                return@query ClaimResult(claimed = false, resultEventId = existing[RabbitInbox.resultEventId])
            }

            RabbitInbox.update({ RabbitInbox.idempotencyKey eq message.idempotencyKey }) {
                it[eventId] = message.eventId
                it[workflowId] = message.workflowId
                it[status] = "PROCESSING"
                it[updatedAt] = message.now
            }
            return@query ClaimResult(claimed = true, resultEventId = existing[RabbitInbox.resultEventId])
        }

        val resultEventId = "evt_${UUID.randomUUID()}"
        val inserted = RabbitInbox.insertIgnore {
            it[idempotencyKey] = message.idempotencyKey
            it[eventId] = message.eventId
            it[workflowId] = message.workflowId
            it[RabbitInbox.resultEventId] = resultEventId
            it[status] = "PROCESSING"
            it[receivedAt] = message.now
            it[updatedAt] = message.now
        }
        if (inserted.insertedCount == 1) {
            ClaimResult(claimed = true, resultEventId = resultEventId)
        } else {
            val concurrent = RabbitInbox
                .select { RabbitInbox.idempotencyKey eq message.idempotencyKey }
                .single()
            ClaimResult(claimed = false, resultEventId = concurrent[RabbitInbox.resultEventId])
        }
    }

    fun markCompleted(idempotencyKey: String) {
        Database.query {
            RabbitInbox.update({ RabbitInbox.idempotencyKey eq idempotencyKey }) {
                it[status] = "COMPLETED"
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    fun markFailed(idempotencyKey: String) {
        Database.query {
            RabbitInbox.update({ RabbitInbox.idempotencyKey eq idempotencyKey }) {
                it[status] = "FAILED"
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    data class InboxMessage(
        val idempotencyKey: String,
        val eventId: String,
        val workflowId: String,
        val now: Long = System.currentTimeMillis(),
    )

    data class ClaimResult(
        val claimed: Boolean,
        val resultEventId: String,
    )
}