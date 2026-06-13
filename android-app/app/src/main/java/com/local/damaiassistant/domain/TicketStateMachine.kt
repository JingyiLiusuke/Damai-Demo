package com.local.damaiassistant.domain

import com.local.damaiassistant.config.AutomationConfig
import com.local.damaiassistant.config.StagePolicy

class TicketStateMachine {
    fun reduce(
        snapshot: RuntimeSnapshot,
        input: Input,
        config: AutomationConfig,
        now: Long,
    ): Transition {
        require(now >= 0L) { "Monotonic time must be nonnegative" }

        if (input.isStaleFor(snapshot)) {
            return unchanged(snapshot)
        }

        if (snapshot.state in TERMINAL_STATES) {
            return unchanged(snapshot)
        }

        when (input) {
            Input.Stop,
            Input.ServiceDisconnected,
            -> return cancel(snapshot, "Automation cancelled")

            is Input.ForegroundPackage -> {
                if (snapshot.state.isActive() && input.packageName != DAMAI_PACKAGE) {
                    return cancel(snapshot, "Damai is no longer foreground")
                }
            }

            is Input.FatalError -> {
                if (snapshot.state.isActive()) {
                    return fail(snapshot, input.reason)
                }
            }

            else -> Unit
        }

        if (input == Input.ResultObserved) {
            return if (
                snapshot.state == RunState.STAGE_3_SUBMIT ||
                snapshot.state == RunState.DONE_PENDING_RESULT
            ) {
                Transition(
                    snapshot.copy(
                        state = RunState.DONE,
                        generation = snapshot.generation + 1,
                        enteredAtNanos = now,
                        gestureInFlight = false,
                        actionPhase = ActionPhase.NONE,
                        message = "Configured result feature observed",
                    ),
                    emptyList(),
                )
            } else {
                unchanged(snapshot)
            }
        }

        if (snapshot.hasTimedOut(config, now)) {
            return fail(snapshot, "Stage timed out")
        }

        return when (snapshot.state) {
            RunState.IDLE -> reduceIdle(snapshot, input, now)
            RunState.ARMED -> reduceArmed(snapshot, input, now)
            RunState.STAGE_1_RESERVE -> reduceStage1(snapshot, input, config, now)
            RunState.STAGE_2_CONFIRM_PRICE -> reduceNodeStage(
                snapshot,
                input,
                config,
                now,
                Stage.STAGE_2,
            )

            RunState.STAGE_3_SUBMIT -> reduceNodeStage(
                snapshot,
                input,
                config,
                now,
                Stage.STAGE_3,
            )

            RunState.DONE_PENDING_RESULT -> unchanged(snapshot)
            RunState.DONE,
            RunState.FAILED,
            RunState.CANCELLED,
            -> unchanged(snapshot)
        }
    }

    private fun reduceIdle(
        snapshot: RuntimeSnapshot,
        input: Input,
        now: Long,
    ): Transition = if (input == Input.Arm) {
        Transition(
            snapshot.copy(
                state = RunState.ARMED,
                generation = snapshot.generation + 1,
                enteredAtNanos = now,
                clickCount = 0,
                screenshotCount = 0,
                gestureInFlight = false,
                lastClickAtNanos = null,
                actionPhase = ActionPhase.NONE,
                message = "Waiting for trigger",
            ),
            emptyList(),
        )
    } else {
        unchanged(snapshot)
    }

    private fun reduceArmed(
        snapshot: RuntimeSnapshot,
        input: Input,
        now: Long,
    ): Transition = if (input == Input.Trigger) {
        val next = snapshot.copy(
            state = RunState.STAGE_1_RESERVE,
            generation = snapshot.generation + 1,
            enteredAtNanos = now,
            clickCount = 1,
            screenshotCount = 0,
            gestureInFlight = true,
            lastClickAtNanos = now,
            actionPhase = ActionPhase.NONE,
            message = "Stage 1 coordinate click requested",
        )
        Transition(next, listOf(Effect.ClickCoordinate(Stage.STAGE_1)))
    } else {
        unchanged(snapshot)
    }

