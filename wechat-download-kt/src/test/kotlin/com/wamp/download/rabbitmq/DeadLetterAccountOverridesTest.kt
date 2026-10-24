package com.wamp.download.rabbitmq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DeadLetterAccountOverridesTest {
    @Test
    fun `exact source name is mapped after trimming`() {
        val overrides = DeadLetterAccountOverrides.parse(listOf(" 海珠检查 " to " 广州海珠发布 "))

        assertEquals("广州海珠发布", overrides.targetFor("海珠检查"))
        assertNull(overrides.targetFor("海珠"))
        assertNull(overrides.targetFor("海珠检查2"))
    }

    @Test
    fun `url targets are preserved`() {
        val overrides = DeadLetterAccountOverrides.fromMap(
            mapOf("海珠检查" to "https://mp.weixin.qq.com/s/real-article?x=1"),
        )

        assertEquals(
            "https://mp.weixin.qq.com/s/real-article?x=1",
            overrides.targetFor("海珠检查"),
        )
    }

    @Test
    fun `blank or control-character mappings are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DeadLetterAccountOverrides.parse(listOf(" " to "广州海珠发布"))
        }
        assertFailsWith<IllegalArgumentException> {
            DeadLetterAccountOverrides.parse(listOf("海珠检查" to " "))
        }
        assertFailsWith<IllegalArgumentException> {
            DeadLetterAccountOverrides.parse(listOf("海珠\u0000检查" to "广州海珠发布"))
        }
    }

    @Test
    fun `duplicate source names are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DeadLetterAccountOverrides.parse(
                listOf("海珠检查" to "广州海珠发布", "海珠检查" to "另一个号"),
            )
        }
    }

    @Test
    fun `mapping count is bounded`() {
        val pairs = (1..101).map { "old-$it" to "new-$it" }
        assertFailsWith<IllegalArgumentException> {
            DeadLetterAccountOverrides.parse(pairs)
        }
    }
}
