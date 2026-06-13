package com.local.damaiassistant.runtime

import com.local.damaiassistant.config.AutomationConfig
import com.local.damaiassistant.config.NormalizedRect
import com.local.damaiassistant.config.PixelPoint
import com.local.damaiassistant.domain.RunState
import com.local.damaiassistant.domain.Stage
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationCoordinatorTest {
    private val executor = QueuedExecutor()
    private val clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 5_000_000_000L)
    private val scheduler = FakeScheduler()
    private val nodes = FakeNodes()
    private val gestures = FakeGestures()
    private val visuals = FakeVisuals()
    private val published = mutableListOf<RunState>()
    private val config = AutomationConfig.defaults().copy(
        targetEpochMillis = 2_000L,
        preTriggerOffsetMillis = 100L,
    )
    private val coordinator = AutomationCoordinator(
        executor = executor,
        clock = clock,
        scheduler = scheduler,
        nodes = nodes,
        gestures = gestures,
        visuals = visuals,
        publish = { published += it.state },
    )

    @Test
    fun armSchedulesComputedMonotonicDeadline() {
        coordinator.arm(config)
        executor.runAll()

        assertEquals(5_900_000_000L, scheduler.lastDeadline)
        assertEquals(RunState.ARMED, coordinator.snapshot().state)
    }

    @Test
    fun accessibilityEventsAreSerializedAndOnlyInspectCurrentStage() {
        coordinator.arm(config)
        executor.runAll()
        scheduler.fireTrigger()
        coordinator.onWindowChanged("cn.damai")

        assertTrue(nodes.inspections.isEmpty())
        executor.runAll()

        assertEquals(RunState.STAGE_1_RESERVE, coordinator.snapshot().state)
        assertEquals(listOf(Stage.STAGE_1), nodes.inspections)
    }

    @Test
    fun observedBuyButtonAdvancesToStage2AndClicksNode() {
        nodes.observation = WindowObservation(buyButtonVisible = true)
        coordinator.arm(config)
        executor.runAll()
        scheduler.fireTrigger()
        coordinator.onWindowChanged("cn.damai")
        executor.runAll()

        assertEquals(RunState.STAGE_2_CONFIRM_PRICE, coordinator.snapshot().state)
        assertEquals(listOf(Stage.STAGE_2), nodes.clicks)
    }

    @Test
    fun stopInvalidatesPendingGestureCallback() {
        coordinator.arm(config)
        executor.runAll()
        scheduler.fireTrigger()
        executor.runAll()
        val oldCallback = gestures.callback
        assertNotNull(oldCallback)

        coordinator.stop()
        executor.runAll()
        oldCallback?.invoke(true)
        executor.runAll()

        assertEquals(RunState.CANCELLED, coordinator.snapshot().state)
        assertTrue(scheduler.cancelCount > 0)
    }

    @Test
    fun coordinateAndVisualEffectsUseStageConfiguration() {
        coordinator.arm(config)
        executor.runAll()
        scheduler.fireTrigger()
        executor.runAll()

        assertEquals(Stage.STAGE_1, gestures.stage)
        assertEquals(config.stage1Rect, gestures.bounds)

        nodes.observation = WindowObservation(buyButtonVisible = true)
        coordinator.onWindowChanged("cn.damai")
        executor.runAll()
        nodes.finishClick(false)
        executor.runAll()

        assertEquals(Stage.STAGE_2, gestures.stage)
        assertEquals(config.stage2Rect, gestures.bounds)
    }

    @Test
    fun rearmDoesNotReuseGenerationFromPreviousRun() {
        coordinator.arm(config)
        executor.runAll()
        scheduler.fireTrigger()
        executor.runAll()
        val staleCallback = gestures.callback
        val firstGeneration = coordinator.snapshot().generation

        coordinator.stop()
        executor.runAll()
        coordinator.arm(config)
        executor.runAll()
        scheduler.fireTrigger()
        executor.runAll()

        assertTrue(coordinator.snapshot().generation > firstGeneration)
        staleCallback?.invoke(true)
        executor.runAll()
        assertTrue(coordinator.snapshot().gestureInFlight)
    }

    @Test
    fun stageTimeoutIsScheduledEvenWhenGestureNeverCallsBack() {
        coordinator.arm(config)
        executor.runAll()
        scheduler.fireTrigger()
        executor.runAll()

        scheduler.fireDelay(config.stage1.timeoutMillis)
        clock.elapsedNanos += config.stage1.timeoutMillis * 1_000_000L
        executor.runAll()

        assertEquals(RunState.FAILED, coordinator.snapshot().state)
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeFirst().run()
            }
        }
    }

    private class FakeClock(
        var wallMillis: Long,
        var elapsedNanos: Long,
    ) : RuntimeClock {
        override fun wallMillis(): Long = wallMillis

        override fun elapsedNanos(): Long = elapsedNanos
    }

    private class FakeScheduler : AutomationScheduler {
        var lastDeadline: Long? = null
        var trigger: (() -> Unit)? = null
        var cancelCount = 0
        val delays = mutableListOf<Long>()
        val delayCallbacks = mutableListOf<Pair<Long, () -> Unit>>()

        override fun scheduleTrigger(deadlineNanos: Long, callback: () -> Unit) {
            lastDeadline = deadlineNanos
            trigger = callback
        }

        override fun scheduleAfter(delayMillis: Long, callback: () -> Unit) {
            delays += delayMillis
            delayCallbacks += delayMillis to callback
        }

        override fun cancelAll() {
            cancelCount += 1
            trigger = null
        }

        fun fireTrigger() {
            val current = trigger
            trigger = null
            current?.invoke()
        }

        fun fireDelay(delayMillis: Long) {
            val index = delayCallbacks.indexOfFirst { it.first == delayMillis }
            if (index >= 0) {
                delayCallbacks.removeAt(index).second.invoke()
            }
        }
    }

    private class FakeNodes : NodeGateway {
        var observation = WindowObservation()
        val inspections = mutableListOf<Stage>()
        val clicks = mutableListOf<Stage>()
        var callback: ((Boolean) -> Unit)? = null

        override fun inspect(stage: Stage, resultTexts: List<String>): WindowObservation {
            inspections += stage
            return observation
        }

        override fun click(stage: Stage, callback: (Boolean) -> Unit): Boolean {
            clicks += stage
            this.callback = callback
            return true
        }

        fun finishClick(succeeded: Boolean) {
            val current = callback
            callback = null
            current?.invoke(succeeded)
        }
    }

    private class FakeGestures : GestureGateway {
        var stage: Stage? = null
        var bounds: NormalizedRect? = null
        var point: PixelPoint? = null
        var callback: ((Boolean) -> Unit)? = null
        var clearPendingCount = 0

        override fun click(
            stage: Stage,
            bounds: NormalizedRect,
            point: PixelPoint?,
            callback: (Boolean) -> Unit,
        ): Boolean {
            this.stage = stage
            this.bounds = bounds
            this.point = point
            this.callback = callback
            return true
        }

        override fun clearPending() {
            clearPendingCount += 1
        }
    }

    private class FakeVisuals : VisualGateway {
        override fun captureAndMatch(
            stage: Stage,
            bounds: NormalizedRect,
            threshold: Float,
            callback: (Result<PixelPoint?>) -> Unit,
        ): Boolean = true
    }
}
