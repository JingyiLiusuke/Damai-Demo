package com.local.damaiassistant.logging

import com.local.damaiassistant.automation.InputMode
import com.local.damaiassistant.domain.Stage
import java.io.File
import java.util.ArrayDeque

enum class PerformanceEvent(
    val wireName: String,
) {
    ARMED("armed"),
    INPUT_WARMED("input_warmed"),
    TRIGGER_DEADLINE("trigger_deadline"),
    TRIGGER_FIRED("trigger_fired"),
    STAGE_TAP_START("stage_tap_start"),
    STAGE_TAP_END("stage_tap_end"),
    STAGE_OBSERVED("stage_observed"),
    RESULT_OBSERVED("result_observed"),
    CANCELLED_OR_FAILED("cancelled_or_failed"),
}

data class PerformanceTraceEntry(
    val event: PerformanceEvent,
    val wallMillis: Long,
    val elapsedNanos: Long,
    val stage: Stage? = null,
    val inputMode: InputMode? = null,
    val foreground: String? = null,
    val reason: String? = null,
)

data class TimedPerformanceTraceEntry(
    val entry: PerformanceTraceEntry,
    val sincePreviousNanos: Long?,
)

class PerformanceTraceLogger(
    private val capacity: Int,
) {
    private val entries = ArrayDeque<PerformanceTraceEntry>(capacity)

    init {
        require(capacity > 0) { "Trace capacity must be positive" }
    }

    @Synchronized
    fun record(
        event: PerformanceEvent,
        wallMillis: Long,
        elapsedNanos: Long,
        stage: Stage? = null,
        inputMode: InputMode? = null,
        foreground: String? = null,
        reason: String? = null,
    ) {
        require(wallMillis >= 0L) { "Wall timestamp must be nonnegative" }
        require(elapsedNanos >= 0L) { "Elapsed timestamp must be nonnegative" }
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(
            PerformanceTraceEntry(
                event = event,
                wallMillis = wallMillis,
                elapsedNanos = elapsedNanos,
                stage = stage,
                inputMode = inputMode,
                foreground = foreground?.let(RunLogger::redact),
                reason = reason?.let(RunLogger::redact),
            ),
        )
    }

    @Synchronized
    fun snapshot(): List<PerformanceTraceEntry> = entries.toList()

    fun snapshotWithDurations(): List<TimedPerformanceTraceEntry> {
        var previous: Long? = null
        return snapshot().map { entry ->
            val duration = previous?.let { (entry.elapsedNanos - it).coerceAtLeast(0L) }
            previous = entry.elapsedNanos
            TimedPerformanceTraceEntry(entry, duration)
        }
    }

    fun writeTo(file: File) {
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine(HEADER)
            snapshotWithDurations().forEach { timed ->
                val entry = timed.entry
                writer.append(entry.wallMillis.toString())
                writer.append('\t')
                writer.append(entry.elapsedNanos.toString())
                writer.append('\t')
                writer.append(timed.sincePreviousNanos?.toString().orEmpty())
                writer.append('\t')
                writer.append(entry.event.wireName)
                writer.append('\t')
                writer.append(entry.stage?.name.orEmpty())
                writer.append('\t')
                writer.append(entry.inputMode?.name.orEmpty())
                writer.append('\t')
                writer.append(entry.foreground.orEmpty())
                writer.append('\t')
                writer.append(entry.reason.orEmpty())
                writer.newLine()
            }
        }
    }

    companion object {
        const val HEADER =
            "wallMillis\telapsedNanos\tsincePreviousNanos\tevent\tstage\tinputMode\tforeground\treason"
    }
}
