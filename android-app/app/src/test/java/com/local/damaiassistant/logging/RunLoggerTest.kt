package com.local.damaiassistant.logging

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLoggerTest {
    @Test
    fun keepsOnlyNewestEntries() {
        val logger = RunLogger(capacity = 2)
        logger.record("state", "one", 1L, 1L)
        logger.record("state", "two", 2L, 2L)
        logger.record("state", "three", 3L, 3L)

        assertEquals(listOf("two", "three"), logger.snapshot().map { it.message })
    }

    @Test
    fun redactsLongDigitSequences() {
        val logger = RunLogger(capacity = 2)
        logger.record(
            "node",
            "phone=13800138000 id=123456789012345678 short=123456",
            1L,
            1L,
        )

        val message = logger.snapshot().single().message
        assertFalse(message.contains("13800138000"))
        assertFalse(message.contains("123456789012345678"))
        assertTrue(message.contains("short=123456"))
    }

    @Test
    fun snapshotIsDetachedAndClearRemovesEntries() {
        val logger = RunLogger(capacity = 2)
        logger.record("state", "one", 1L, 1L)
        val snapshot = logger.snapshot()

        logger.clear()

        assertEquals(listOf("one"), snapshot.map { it.message })
        assertTrue(logger.snapshot().isEmpty())
    }

    @Test
    fun writesSanitizedTabSeparatedUtf8() {
        val logger = RunLogger(capacity = 2)
        logger.record("node", "line\tone\n13800138000", 123L, 456L)
        val file = Files.createTempFile("run-log", ".tsv").toFile()

        try {
            logger.writeTo(file)

            assertEquals(
                "123\t456\tnode\tline one [REDACTED]\n",
                file.readText(Charsets.UTF_8).replace("\r\n", "\n"),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsInvalidCapacityAndTimestamps() {
        assertThrows(IllegalArgumentException::class.java) { RunLogger(0) }
        val logger = RunLogger(1)
        assertThrows(IllegalArgumentException::class.java) {
            logger.record("state", "bad", -1L, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            logger.record("state", "bad", 0L, -1L)
        }
    }
}
