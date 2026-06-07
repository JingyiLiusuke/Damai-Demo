package com.local.damaiassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TriggerDeadlineTest {
    @Test
    fun computesMonotonicDeadlineFromWallClockDelta() {
        assertEquals(
            7_900_000_000L,
            TriggerDeadline.compute(
                targetWallMillis = 10_000L,
                preTriggerOffsetMillis = 100L,
                nowWallMillis = 2_000L,
                nowElapsedNanos = 0L,
            ),
        )
    }

    @Test
    fun rejectsPastTrigger() {
        assertThrows(IllegalArgumentException::class.java) {
            TriggerDeadline.compute(1_000L, 0L, 1_001L, 0L)
        }
    }

    @Test
    fun includesCurrentMonotonicTime() {
        assertEquals(
            10_000_000_123L,
            TriggerDeadline.compute(12_000L, 0L, 2_000L, 123L),
        )
    }

    @Test
    fun rejectsNegativeOffsetAndOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            TriggerDeadline.compute(2_000L, -1L, 1_000L, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TriggerDeadline.compute(Long.MAX_VALUE, 0L, 0L, 0L)
        }
    }
}
