package com.wamp.download.task

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureWindowParserTest {
    @Test
    fun `纯日期窗口包含结束日`() {
        val (start, end) = CaptureWindowParser.parse(
            listOf("2026-08-10", "2026-08-13"),
            ZoneOffset.UTC,
        )

        assertEquals(InstantSeconds.of("2026-08-10T00:00:00Z"), start)
        assertEquals(InstantSeconds.of("2026-08-13T23:59:59Z"), end)
    }

    @Test
    fun `RFC3339窗口保留精确边界`() {
        val (start, end) = CaptureWindowParser.parse(
            listOf("2026-08-10T08:00:00+08:00", "2026-08-13T18:30:00+08:00"),
        )

        assertEquals(InstantSeconds.of("2026-08-10T00:00:00Z"), start)
        assertEquals(InstantSeconds.of("2026-08-13T10:30:00Z"), end)
    }

    @Test
    fun `非法或反向窗口返回空结果`() {
        assertEquals(null to null, CaptureWindowParser.parse(listOf("bad", "2026-08-13")))
        val reversed = CaptureWindowParser.parse(listOf("2026-08-14", "2026-08-13"))
        assertNull(reversed.first)
        assertNull(reversed.second)
    }

    private object InstantSeconds {
        fun of(value: String): Long = java.time.Instant.parse(value).epochSecond
    }
}