    private fun reduceStage1(
        snapshot: RuntimeSnapshot,
        input: Input,
        config: AutomationConfig,
        now: Long,
    ): Transition {
        if (input == Input.FeatureObserved(Stage.STAGE_2)) {
            return enterActionStage(
                snapshot,
                RunState.STAGE_2_CONFIRM_PRICE,
                Stage.STAGE_2,
                config,
                now,
            )
        }

        if (input is Input.GestureFinished) {
            if (!snapshot.gestureInFlight) return unchanged(snapshot)
            val next = snapshot.copy(gestureInFlight = false)
            return Transition(
                next,
                listOf(Effect.ScheduleTick(config.stage1.retryMillis, next.generation)),
            )
        }

        if (input != Input.Tick) return unchanged(snapshot)
        stageTerminalTransition(snapshot, config.stage1, now, terminal = false)?.let {
            return it
        }
        if (snapshot.gestureInFlight) return unchanged(snapshot)
        retryDelay(snapshot, config.stage1, now)?.let { return it }

        val next = snapshot.copy(
            clickCount = snapshot.clickCount + 1,
            gestureInFlight = true,
            lastClickAtNanos = now,
            message = "Stage 1 coordinate retry requested",
        )
        return Transition(next, listOf(Effect.ClickCoordinate(Stage.STAGE_1)))
    }

    private fun reduceNodeStage(
        snapshot: RuntimeSnapshot,
        input: Input,
        config: AutomationConfig,
        now: Long,
        stage: Stage,
    ): Transition {
        val policy = config.policyFor(stage)

        if (
            stage == Stage.STAGE_2 &&
            input == Input.FeatureObserved(Stage.STAGE_3)
        ) {
            return enterActionStage(
                snapshot,
                RunState.STAGE_3_SUBMIT,
                Stage.STAGE_3,
                config,
                now,
            )
        }

        if (input is Input.NodeClickFinished) {
            if (snapshot.actionPhase != ActionPhase.NODE_IN_FLIGHT) {
                return unchanged(snapshot)
            }
            if (input.succeeded) {
                val next = snapshot.copy(
                    actionPhase = ActionPhase.WAITING_VISUAL,
                    message = "Node click sent; waiting for next stage",
                )
                return Transition(
                    next,
                    listOf(
                        Effect.ScheduleTick(
                            config.visualFallbackDelayMillis,
                            next.generation,
                        ),
                    ),
                )
            }
            clickLimitTransition(snapshot, policy, stage == Stage.STAGE_3, now)?.let {
                return it
            }
            val next = snapshot.copy(
                clickCount = snapshot.clickCount + 1,
                gestureInFlight = true,
                lastClickAtNanos = now,
                actionPhase = ActionPhase.WAITING_VISUAL,
                message = "Node click failed; coordinate fallback requested",
            )
            return Transition(next, listOf(Effect.ClickCoordinate(stage)))
        }

        if (input is Input.GestureFinished) {
            if (!snapshot.gestureInFlight) return unchanged(snapshot)
            val next = snapshot.copy(
                gestureInFlight = false,
                actionPhase = if (
                    snapshot.actionPhase == ActionPhase.COORDINATE_IN_FLIGHT
                ) {
                    ActionPhase.READY_FOR_NODE
                } else {
                    snapshot.actionPhase
                },
            )
            return Transition(
                next,
                listOf(
                    Effect.ScheduleTick(
                        config.visualFallbackDelayMillis,
                        next.generation,
                    ),
                ),
            )
        }

        if (input is Input.VisualFinished) {
            if (
                input.stage != stage ||
                snapshot.actionPhase != ActionPhase.VISUAL_IN_FLIGHT
            ) {
                return unchanged(snapshot)
            }
            if (input.match == null) {
                val next = snapshot.copy(
                    actionPhase = ActionPhase.READY_FOR_NODE,
                    message = "Visual fallback did not match",
                )
                return Transition(
                    next,
                    listOf(Effect.ScheduleTick(policy.retryMillis, next.generation)),
                )
            }
            clickLimitTransition(snapshot, policy, stage == Stage.STAGE_3, now)?.let {
                return it
            }
            val next = snapshot.copy(
                clickCount = snapshot.clickCount + 1,
                gestureInFlight = true,
                lastClickAtNanos = now,
                actionPhase = ActionPhase.WAITING_VISUAL,
                message = "Visual match coordinate click requested",
            )
            return Transition(next, listOf(Effect.ClickCoordinate(stage, input.match)))
        }

        if (input != Input.Tick) return unchanged(snapshot)
        stageTerminalTransition(snapshot, policy, now, stage == Stage.STAGE_3)?.let {
            return it
        }
        if (snapshot.gestureInFlight) return unchanged(snapshot)
        retryDelay(snapshot, policy, now)?.let { return it }

        return when (snapshot.actionPhase) {
            ActionPhase.READY_FOR_NODE -> requestNodeClick(snapshot, stage, now)
            ActionPhase.WAITING_VISUAL -> {
                if (
                    config.visualFallbackEnabled &&
                    snapshot.screenshotCount < config.maxScreenshotsPerStage
                ) {
                    val next = snapshot.copy(
                        screenshotCount = snapshot.screenshotCount + 1,
                        actionPhase = ActionPhase.VISUAL_IN_FLIGHT,
                        message = "Visual fallback requested",
                    )
                    Transition(next, listOf(Effect.CaptureAndMatch(stage)))
                } else {
                    requestNodeClick(snapshot, stage, now)
                }
            }

            ActionPhase.NONE,
            ActionPhase.COORDINATE_IN_FLIGHT,
            ActionPhase.NODE_IN_FLIGHT,
            ActionPhase.VISUAL_IN_FLIGHT,
            -> unchanged(snapshot)
        }
    }

