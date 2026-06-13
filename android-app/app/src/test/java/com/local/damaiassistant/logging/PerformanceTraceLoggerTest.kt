package com.local.damaiassistant.logging

import com.local.damaiassistant.automation.InputMode
import com.local.damaiassistant.domain.Stage
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceTraceLoggerTest {
    @Test
    fun keepsOrderedEventsAndCalculatesAdjacentDurations() {
        val logger = PerformanceTraceLogger(capacity = 3)
        logger.record(PerformanceEvent.TRIGGER_FIRED, 10L, 100L)
        logger.record(
            event = PerformanceEvent.STAGE_TAP_END,
            wallMillis = 11L,
            elapsedNanos = 160L,
            stage = Stage.STAGE_1,
            inputMode = InputMode.DIRECT_INJECT,
        )

        val entries = logger.snapshotWithDurations()

        assertEquals(
            listOf(PerformanceEvent.TRIGGER_FIRED, PerformanceEvent.STAGE_TAP_END),
            entries.map { it.entry.event },
        )
        assertEquals(null, entries[0].sincePreviousNanos)
        assertEquals(60L, entries[1].sincePreviousNanos)
    }

    @Test
    fun dropsOldestEventAtCapacity() {
        val logger = PerformanceTraceLogger(capacity = 2)
        logger.record(PerformanceEvent.ARMED, 1L, 1L)
        logger.record(PerformanceEvent.TRIGGER_DEADLINE, 2L, 2L)
        logger.record(PerformanceEvent.TRIGGER_FIRED, 3L, 3L)

        assertEquals(
            listOf(PerformanceEvent.TRIGGER_DEADLINE, PerformanceEvent.TRIGGER_FIRED),
            logger.snapshot().map { it.event },
        )
    }

    @Test
    fun writesTabSeparatedTraceWithDuration() {
        val logger = PerformanceTraceLogger(capacity = 2)
        logger.record(PerformanceEvent.ARMED, 1L, 10L, reason = "ready")
        logger.record(
            PerformanceEvent.STAGE_TAP_START,
            2L,
            25L,
            stage = Stage.STAGE_2,
            foreground = "cn.damai",
        )
        val file = Files.createTempFile("performance", ".tsv").toFile()

        try {
            logger.writeTo(file)

            val lines = file.readLines(Charsets.UTF_8)
            assertEquals(
                "wallMillis\telapsedNanos\tsincePreviousNanos\tevent\tstage\tinputMode\tforeground\treason",
                lines[0],
            )
            assertEquals("2\t25\t15\tstage_tap_start\tSTAGE_2\t\tcn.damai\t", lines[2])
        } finally {
            file.delete()
        }
    }
}
