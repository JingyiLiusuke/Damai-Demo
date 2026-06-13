package com.local.damaiassistant.domain

import com.local.damaiassistant.config.AutomationConfig
import com.local.damaiassistant.config.PixelPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketStateMachineTest {
    private val machine = TicketStateMachine()
    private val config = AutomationConfig.defaults()

    @Test
    fun armMovesIdleRunToArmed() {
        val transition = machine.reduce(RuntimeSnapshot(), Input.Arm, config, now = 5L)

        assertEquals(RunState.ARMED, transition.snapshot.state)
        assertEquals(1L, transition.snapshot.generation)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun triggerStartsStage1WithImmediateCoordinateClick() {
        val transition = machine.reduce(armedSnapshot(), Input.Trigger, config, now = 10L)

        assertEquals(RunState.STAGE_1_RESERVE, transition.snapshot.state)
        assertEquals(1, transition.snapshot.clickCount)
        assertTrue(transition.snapshot.gestureInFlight)
        assertEquals(listOf(Effect.ClickCoordinate(Stage.STAGE_1)), transition.effects)
    }

    @Test
    fun stage1OnlyAdvancesWhenStage2FeatureIsObserved() {
        val ignored = machine.reduce(
            stage1Snapshot(),
            Input.FeatureObserved(Stage.STAGE_3),
            config,
            19L,
        )
        assertEquals(RunState.STAGE_1_RESERVE, ignored.snapshot.state)
        assertTrue(ignored.effects.isEmpty())

        val transition = machine.reduce(
            stage1Snapshot(),
            Input.FeatureObserved(Stage.STAGE_2),
            config,
            20L,
        )

        assertEquals(RunState.STAGE_2_CONFIRM_PRICE, transition.snapshot.state)
        assertTrue(transition.snapshot.gestureInFlight)
        assertEquals(ActionPhase.COORDINATE_IN_FLIGHT, transition.snapshot.actionPhase)
        assertEquals(Effect.ClickCoordinate(Stage.STAGE_2), transition.effects.first())
    }

    @Test
    fun stage2AdvancesOnlyWhenStage3FeatureIsObserved() {
        val ignored = machine.reduce(
            stage2Snapshot(),
            Input.FeatureObserved(Stage.STAGE_2),
            config,
            20L,
        )
        assertEquals(RunState.STAGE_2_CONFIRM_PRICE, ignored.snapshot.state)

        val advanced = machine.reduce(
            stage2Snapshot(),
            Input.FeatureObserved(Stage.STAGE_3),
            config,
            21L,
        )
        assertEquals(RunState.STAGE_3_SUBMIT, advanced.snapshot.state)
        assertTrue(advanced.snapshot.gestureInFlight)
        assertEquals(ActionPhase.COORDINATE_IN_FLIGHT, advanced.snapshot.actionPhase)
        assertEquals(listOf(Effect.ClickCoordinate(Stage.STAGE_3)), advanced.effects)
    }

    @Test
    fun coordinateFirstStageDelaysNodeRecovery() {
        val snapshot = stage2Snapshot(
            generation = 8,
            phase = ActionPhase.COORDINATE_IN_FLIGHT,
            gestureInFlight = true,
            clicks = 1,
        )

        val completed = machine.reduce(
            snapshot,
            Input.GestureFinished(generation = 8, succeeded = true),
            config,
            100L,
        )

        assertFalse(completed.snapshot.gestureInFlight)
        assertEquals(ActionPhase.READY_FOR_NODE, completed.snapshot.actionPhase)
        assertEquals(
            listOf(Effect.ScheduleTick(config.visualFallbackDelayMillis, 8)),
            completed.effects,
        )
    }

    @Test
    fun staleCallbacksCannotChangeNewGeneration() {
        val snapshot = stage2Snapshot(generation = 4)
        val inputs = listOf<Input>(
            Input.GestureFinished(generation = 3, succeeded = true),
            Input.NodeClickFinished(generation = 3, succeeded = false),
            Input.VisualFinished(
                generation = 3,
                stage = Stage.STAGE_2,
                match = PixelPoint(1, 2),
            ),
        )

        inputs.forEach { input ->
            val transition = machine.reduce(snapshot, input, config, 30L)
            assertEquals(snapshot, transition.snapshot)
            assertTrue(transition.effects.isEmpty())
        }
    }

    @Test
    fun failedNodeClickFallsBackToCoordinate() {
        val snapshot = stage2Snapshot(
            generation = 8,
            phase = ActionPhase.NODE_IN_FLIGHT,
            clicks = 1,
        )

        val transition = machine.reduce(
            snapshot,
            Input.NodeClickFinished(generation = 8, succeeded = false),
            config,
            100L,
        )

        assertEquals(2, transition.snapshot.clickCount)
        assertTrue(transition.snapshot.gestureInFlight)
        assertEquals(ActionPhase.WAITING_VISUAL, transition.snapshot.actionPhase)
        assertEquals(listOf(Effect.ClickCoordinate(Stage.STAGE_2)), transition.effects)
    }

    @Test
    fun successfulNodeClickWaitsThenRequestsVisualFallback() {
        val snapshot = stage2Snapshot(
            generation = 8,
            phase = ActionPhase.NODE_IN_FLIGHT,
            clicks = 1,
        )
        val completed = machine.reduce(
            snapshot,
            Input.NodeClickFinished(generation = 8, succeeded = true),
            config,
            100L,
        )
        assertEquals(ActionPhase.WAITING_VISUAL, completed.snapshot.actionPhase)
        assertEquals(
            listOf(Effect.ScheduleTick(config.visualFallbackDelayMillis, 8)),
            completed.effects,
        )

        val tick = machine.reduce(
            completed.snapshot,
            Input.Tick,
            config,
            now = 100L + config.visualFallbackDelayMillis * 1_000_000L,
        )
        assertEquals(ActionPhase.VISUAL_IN_FLIGHT, tick.snapshot.actionPhase)
        assertEquals(1, tick.snapshot.screenshotCount)
        assertEquals(listOf(Effect.CaptureAndMatch(Stage.STAGE_2)), tick.effects)
    }

    @Test
    fun visualMatchClicksMatchedPointAndNoMatchRetriesNode() {
        val matchingSnapshot = stage2Snapshot(
            generation = 9,
            phase = ActionPhase.VISUAL_IN_FLIGHT,
            clicks = 1,
            screenshots = 1,
        )
        val point = PixelPoint(50, 60)

        val matched = machine.reduce(
            matchingSnapshot,
            Input.VisualFinished(9, Stage.STAGE_2, point),
            config,
            200L,
        )
        assertEquals(2, matched.snapshot.clickCount)
        assertTrue(matched.snapshot.gestureInFlight)
        assertEquals(listOf(Effect.ClickCoordinate(Stage.STAGE_2, point)), matched.effects)

        val noMatch = machine.reduce(
            matchingSnapshot,
            Input.VisualFinished(9, Stage.STAGE_2, null),
            config,
            200L,
        )
        assertEquals(ActionPhase.READY_FOR_NODE, noMatch.snapshot.actionPhase)
        assertEquals(
            listOf(Effect.ScheduleTick(config.stage2.retryMillis, 9)),
            noMatch.effects,
        )
    }

    @Test
    fun screenshotLimitPreventsAdditionalCaptureAndRetriesNode() {
        val snapshot = stage2Snapshot(
            generation = 5,
            phase = ActionPhase.WAITING_VISUAL,
            clicks = 1,
            screenshots = config.maxScreenshotsPerStage,
            lastClickAtNanos = null,
        )

        val transition = machine.reduce(snapshot, Input.Tick, config, 1_000_000_000L)

        assertEquals(config.maxScreenshotsPerStage, transition.snapshot.screenshotCount)
        assertEquals(2, transition.snapshot.clickCount)
        assertEquals(ActionPhase.NODE_IN_FLIGHT, transition.snapshot.actionPhase)
        assertEquals(listOf(Effect.ClickNode(Stage.STAGE_2)), transition.effects)
    }

    @Test
    fun stageSpecificRetryDelayIsEnforced() {
        val snapshot = stage2Snapshot(
            generation = 6,
            phase = ActionPhase.READY_FOR_NODE,
            lastClickAtNanos = 1_000_000_000L,
        )
        val now = 1_025_000_000L

        val transition = machine.reduce(snapshot, Input.Tick, config, now)

        assertEquals(snapshot, transition.snapshot)
        assertEquals(
            listOf(Effect.ScheduleTick(config.stage2.retryMillis - 25L, 6)),
            transition.effects,
        )
    }

    @Test
    fun gestureCompletionClearsInFlightAndSchedulesRetry() {
        val snapshot = stage1Snapshot(
            generation = 3,
            gestureInFlight = true,
            lastClickAtNanos = 100L,
        )

        val completed = machine.reduce(
            snapshot,
            Input.GestureFinished(3, succeeded = false),
            config,
            110L,
        )

        assertFalse(completed.snapshot.gestureInFlight)
        assertEquals(
            listOf(Effect.ScheduleTick(config.stage1.retryMillis, 3)),
            completed.effects,
        )
    }

    @Test
    fun duplicateTickDoesNothingWhileGestureIsInFlight() {
        val snapshot = stage1Snapshot(gestureInFlight = true)

        val transition = machine.reduce(snapshot, Input.Tick, config, 100L)

        assertEquals(snapshot, transition.snapshot)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun stage3ClickLimitStopsAtPendingResult() {
        val snapshot = stage3Snapshot(clicks = config.stage3.maxClicks)

        val transition = machine.reduce(snapshot, Input.Tick, config, 40L)

        assertEquals(RunState.DONE_PENDING_RESULT, transition.snapshot.state)
        assertTrue(transition.effects.none { it is Effect.ClickCoordinate })
    }

    @Test
    fun nonterminalClickLimitFailsRun() {
        val snapshot = stage1Snapshot(clicks = config.stage1.maxClicks)

        val transition = machine.reduce(snapshot, Input.Tick, config, 40L)

        assertEquals(RunState.FAILED, transition.snapshot.state)
        assertEquals(listOf(Effect.CancelPendingWork), transition.effects)
    }

    @Test
    fun timeoutFailsActiveStage() {
        val snapshot = stage2Snapshot(enteredAtNanos = 100L)
        val now = 100L + config.stage2.timeoutMillis * 1_000_000L

        val transition = machine.reduce(snapshot, Input.Tick, config, now)

        assertEquals(RunState.FAILED, transition.snapshot.state)
        assertEquals(listOf(Effect.CancelPendingWork), transition.effects)
    }

    @Test
    fun featureObservedAfterTimeoutCannotAdvanceStage() {
        val snapshot = stage1Snapshot(enteredAtNanos = 100L)
        val now = 100L + config.stage1.timeoutMillis * 1_000_000L

        val transition = machine.reduce(
            snapshot,
            Input.FeatureObserved(Stage.STAGE_2),
            config,
            now,
        )

        assertEquals(RunState.FAILED, transition.snapshot.state)
    }

    @Test
    fun lateNodeCallbackCannotStartAnotherFallbackClick() {
        val snapshot = stage2Snapshot(
            generation = 7,
            enteredAtNanos = 100L,
            phase = ActionPhase.NODE_IN_FLIGHT,
        )
        val now = 100L + config.stage2.timeoutMillis * 1_000_000L

        val transition = machine.reduce(
            snapshot,
            Input.NodeClickFinished(7, succeeded = false),
            config,
            now,
        )

        assertEquals(RunState.FAILED, transition.snapshot.state)
        assertEquals(listOf(Effect.CancelPendingWork), transition.effects)
    }

    @Test
    fun resultDetectionIsRequiredForDone() {
        val pending = RuntimeSnapshot(
            state = RunState.DONE_PENDING_RESULT,
            generation = 10,
        )

        val unchanged = machine.reduce(pending, Input.Tick, config, 50L)
        assertEquals(RunState.DONE_PENDING_RESULT, unchanged.snapshot.state)

        val done = machine.reduce(pending, Input.ResultObserved, config, 51L)
        assertEquals(RunState.DONE, done.snapshot.state)
    }

    @Test
    fun stopServiceDisconnectAndPackageChangeCancelActiveRun() {
        val inputs = listOf<Input>(
            Input.Stop,
            Input.ServiceDisconnected,
            Input.ForegroundPackage("other.app"),
            Input.ForegroundPackage(null),
        )

        inputs.forEach { input ->
            val transition = machine.reduce(stage1Snapshot(), input, config, 50L)
            assertEquals(RunState.CANCELLED, transition.snapshot.state)
            assertEquals(listOf(Effect.CancelPendingWork), transition.effects)
        }
    }

    @Test
    fun damaiPackageAndDuplicateArmDoNotChangeActiveRun() {
        val snapshot = stage1Snapshot()

        listOf<Input>(
            Input.ForegroundPackage(TicketStateMachine.DAMAI_PACKAGE),
            Input.Arm,
        ).forEach { input ->
            val transition = machine.reduce(snapshot, input, config, 50L)
            assertEquals(snapshot, transition.snapshot)
            assertTrue(transition.effects.isEmpty())
        }
    }

    @Test
    fun fatalErrorFailsActiveRun() {
        val transition = machine.reduce(
            stage2Snapshot(),
            Input.FatalError("screenshot failed"),
            config,
            50L,
        )

        assertEquals(RunState.FAILED, transition.snapshot.state)
        assertEquals("screenshot failed", transition.snapshot.message)
        assertEquals(listOf(Effect.CancelPendingWork), transition.effects)
    }

    private fun armedSnapshot() = RuntimeSnapshot(
        state = RunState.ARMED,
        generation = 1,
        enteredAtNanos = 1L,
    )

    private fun stage1Snapshot(
        generation: Long = 2,
        enteredAtNanos: Long = 0L,
        clicks: Int = 1,
        gestureInFlight: Boolean = false,
        lastClickAtNanos: Long? = null,
    ) = RuntimeSnapshot(
        state = RunState.STAGE_1_RESERVE,
        generation = generation,
        enteredAtNanos = enteredAtNanos,
        clickCount = clicks,
        gestureInFlight = gestureInFlight,
        lastClickAtNanos = lastClickAtNanos,
    )

    private fun stage2Snapshot(
        generation: Long = 3,
        enteredAtNanos: Long = 0L,
        clicks: Int = 1,
        screenshots: Int = 0,
        phase: ActionPhase = ActionPhase.NODE_IN_FLIGHT,
        lastClickAtNanos: Long? = null,
        gestureInFlight: Boolean = false,
    ) = RuntimeSnapshot(
        state = RunState.STAGE_2_CONFIRM_PRICE,
        generation = generation,
        enteredAtNanos = enteredAtNanos,
        clickCount = clicks,
        screenshotCount = screenshots,
        actionPhase = phase,
        lastClickAtNanos = lastClickAtNanos,
        gestureInFlight = gestureInFlight,
    )

    private fun stage3Snapshot(
        generation: Long = 4,
        enteredAtNanos: Long = 0L,
        clicks: Int = 1,
        screenshots: Int = 0,
        phase: ActionPhase = ActionPhase.NODE_IN_FLIGHT,
    ) = RuntimeSnapshot(
        state = RunState.STAGE_3_SUBMIT,
        generation = generation,
        enteredAtNanos = enteredAtNanos,
        clickCount = clicks,
        screenshotCount = screenshots,
        actionPhase = phase,
    )
}
