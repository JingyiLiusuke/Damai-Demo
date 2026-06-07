package com.local.damaiassistant.ui

import java.util.PriorityQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPackageAwaiterTest {
    @Test
    fun waitsForLateDamaiEventAndStableForeground() {
        val scheduler = FakeScheduler()
        var foregroundPackage: String? = "com.local.damaiassistant"
        var ready = false
        var timedOut = false
        val awaiter = ForegroundPackageAwaiter(
            currentPackage = { foregroundPackage },
            nowMillis = scheduler::nowMillis,
            schedule = scheduler::schedule,
        )

        awaiter.await(
            expectedPackage = "cn.damai",
            timeoutMillis = 5_000L,
            pollIntervalMillis = 100L,
            stableMillis = 250L,
            onReady = { ready = true },
            onTimeout = { timedOut = true },
        )

        scheduler.advanceTo(800L)
        assertFalse(ready)
        foregroundPackage = "cn.damai"
        scheduler.advanceTo(1_000L)
        assertFalse(ready)
        scheduler.advanceTo(1_100L)
        assertFalse(ready)
        scheduler.advanceTo(1_200L)

        assertTrue(ready)
        assertFalse(timedOut)
    }

    @Test
    fun timesOutWhenDamaiNeverBecomesForeground() {
        val scheduler = FakeScheduler()
        var ready = false
        var timeoutPackage: String? = null
        val awaiter = ForegroundPackageAwaiter(
            currentPackage = { "com.huawei.android.launcher" },
            nowMillis = scheduler::nowMillis,
            schedule = scheduler::schedule,
        )

        awaiter.await(
            expectedPackage = "cn.damai",
            timeoutMillis = 500L,
            pollIntervalMillis = 100L,
            stableMillis = 250L,
            onReady = { ready = true },
            onTimeout = { timeoutPackage = it },
        )
        scheduler.advanceTo(500L)

        assertFalse(ready)
        assertEquals("com.huawei.android.launcher", timeoutPackage)
    }

    @Test
    fun cancelledWaitDoesNotInvokeReadyOrTimeout() {
        val scheduler = FakeScheduler()
        var foregroundPackage: String? = null
        var ready = false
        var timedOut = false
        val awaiter = ForegroundPackageAwaiter(
            currentPackage = { foregroundPackage },
            nowMillis = scheduler::nowMillis,
            schedule = scheduler::schedule,
        )

        val handle = awaiter.await(
            expectedPackage = "cn.damai",
            timeoutMillis = 500L,
            pollIntervalMillis = 100L,
            stableMillis = 100L,
            onReady = { ready = true },
            onTimeout = { timedOut = true },
        )
        handle.cancel()
        foregroundPackage = "cn.damai"
        scheduler.advanceTo(500L)

        assertFalse(ready)
        assertFalse(timedOut)
    }

    private class FakeScheduler {
        private data class Task(
            val atMillis: Long,
            val sequence: Long,
            val action: () -> Unit,
        ) : Comparable<Task> {
            override fun compareTo(other: Task): Int =
                compareValuesBy(this, other, Task::atMillis, Task::sequence)
        }

        private val tasks = PriorityQueue<Task>()
        private var sequence = 0L
        private var currentMillis = 0L

        fun nowMillis(): Long = currentMillis

        fun schedule(delayMillis: Long, action: () -> Unit) {
            tasks += Task(currentMillis + delayMillis, sequence++, action)
        }

        fun advanceTo(targetMillis: Long) {
            while (tasks.isNotEmpty() && tasks.peek()!!.atMillis <= targetMillis) {
                val task = tasks.remove()
                currentMillis = task.atMillis
                task.action()
            }
            currentMillis = targetMillis
        }
    }
}
