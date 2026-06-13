package com.local.damaiassistant.domain

object TriggerDeadline {
    private const val NANOS_PER_MILLISECOND = 1_000_000L

    fun compute(
        targetWallMillis: Long,
        preTriggerOffsetMillis: Long,
        nowWallMillis: Long,
        nowElapsedNanos: Long,
    ): Long {
        require(targetWallMillis >= 0L) { "Target wall time must be nonnegative" }
        require(preTriggerOffsetMillis >= 0L) { "Pre-trigger offset must be nonnegative" }
        require(nowWallMillis >= 0L) { "Current wall time must be nonnegative" }
        require(nowElapsedNanos >= 0L) { "Current elapsed time must be nonnegative" }

        try {
            val triggerWallMillis = Math.subtractExact(
                targetWallMillis,
                preTriggerOffsetMillis,
            )
            val remainingMillis = Math.subtractExact(triggerWallMillis, nowWallMillis)
            require(remainingMillis > 0L) { "Trigger time must be in the future" }
            return Math.addExact(
                nowElapsedNanos,
                Math.multiplyExact(remainingMillis, NANOS_PER_MILLISECOND),
            )
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException("Trigger deadline is outside the supported range", exception)
        }
    }
}
