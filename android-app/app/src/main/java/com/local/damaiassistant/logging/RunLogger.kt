package com.local.damaiassistant.logging

import java.io.File
import java.util.ArrayDeque

data class RunLogEntry(
    val category: String,
    val message: String,
    val wallMillis: Long,
    val elapsedNanos: Long,
)

class RunLogger(
    private val capacity: Int,
) {
    private val entries = ArrayDeque<RunLogEntry>(capacity)

    init {
        require(capacity > 0) { "Log capacity must be positive" }
    }

    @Synchronized
    fun record(
        category: String,
        message: String,
        wallMillis: Long,
        elapsedNanos: Long,
    ) {
        require(category.isNotBlank()) { "Log category must not be blank" }
        require(wallMillis >= 0L) { "Wall timestamp must be nonnegative" }
        require(elapsedNanos >= 0L) { "Elapsed timestamp must be nonnegative" }

        if (entries.size == capacity) {
            entries.removeFirst()
        }
        entries.addLast(
            RunLogEntry(
                category = normalizeField(category),
                message = sanitizeMessage(message),
                wallMillis = wallMillis,
                elapsedNanos = elapsedNanos,
            ),
        )
    }

    @Synchronized
    fun snapshot(): List<RunLogEntry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    fun writeTo(file: File) {
        val currentEntries = snapshot()
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            currentEntries.forEach { entry ->
                writer.append(entry.wallMillis.toString())
                writer.append('\t')
                writer.append(entry.elapsedNanos.toString())
                writer.append('\t')
                writer.append(entry.category)
                writer.append('\t')
                writer.append(entry.message)
                writer.newLine()
            }
        }
    }

    private fun sanitizeMessage(message: String): String = redact(message)

    companion object {
        const val REDACTED = "[REDACTED]"

        fun redact(value: String): String =
            sensitiveDigits.replace(normalizeField(value), REDACTED)

        private fun normalizeField(value: String): String =
            fieldSeparators.replace(value, " ").trim()

        private val sensitiveDigits = Regex("""\d{7,}""")
        private val fieldSeparators = Regex("""[\t\r\n]+""")
    }
}
