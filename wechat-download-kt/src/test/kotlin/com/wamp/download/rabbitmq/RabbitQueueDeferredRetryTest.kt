package com.wamp.download.rabbitmq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RabbitQueueDeferredRetryTest {
    @Test
    fun `durable progress continuation remains queued beyond finite failure retry cap without increment`() {
        val manager = RabbitMqClientManager()

        assertTrue(manager.shouldRequeue(Int.MAX_VALUE, deferred = true))
        assertEquals(Int.MAX_VALUE, manager.nextRetryCount(Int.MAX_VALUE, deferred = true))
        assertEquals(false, manager.shouldRequeue(Int.MAX_VALUE, deferred = false))
        assertEquals(6, manager.nextRetryCount(5, deferred = false))
    }
}
