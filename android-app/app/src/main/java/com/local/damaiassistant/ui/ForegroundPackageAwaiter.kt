package com.local.damaiassistant.ui

class ForegroundAwaitHandle internal constructor() {
    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    internal fun isCancelled(): Boolean = cancelled
}

class ForegroundPackageAwaiter(
    private val currentPackage: () -> String?,
    private val nowMillis: () -> Long,
    private val schedule: (delayMillis: Long, action: () -> Unit) -> Unit,
) {
    fun await(
        expectedPackage: String,
        timeoutMillis: Long,
        pollIntervalMillis: Long,
        stableMillis: Long,
        onReady: () -> Unit,
        onTimeout: (lastPackage: String?) -> Unit,
    ): ForegroundAwaitHandle {
        val handle = ForegroundAwaitHandle()
        val startedAt = nowMillis()
        var observedAt: Long? = null

        fun check() {
            if (handle.isCancelled()) return
            val now = nowMillis()
            val foregroundPackage = currentPackage()
            if (foregroundPackage == expectedPackage) {
                val firstObservedAt = observedAt ?: now.also { observedAt = it }
                if (now - firstObservedAt >= stableMillis) {
                    if (handle.isCancelled()) return
                    onReady()
                    return
                }
            } else {
                observedAt = null
            }

            val elapsed = now - startedAt
            if (elapsed >= timeoutMillis) {
                if (handle.isCancelled()) return
                onTimeout(foregroundPackage)
                return
            }
            schedule(minOf(pollIntervalMillis, timeoutMillis - elapsed), ::check)
        }

        check()
        return handle
    }
}
