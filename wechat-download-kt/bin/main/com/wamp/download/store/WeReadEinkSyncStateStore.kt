package com.wamp.download.store

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

data class WeReadEinkSyncState(
    val accountVid: String,
    val fakeid: String,
    val mpId: String,
    val synckey: Long? = null,
    val lastSyncAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
    val coverageStart: Long? = null,
    val coverageEnd: Long? = null,
    val historyComplete: Boolean = false,
    val coverageVersion: Int = 0,
    val status: String = "NEVER_SYNCED",
    val errorType: String = "",
    val errorMessage: String = "",
)

object WeReadEinkSyncStateStore {
    fun get(accountVid: String, mpId: String): WeReadEinkSyncState? = Database.query {
        WeReadEinkSyncStates.selectAll()
            .where {
                (WeReadEinkSyncStates.accountVid eq accountVid) and
                    (WeReadEinkSyncStates.mpId eq mpId)
            }
            .singleOrNull()
            ?.toState()
    }

    fun saveSuccess(
        accountVid: String,
        fakeid: String,
        mpId: String,
        synckey: Long?,
        coverageStart: Long?,
        coverageEnd: Long?,
        historyComplete: Boolean,
    ) = Database.query {
        val now = Instant.now().epochSecond
        val existing = WeReadEinkSyncStates.selectAll()
            .where {
                (WeReadEinkSyncStates.accountVid eq accountVid) and
                    (WeReadEinkSyncStates.mpId eq mpId)
            }
            .singleOrNull()
        if (existing == null) {
            WeReadEinkSyncStates.insert {
                it[WeReadEinkSyncStates.accountVid] = accountVid
                it[WeReadEinkSyncStates.fakeid] = fakeid
                it[WeReadEinkSyncStates.mpId] = mpId
                it[WeReadEinkSyncStates.synckey] = synckey
                it[WeReadEinkSyncStates.lastSyncAt] = now
                it[WeReadEinkSyncStates.lastSuccessAt] = now
                it[WeReadEinkSyncStates.coverageStart] = coverageStart
                it[WeReadEinkSyncStates.coverageEnd] = coverageEnd
                it[WeReadEinkSyncStates.historyComplete] = historyComplete
                it[WeReadEinkSyncStates.coverageVersion] = CURRENT_COVERAGE_VERSION
                it[WeReadEinkSyncStates.status] = "ACTIVE"
                it[WeReadEinkSyncStates.errorType] = ""
                it[WeReadEinkSyncStates.errorMessage] = ""
            }
        } else {
            WeReadEinkSyncStates.update({
                (WeReadEinkSyncStates.accountVid eq accountVid) and
                    (WeReadEinkSyncStates.mpId eq mpId)
            }) {
                it[WeReadEinkSyncStates.mpId] = mpId
                it[WeReadEinkSyncStates.synckey] = synckey ?: existing[WeReadEinkSyncStates.synckey]
                it[WeReadEinkSyncStates.lastSyncAt] = now
                it[WeReadEinkSyncStates.lastSuccessAt] = now
                it[WeReadEinkSyncStates.coverageStart] = coverageStart ?: existing[WeReadEinkSyncStates.coverageStart]
                it[WeReadEinkSyncStates.coverageEnd] = coverageEnd ?: existing[WeReadEinkSyncStates.coverageEnd]
                it[WeReadEinkSyncStates.historyComplete] = historyComplete || existing[WeReadEinkSyncStates.historyComplete]
                it[WeReadEinkSyncStates.coverageVersion] = CURRENT_COVERAGE_VERSION
                it[WeReadEinkSyncStates.status] = "ACTIVE"
                it[WeReadEinkSyncStates.errorType] = ""
                it[WeReadEinkSyncStates.errorMessage] = ""
            }
        }
    }

    fun saveFailure(
        accountVid: String,
        fakeid: String,
        mpId: String,
        errorType: String,
        errorMessage: String,
    ) = Database.query {
        val now = Instant.now().epochSecond
        val existing = WeReadEinkSyncStates.selectAll()
            .where {
                (WeReadEinkSyncStates.accountVid eq accountVid) and
                    (WeReadEinkSyncStates.mpId eq mpId)
            }
            .singleOrNull()
        if (existing == null) {
            WeReadEinkSyncStates.insert {
                it[WeReadEinkSyncStates.accountVid] = accountVid
                it[WeReadEinkSyncStates.fakeid] = fakeid
                it[WeReadEinkSyncStates.mpId] = mpId
                it[WeReadEinkSyncStates.lastSyncAt] = now
                it[WeReadEinkSyncStates.status] = "FAILED"
                it[WeReadEinkSyncStates.errorType] = errorType
                it[WeReadEinkSyncStates.errorMessage] = errorMessage.take(500)
            }
        } else {
            WeReadEinkSyncStates.update({
                (WeReadEinkSyncStates.accountVid eq accountVid) and
                    (WeReadEinkSyncStates.mpId eq mpId)
            }) {
                it[WeReadEinkSyncStates.mpId] = mpId
                it[WeReadEinkSyncStates.lastSyncAt] = now
                it[WeReadEinkSyncStates.status] = "FAILED"
                it[WeReadEinkSyncStates.errorType] = errorType
                it[WeReadEinkSyncStates.errorMessage] = errorMessage.take(500)
            }
        }
    }

    private fun ResultRow.toState() = WeReadEinkSyncState(
        accountVid = this[WeReadEinkSyncStates.accountVid],
        fakeid = this[WeReadEinkSyncStates.fakeid],
        mpId = this[WeReadEinkSyncStates.mpId],
        synckey = this[WeReadEinkSyncStates.synckey],
        lastSyncAt = this[WeReadEinkSyncStates.lastSyncAt],
        lastSuccessAt = this[WeReadEinkSyncStates.lastSuccessAt],
        coverageStart = this[WeReadEinkSyncStates.coverageStart],
        coverageEnd = this[WeReadEinkSyncStates.coverageEnd],
        historyComplete = this[WeReadEinkSyncStates.historyComplete],
        coverageVersion = this[WeReadEinkSyncStates.coverageVersion],
        status = this[WeReadEinkSyncStates.status],
        errorType = this[WeReadEinkSyncStates.errorType],
        errorMessage = this[WeReadEinkSyncStates.errorMessage],
    )

    const val CURRENT_COVERAGE_VERSION = 1
}
