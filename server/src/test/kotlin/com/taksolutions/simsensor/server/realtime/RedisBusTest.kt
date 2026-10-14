package com.taksolutions.simsensor.server.realtime

import com.taksolutions.simsensor.server.support.TestRedis
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class RedisBusTest {

    /** Two buses stand in for two server instances: an event published on one reaches the other. */
    @Test
    fun eventsCrossInstances() {
        RedisBus(TestRedis.url).use { instanceA ->
            RedisBus(TestRedis.url).use { instanceB ->
                val receivedByB = LinkedBlockingQueue<BusEvent>()
                instanceB.subscribe { receivedByB += it }

                val event = ExerciseChangedEvent("6f1e0c1e-0000-4000-8000-000000000001")
                instanceA.publish(event)

                assertEquals(event, receivedByB.poll(5, TimeUnit.SECONDS))
            }
        }
    }
}
