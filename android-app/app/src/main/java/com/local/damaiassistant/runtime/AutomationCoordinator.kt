package com.local.damaiassistant.runtime

import com.local.damaiassistant.config.AutomationConfig
import com.local.damaiassistant.config.NormalizedRect
import com.local.damaiassistant.config.PixelPoint
import com.local.damaiassistant.config.StagePolicy
import com.local.damaiassistant.domain.Effect
import com.local.damaiassistant.domain.Input
import com.local.damaiassistant.domain.RunState
import com.local.damaiassistant.domain.RuntimeSnapshot
import com.local.damaiassistant.domain.Stage
import com.local.damaiassistant.domain.TicketStateMachine
import com.local.damaiassistant.domain.TriggerDeadline
import java.util.concurrent.Executor

interface RuntimeClock {
    fun wallMillis(): Long

    fun elapsedNanos(): Long
}

object SystemRuntimeClock : RuntimeClock {
    override fun wallMillis(): Long = System.currentTimeMillis()

    override fun elapsedNanos(): Long = System.nanoTime()
}

data class WindowObservation(
    val buyButtonVisible: Boolean = false,
    val submitTextVisible: Boolean = false,
    val resultTextVisible: Boolean = false,
)

interface NodeGateway {
    fun inspect(stage: Stage, resultTexts: List<String>): WindowObservation

    fun click(stage: Stage, callback: (Boolean) -> Unit): Boolean
}

interface GestureGateway {
    fun click(
        stage: Stage,
        bounds: NormalizedRect,
        point: PixelPoint?,
        callback: (Boolean) -> Unit,
    ): Boolean
}

interface VisualGateway {
    fun captureAndMatch(
        stage: Stage,
        bounds: NormalizedRect,
        threshold: Float,
        callback: (Result<PixelPoint?>) -> Unit,
    ): Boolean
}

