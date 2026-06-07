package com.local.damaiassistant.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerSchedulerTest {
    @Test
    fun longOldDelayDoesNotBlockNewShortDelay() {
        TriggerScheduler().use { scheduler ->
            val shortDelayFired = CountDownLatch(1)
            scheduler.scheduleAfter(2_000L) {}
            scheduler.scheduleAfter(10L) { shortDelayFired.countDown() }

            assertTrue(shortDelayFired.await(500L, TimeUnit.MILLISECONDS))
        }
    }
}
