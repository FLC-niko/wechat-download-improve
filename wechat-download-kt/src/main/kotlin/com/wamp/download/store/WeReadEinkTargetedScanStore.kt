package com.wamp.download.store

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

data class WeReadEinkTargetedScan(
    val accountVid: String,
    val mpId: String,
    val requestedStart: Long,
    val requestedEnd: Long,
    val scanMode: String,
    val nextOffset: Long,
    val baseSynckey: Long?,
    val synckey: Long?,
    val scanStartedAt: Long,
    val oldestCollectedAt: Long?,
)

/** Pending range scans persist independently from the /mp/chapters synckey cursor. */
object WeReadEinkTargetedScanStore {
    fun get(accountVid: String, mpId: String, requestedStart: Long, scanMode: String): WeReadEinkTargetedScan? = Database.query {
        WeReadEinkTargetedScans.selectAll().where {
            (WeReadEinkTargetedScans.accountVid eq accountVid) and
                (WeReadEinkTargetedScans.mpId eq mpId) and
                (WeReadEinkTargetedScans.scanMode eq scanMode) and
                (WeReadEinkTargetedScans.requestedStart eq requestedStart)
        }.singleOrNull()?.toScan()
    }

    fun saveProgress(
        accountVid: String,
        mpId: String,
        requestedStart: Long,
        requestedEnd: Long,
        scanMode: String,
        nextOffset: Long,
        baseSynckey: Long?,
        synckey: Long?,
        scanStartedAt: Long,
        oldestCollectedAt: Long?,
    ) = Database.query {
        val existing = WeReadEinkTargetedScans.selectAll().where {
            (WeReadEinkTargetedScans.accountVid eq accountVid) and
                (WeReadEinkTargetedScans.mpId eq mpId) and
                (WeReadEinkTargetedScans.scanMode eq scanMode) and
                (WeReadEinkTargetedScans.requestedStart eq requestedStart)
        }.singleOrNull()
        if (existing == null) {
            WeReadEinkTargetedScans.insert {
                it[WeReadEinkTargetedScans.accountVid] = accountVid
                it[WeReadEinkTargetedScans.mpId] = mpId
                it[WeReadEinkTargetedScans.scanMode] = scanMode
                it[WeReadEinkTargetedScans.requestedStart] = requestedStart
                it[WeReadEinkTargetedScans.requestedEnd] = requestedEnd
                it[WeReadEinkTargetedScans.nextOffset] = nextOffset
                it[WeReadEinkTargetedScans.baseSynckey] = baseSynckey
                it[WeReadEinkTargetedScans.synckey] = synckey
                it[WeReadEinkTargetedScans.scanStartedAt] = scanStartedAt
                it[WeReadEinkTargetedScans.oldestCollectedAt] = oldestCollectedAt
            }
        } else {
            // Keep the initial end boundary and timestamp: a retry cannot silently extend the
            // time interval represented by a partially completed scan.
            WeReadEinkTargetedScans.update({
                (WeReadEinkTargetedScans.accountVid eq accountVid) and
                    (WeReadEinkTargetedScans.mpId eq mpId) and
                    (WeReadEinkTargetedScans.scanMode eq scanMode) and
                    (WeReadEinkTargetedScans.requestedStart eq requestedStart)
            }) {
                it[WeReadEinkTargetedScans.nextOffset] = nextOffset
                it[WeReadEinkTargetedScans.synckey] = synckey ?: existing[WeReadEinkTargetedScans.synckey]
                it[WeReadEinkTargetedScans.oldestCollectedAt] = oldestCollectedAt
            }
        }
    }

    fun delete(accountVid: String, mpId: String, requestedStart: Long, scanMode: String) = Database.query {
        WeReadEinkTargetedScans.deleteWhere {
            (WeReadEinkTargetedScans.accountVid eq accountVid) and
                (WeReadEinkTargetedScans.mpId eq mpId) and
                (WeReadEinkTargetedScans.scanMode eq scanMode) and
                (WeReadEinkTargetedScans.requestedStart eq requestedStart)
        }
    }

    private fun ResultRow.toScan() = WeReadEinkTargetedScan(
        accountVid = this[WeReadEinkTargetedScans.accountVid],
        mpId = this[WeReadEinkTargetedScans.mpId],
        requestedStart = this[WeReadEinkTargetedScans.requestedStart],
        requestedEnd = this[WeReadEinkTargetedScans.requestedEnd],
        scanMode = this[WeReadEinkTargetedScans.scanMode],
        nextOffset = this[WeReadEinkTargetedScans.nextOffset],
        baseSynckey = this[WeReadEinkTargetedScans.baseSynckey],
        synckey = this[WeReadEinkTargetedScans.synckey],
        scanStartedAt = this[WeReadEinkTargetedScans.scanStartedAt],
        oldestCollectedAt = this[WeReadEinkTargetedScans.oldestCollectedAt],
    )
}