class AutomationCoordinator(
    private val executor: Executor,
    private val clock: RuntimeClock,
    private val scheduler: AutomationScheduler,
    private val nodes: NodeGateway,
    private val gestures: GestureGateway,
    private val visuals: VisualGateway,
    private val machine: TicketStateMachine = TicketStateMachine(),
    private val publish: (RuntimeSnapshot) -> Unit = {},
    private val log: (String, String, Long, Long) -> Unit = { _, _, _, _ -> },
) {
    @Volatile
    private var currentSnapshot = RuntimeSnapshot()
    private var currentConfig: AutomationConfig? = null

    fun arm(config: AutomationConfig) {
        executor.execute {
            scheduler.cancelAll()
            currentConfig = config
            currentSnapshot = RuntimeSnapshot()
            process(Input.Arm)
            try {
                val deadline = TriggerDeadline.compute(
                    targetWallMillis = config.targetEpochMillis,
                    preTriggerOffsetMillis = config.preTriggerOffsetMillis,
                    nowWallMillis = clock.wallMillis(),
                    nowElapsedNanos = clock.elapsedNanos(),
                )
                val generation = currentSnapshot.generation
                scheduler.scheduleTrigger(deadline) {
                    executor.execute {
                        if (currentSnapshot.generation == generation) {
                            process(Input.Trigger)
                        }
                    }
                }
            } catch (exception: IllegalArgumentException) {
                process(Input.FatalError(exception.message ?: "Invalid trigger deadline"))
            }
        }
    }

    fun stop() {
        executor.execute { process(Input.Stop) }
    }

    fun serviceDisconnected() {
        executor.execute { process(Input.ServiceDisconnected) }
    }

    fun onWindowChanged(packageName: String?) {
        executor.execute {
            process(Input.ForegroundPackage(packageName))
            if (packageName == TicketStateMachine.DAMAI_PACKAGE) {
                inspectCurrentStage()
            }
        }
    }

    fun snapshot(): RuntimeSnapshot = currentSnapshot

    private fun process(input: Input) {
        val config = currentConfig ?: return
        val transition = machine.reduce(
            snapshot = currentSnapshot,
            input = input,
            config = config,
            now = clock.elapsedNanos(),
        )
        currentSnapshot = transition.snapshot
        publish(transition.snapshot)
        log(
            "state",
            "${input.javaClass.simpleName}: ${transition.snapshot.state} " +
                transition.snapshot.message,
            clock.wallMillis(),
            clock.elapsedNanos(),
        )
        transition.effects.forEach { execute(it, config) }
    }

    private fun execute(effect: Effect, config: AutomationConfig) {
        when (effect) {
            is Effect.ScheduleTrigger -> {
                val generation = currentSnapshot.generation
                scheduler.scheduleTrigger(effect.elapsedDeadlineNanos) {
                    postIfCurrent(generation, Input.Trigger)
                }
            }

            is Effect.ScheduleTick -> scheduler.scheduleAfter(effect.delayMillis) {
                postIfCurrent(effect.generation, Input.Tick)
            }

            is Effect.InspectCurrentWindow -> {
                if (currentStage() == effect.expectedStage) inspectCurrentStage()
            }

            is Effect.ClickNode -> clickNode(effect.stage)
            is Effect.ClickCoordinate -> clickCoordinate(effect, config)
            is Effect.CaptureAndMatch -> captureAndMatch(effect.stage, config)
            Effect.CancelPendingWork -> scheduler.cancelAll()
            is Effect.Publish -> publish(effect.snapshot)
        }
    }

    private fun inspectCurrentStage() {
        val config = currentConfig ?: return
        val stage = currentStage() ?: return
        val observation = nodes.inspect(stage, config.resultTexts)
        when (stage) {
            Stage.STAGE_1 -> {
                if (observation.buyButtonVisible) {
                    process(Input.FeatureObserved(Stage.STAGE_2))
                }
            }

            Stage.STAGE_2 -> {
                if (observation.submitTextVisible) {
                    process(Input.FeatureObserved(Stage.STAGE_3))
                }
            }

            Stage.STAGE_3 -> {
                if (observation.resultTextVisible) {
                    process(Input.ResultObserved)
                }
            }
        }
    }

    private fun clickNode(stage: Stage) {
        val generation = currentSnapshot.generation
        val accepted = nodes.click(stage) { succeeded ->
            postIfCurrent(
                generation,
                Input.NodeClickFinished(generation, succeeded),
            )
        }
        if (!accepted) {
            process(Input.NodeClickFinished(generation, false))
        }
    }

    private fun clickCoordinate(
        effect: Effect.ClickCoordinate,
        config: AutomationConfig,
    ) {
        val generation = currentSnapshot.generation
        val accepted = gestures.click(
            stage = effect.stage,
            bounds = config.boundsFor(effect.stage),
            point = effect.point,
        ) { succeeded ->
            postIfCurrent(
                generation,
                Input.GestureFinished(generation, succeeded),
            )
        }
        if (!accepted) {
            process(Input.GestureFinished(generation, false))
        }
    }

    private fun captureAndMatch(stage: Stage, config: AutomationConfig) {
        val generation = currentSnapshot.generation
        val accepted = visuals.captureAndMatch(
            stage = stage,
            bounds = config.boundsFor(stage),
            threshold = config.policyFor(stage).visualMatchThreshold,
        ) { result ->
            executor.execute {
                if (currentSnapshot.generation != generation) return@execute
                result.fold(
                    onSuccess = { point ->
                        process(Input.VisualFinished(generation, stage, point))
                    },
                    onFailure = { error ->
                        process(
                            Input.FatalError(
                                error.message ?: "Visual matching failed",
                            ),
                        )
                    },
                )
            }
        }
        if (!accepted) {
            process(Input.VisualFinished(generation, stage, null))
        }
    }

    private fun postIfCurrent(generation: Long, input: Input) {
        executor.execute {
            if (currentSnapshot.generation == generation) process(input)
        }
    }

    private fun currentStage(): Stage? = when (currentSnapshot.state) {
        RunState.STAGE_1_RESERVE -> Stage.STAGE_1
        RunState.STAGE_2_CONFIRM_PRICE -> Stage.STAGE_2
        RunState.STAGE_3_SUBMIT,
        RunState.DONE_PENDING_RESULT,
        -> Stage.STAGE_3

        else -> null
    }

    private fun AutomationConfig.boundsFor(stage: Stage): NormalizedRect = when (stage) {
        Stage.STAGE_1 -> stage1Rect
        Stage.STAGE_2 -> stage2Rect
        Stage.STAGE_3 -> stage3Rect
    }

    private fun AutomationConfig.policyFor(stage: Stage): StagePolicy = when (stage) {
        Stage.STAGE_1 -> stage1
        Stage.STAGE_2 -> stage2
        Stage.STAGE_3 -> stage3
    }
}