    private fun enterActionStage(
        snapshot: RuntimeSnapshot,
        state: RunState,
        stage: Stage,
        config: AutomationConfig,
        now: Long,
    ): Transition {
        if (!config.lowLatencyEnabled) {
            return enterNodeStage(snapshot, state, stage, now)
        }
        val next = snapshot.copy(
            state = state,
            generation = snapshot.generation + 1,
            enteredAtNanos = now,
            clickCount = 1,
            screenshotCount = 0,
            gestureInFlight = true,
            lastClickAtNanos = now,
            actionPhase = ActionPhase.COORDINATE_IN_FLIGHT,
            message = "$stage coordinate click requested",
        )
        return Transition(next, listOf(Effect.ClickCoordinate(stage)))
    }

    private fun enterNodeStage(
        snapshot: RuntimeSnapshot,
        state: RunState,
        stage: Stage,
        now: Long,
    ): Transition {
        val next = snapshot.copy(
            state = state,
            generation = snapshot.generation + 1,
            enteredAtNanos = now,
            clickCount = 1,
            screenshotCount = 0,
            gestureInFlight = false,
            lastClickAtNanos = now,
            actionPhase = ActionPhase.NODE_IN_FLIGHT,
            message = "$stage node click requested",
        )
        return Transition(next, listOf(Effect.ClickNode(stage)))
    }

    private fun requestNodeClick(
        snapshot: RuntimeSnapshot,
        stage: Stage,
        now: Long,
    ): Transition {
        val next = snapshot.copy(
            clickCount = snapshot.clickCount + 1,
            lastClickAtNanos = now,
            actionPhase = ActionPhase.NODE_IN_FLIGHT,
            message = "$stage node retry requested",
        )
        return Transition(next, listOf(Effect.ClickNode(stage)))
    }

    private fun stageTerminalTransition(
        snapshot: RuntimeSnapshot,
        policy: StagePolicy,
        now: Long,
        terminal: Boolean,
    ): Transition? {
        if (hasTimedOut(snapshot.enteredAtNanos, policy, now)) {
            return fail(snapshot, "Stage timed out")
        }
        return clickLimitTransition(snapshot, policy, terminal, now)
    }

