package com.wamp.download.store

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

data class PublicAccountCollectionCheckpoint(
    val fakeid: String,
    val mpId: String,
    val source: String,
    val oldestCollectedAt: Long,
    val newestCollectedAt: Long,
    val lastSuccessAt: Long,
    val pagesCollected: Int,
    val articlesCollected: Int,
) {
    fun covers(startTime: Long, endTime: Long): Boolean =
        oldestCollectedAt <= startTime && newestCollectedAt >= endTime
}

/** Persists page-level collection progress without overstating unfinished pages as covered. */
object PublicAccountCollectionCheckpointStore {
    fun get(fakeid: String, source: String = EINK_SOURCE): PublicAccountCollectionCheckpoint? = Database.query {
        PublicAccountCollectionCheckpoints.selectAll()
            .where {
                (PublicAccountCollectionCheckpoints.fakeid eq fakeid) and
                    (PublicAccountCollectionCheckpoints.collectionSource eq source.take(32))
            }
            .singleOrNull()
            ?.toCheckpoint()
    }

    fun covers(fakeid: String, startTime: Long, endTime: Long, source: String = EINK_SOURCE): Boolean {
        require(startTime <= endTime) { "startTime must not be after endTime" }
        return get(fakeid, source)?.covers(startTime, endTime) == true
    }

    /**
     * Records a continuous range only after all articles in that range have been persisted.
     *
     * Normal E-Ink scans always start at the newest page, so subsequent page ranges overlap the
     * current checkpoint. If a non-overlapping newer scan is ever observed, keep that newer safe
     * range instead of joining it across an unverified gap.
     */
    fun recordProgress(
        fakeid: String,
        mpId: String,
        oldestCollectedAt: Long,
        newestCollectedAt: Long,
        pagesDelta: Int,
        articlesDelta: Int,
        source: String = EINK_SOURCE,
    ): PublicAccountCollectionCheckpoint = Database.query {
        require(fakeid.isNotBlank()) { "fakeid must not be blank" }
        require(oldestCollectedAt <= newestCollectedAt) {
            "oldestCollectedAt must not be after newestCollectedAt"
        }
        require(pagesDelta >= 0) { "pagesDelta must not be negative" }
        require(articlesDelta >= 0) { "articlesDelta must not be negative" }

        val normalizedSource = source.take(32)
        val now = Instant.now().epochSecond
        val existing = PublicAccountCollectionCheckpoints.selectAll()
            .where {
                (PublicAccountCollectionCheckpoints.fakeid eq fakeid) and
                    (PublicAccountCollectionCheckpoints.collectionSource eq normalizedSource)
            }
            .singleOrNull()

        if (existing == null) {
            PublicAccountCollectionCheckpoints.insert {
                it[PublicAccountCollectionCheckpoints.fakeid] = fakeid
                it[PublicAccountCollectionCheckpoints.mpId] = mpId
                it[PublicAccountCollectionCheckpoints.collectionSource] = normalizedSource
                it[PublicAccountCollectionCheckpoints.oldestCollectedAt] = oldestCollectedAt
                it[PublicAccountCollectionCheckpoints.newestCollectedAt] = newestCollectedAt
                it[PublicAccountCollectionCheckpoints.lastSuccessAt] = now
                it[PublicAccountCollectionCheckpoints.pagesCollected] = pagesDelta
                it[PublicAccountCollectionCheckpoints.articlesCollected] = articlesDelta
            }
        } else {
            val existingOldest = existing[PublicAccountCollectionCheckpoints.oldestCollectedAt]
            val existingNewest = existing[PublicAccountCollectionCheckpoints.newestCollectedAt]
            val overlaps = oldestCollectedAt <= existingNewest && newestCollectedAt >= existingOldest
            val replaceWithNewerRange = !overlaps && newestCollectedAt > existingNewest
            val mergedOldest = when {
                overlaps -> minOf(existingOldest, oldestCollectedAt)
                replaceWithNewerRange -> oldestCollectedAt
                else -> existingOldest
            }
            val mergedNewest = when {
                overlaps -> maxOf(existingNewest, newestCollectedAt)
                replaceWithNewerRange -> newestCollectedAt
                else -> existingNewest
            }
            PublicAccountCollectionCheckpoints.update({
                (PublicAccountCollectionCheckpoints.fakeid eq fakeid) and
                    (PublicAccountCollectionCheckpoints.collectionSource eq normalizedSource)
            }) {
                it[PublicAccountCollectionCheckpoints.mpId] = mpId
                it[PublicAccountCollectionCheckpoints.oldestCollectedAt] = mergedOldest
                it[PublicAccountCollectionCheckpoints.newestCollectedAt] = mergedNewest
                it[PublicAccountCollectionCheckpoints.lastSuccessAt] = now
                it[PublicAccountCollectionCheckpoints.pagesCollected] =
                    existing[PublicAccountCollectionCheckpoints.pagesCollected] + pagesDelta
                it[PublicAccountCollectionCheckpoints.articlesCollected] =
                    existing[PublicAccountCollectionCheckpoints.articlesCollected] + articlesDelta
            }
        }

        PublicAccountCollectionCheckpoints.selectAll()
            .where {
                (PublicAccountCollectionCheckpoints.fakeid eq fakeid) and
                    (PublicAccountCollectionCheckpoints.collectionSource eq normalizedSource)
            }
            .single()
            .toCheckpoint()
    }

    private fun ResultRow.toCheckpoint() = PublicAccountCollectionCheckpoint(
        fakeid = this[PublicAccountCollectionCheckpoints.fakeid],
        mpId = this[PublicAccountCollectionCheckpoints.mpId],
        source = this[PublicAccountCollectionCheckpoints.collectionSource],
        oldestCollectedAt = this[PublicAccountCollectionCheckpoints.oldestCollectedAt],
        newestCollectedAt = this[PublicAccountCollectionCheckpoints.newestCollectedAt],
        lastSuccessAt = this[PublicAccountCollectionCheckpoints.lastSuccessAt],
        pagesCollected = this[PublicAccountCollectionCheckpoints.pagesCollected],
        articlesCollected = this[PublicAccountCollectionCheckpoints.articlesCollected],
    )

    const val EINK_SOURCE = "eink"
}
