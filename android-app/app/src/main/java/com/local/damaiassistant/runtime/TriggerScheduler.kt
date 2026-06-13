package com.local.damaiassistant.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.math.min

interface AutomationScheduler {
    fun scheduleTrigger(deadlineNanos: Long, callback: () -> Unit)

    fun scheduleAfter(delayMillis: Long, callback: () -> Unit)

    fun cancelAll()
}

class TriggerScheduler(
    private val nanoTime: () -> Long = System::nanoTime,
) : AutomationScheduler, AutoCloseable {
    private val token = AtomicLong()
    private val threads = ConcurrentHashMap.newKeySet<Thread>()

    override fun scheduleTrigger(deadlineNanos: Long, callback: () -> Unit) {
        require(deadlineNanos >= 0L) { "Trigger deadline must be nonnegative" }
        val expectedToken = token.incrementAndGet()
        interruptWorkers()
        launch("trigger") {
            waitUntil(deadlineNanos, expectedToken)
            if (token.get() == expectedToken) callback()
        }
    }

    override fun scheduleAfter(delayMillis: Long, callback: () -> Unit) {
        require(delayMillis >= 0L) { "Delay must be nonnegative" }
        val expectedToken = token.get()
        val deadline = saturatingAdd(
            nanoTime(),
            saturatingMultiply(delayMillis, NANOS_PER_MILLISECOND),
        )
        launch("delay") {
            waitUntil(deadline, expectedToken)
            if (token.get() == expectedToken) callback()
        }
    }

    override fun cancelAll() {
        token.incrementAndGet()
        interruptWorkers()
    }

    private fun launch(kind: String, block: () -> Unit) {
        lateinit var thread: Thread
        thread = Thread(
            {
                try {
                    block()
                } finally {
                    threads.remove(thread)
                }
            },
            "$THREAD_NAME-$kind",
        ).apply { isDaemon = true }
        threads += thread
        thread.start()
    }

    private fun interruptWorkers() {
        threads.forEach(Thread::interrupt)
    }

    private fun waitUntil(deadlineNanos: Long, expectedToken: Long) {
        var remaining = deadlineNanos - nanoTime()
        if (remaining > SPIN_WINDOW_NANOS) {
            val sleepMillis = (remaining - SPIN_WINDOW_NANOS) / NANOS_PER_MILLISECOND
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                if (token.get() != expectedToken) return
            }
        }

        while (token.get() == expectedToken) {
            remaining = deadlineNanos - nanoTime()
            if (remaining <= 0L) return
            LockSupport.parkNanos(min(remaining, MAX_PARK_NANOS))
            if (Thread.interrupted() && token.get() != expectedToken) return
        }
    }

    override fun close() {
        cancelAll()
    }

    private fun saturatingMultiply(left: Long, right: Long): Long =
        try {
            Math.multiplyExact(left, right)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

    private fun saturatingAdd(left: Long, right: Long): Long =
        try {
            Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

    private companion object {
        const val THREAD_NAME = "damai-trigger"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val SPIN_WINDOW_NANOS = 2_000_000_000L
        const val MAX_PARK_NANOS = 1_000_000L
    }
}