    private fun clickLimitTransition(
        snapshot: RuntimeSnapshot,
        policy: StagePolicy,
        terminal: Boolean,
        now: Long,
    ): Transition? {
        if (snapshot.clickCount < policy.maxClicks) return null
        return if (terminal) {
            Transition(
                snapshot.copy(
                    state = RunState.DONE_PENDING_RESULT,
                    generation = snapshot.generation + 1,
                    enteredAtNanos = now,
                    gestureInFlight = false,
                    actionPhase = ActionPhase.NONE,
                    message = "Submit click limit reached; result requires confirmation",
                ),
                listOf(Effect.CancelPendingWork),
            )
        } else {
            fail(snapshot, "Stage click limit reached")
        }
    }

    private fun retryDelay(
        snapshot: RuntimeSnapshot,
        policy: StagePolicy,
        now: Long,
    ): Transition? {
        val lastClick = snapshot.lastClickAtNanos ?: return null
        if (now < lastClick) {
            return Transition(
                snapshot,
                listOf(Effect.ScheduleTick(policy.retryMillis, snapshot.generation)),
            )
        }
        val elapsedMillis = (now - lastClick) / NANOS_PER_MILLISECOND
        if (elapsedMillis >= policy.retryMillis) return null
        val remainingMillis = policy.retryMillis - elapsedMillis
        return Transition(
            snapshot,
            listOf(Effect.ScheduleTick(remainingMillis, snapshot.generation)),
        )
    }

    private fun cancel(snapshot: RuntimeSnapshot, message: String): Transition = Transition(
        snapshot.copy(
            state = RunState.CANCELLED,
            generation = snapshot.generation + 1,
            gestureInFlight = false,
            actionPhase = ActionPhase.NONE,
            message = message,
        ),
        listOf(Effect.CancelPendingWork),
    )

    private fun fail(snapshot: RuntimeSnapshot, message: String): Transition = Transition(
        snapshot.copy(
            state = RunState.FAILED,
            generation = snapshot.generation + 1,
            gestureInFlight = false,
            actionPhase = ActionPhase.NONE,
            message = message,
        ),
        listOf(Effect.CancelPendingWork),
    )

    private fun unchanged(snapshot: RuntimeSnapshot) = Transition(snapshot, emptyList())

    private fun Input.isStaleFor(snapshot: RuntimeSnapshot): Boolean = when (this) {
        is Input.GestureFinished -> generation != snapshot.generation
        is Input.NodeClickFinished -> generation != snapshot.generation
        is Input.VisualFinished -> generation != snapshot.generation
        else -> false
    }

    private fun RunState.isActive(): Boolean = this == RunState.ARMED ||
        this == RunState.STAGE_1_RESERVE ||
        this == RunState.STAGE_2_CONFIRM_PRICE ||
        this == RunState.STAGE_3_SUBMIT ||
        this == RunState.DONE_PENDING_RESULT

    private fun AutomationConfig.policyFor(stage: Stage): StagePolicy = when (stage) {
        Stage.STAGE_1 -> stage1
        Stage.STAGE_2 -> stage2
        Stage.STAGE_3 -> stage3
    }

    private fun RuntimeSnapshot.hasTimedOut(
        config: AutomationConfig,
        now: Long,
    ): Boolean = when (state) {
        RunState.STAGE_1_RESERVE -> hasTimedOut(enteredAtNanos, config.stage1, now)
        RunState.STAGE_2_CONFIRM_PRICE -> hasTimedOut(enteredAtNanos, config.stage2, now)
        RunState.STAGE_3_SUBMIT -> hasTimedOut(enteredAtNanos, config.stage3, now)
        else -> false
    }

    private fun hasTimedOut(
        enteredAtNanos: Long?,
        policy: StagePolicy,
        now: Long,
    ): Boolean {
        if (enteredAtNanos == null || now < enteredAtNanos) return false
        val elapsedMillis = (now - enteredAtNanos) / NANOS_PER_MILLISECOND
        return elapsedMillis >= policy.timeoutMillis
    }

    companion object {
        const val DAMAI_PACKAGE = "cn.damai"
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val TERMINAL_STATES = setOf(
            RunState.DONE,
            RunState.FAILED,
            RunState.CANCELLED,
        )
    }
}
