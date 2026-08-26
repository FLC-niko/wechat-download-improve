package com.wamp.download.task

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

object CaptureWindowParser {
    fun parse(range: List<String>, zone: ZoneId = ZoneId.systemDefault()): Pair<Long?, Long?> {
        if (range.size < 2) return null to null
        val start = parseBoundary(range[0], isEnd = false, zone = zone)
        val end = parseBoundary(range[1], isEnd = true, zone = zone)
        return if (start == null || end == null || start > end) null to null else start to end
    }

    private fun parseBoundary(value: String, isEnd: Boolean, zone: ZoneId): Long? {
        return try {
            Instant.parse(value).epochSecond
        } catch (_: DateTimeParseException) {
            try {
                val date = LocalDate.parse(value)
                val boundary = if (isEnd) date.plusDays(1).atStartOfDay(zone).minusNanos(1) else date.atStartOfDay(zone)
                boundary.toEpochSecond()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}