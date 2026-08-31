package com.wamp.download.store

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

/** Stores conservative, source-verified cache coverage intervals. */
object ArticleCoverageStore {
    fun intervals(fakeid: String, startTime: Long, endTime: Long): List<LongRange> {
        require(startTime <= endTime) { "startTime must not be after endTime" }
        return Database.query {
            ArticleCoverageIntervals.selectAll()
                .where {
                    (ArticleCoverageIntervals.fakeid eq fakeid) and
                        (ArticleCoverageIntervals.coverageEnd greaterEq startTime) and
                        (ArticleCoverageIntervals.coverageStart lessEq endTime)
                }
                .orderBy(ArticleCoverageIntervals.coverageStart, SortOrder.ASC)
                .map {
                    it[ArticleCoverageIntervals.coverageStart]..it[ArticleCoverageIntervals.coverageEnd]
                }
        }
    }

    /**
     * Records coverage only after articles from the same successful fetch have been persisted.
     * Duplicate intervals are idempotent; verifiedAt is refreshed for observability.
     */
    fun saveVerified(fakeid: String, startTime: Long, endTime: Long, source: String) {
        require(startTime <= endTime) { "startTime must not be after endTime" }
        require(source.isNotBlank()) { "source must not be blank" }
        Database.query {
            val now = Instant.now().epochSecond
            val inserted = ArticleCoverageIntervals.insertIgnore {
                it[ArticleCoverageIntervals.fakeid] = fakeid
                it[coverageStart] = startTime
                it[coverageEnd] = endTime
                it[ArticleCoverageIntervals.coverageSource] = source.take(32)
                it[verifiedAt] = now
            }.insertedCount
            if (inserted == 0) {
                ArticleCoverageIntervals.update({
                    (ArticleCoverageIntervals.fakeid eq fakeid) and
                        (ArticleCoverageIntervals.coverageStart eq startTime) and
                        (ArticleCoverageIntervals.coverageEnd eq endTime) and
                        (ArticleCoverageIntervals.coverageSource eq source.take(32))
                }) {
                    it[verifiedAt] = now
                }
            }
        }
    }
}
