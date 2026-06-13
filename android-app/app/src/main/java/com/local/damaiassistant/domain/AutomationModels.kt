package com.local.damaiassistant.domain

import com.local.damaiassistant.config.PixelPoint

enum class RunState {
    IDLE,
    ARMED,
    STAGE_1_RESERVE,
    STAGE_2_CONFIRM_PRICE,
    STAGE_3_SUBMIT,
    DONE_PENDING_RESULT,
    DONE,
    FAILED,
    CANCELLED,
}

enum class Stage {
    STAGE_1,
    STAGE_2,
    STAGE_3,
}

enum class ActionPhase {
    NONE,
    COORDINATE_IN_FLIGHT,
    READY_FOR_NODE,
    NODE_IN_FLIGHT,
    WAITING_VISUAL,
    VISUAL_IN_FLIGHT,
}

data class RuntimeSnapshot(
    val state: RunState = RunState.IDLE,
    val generation: Long = 0,
    val enteredAtNanos: Long = 0,
    val clickCount: Int = 0,
    val screenshotCount: Int = 0,
    val gestureInFlight: Boolean = false,
    val lastClickAtNanos: Long? = null,
    val actionPhase: ActionPhase = ActionPhase.NONE,
    val message: String = "",
)

sealed interface Input {
    data object Arm : Input
    data object Trigger : Input
    data object Tick : Input
    data object Stop : Input
    data object ServiceDisconnected : Input
    data class ForegroundPackage(val packageName: String?) : Input
    data class FeatureObserved(val stage: Stage) : Input
    data class GestureFinished(val generation: Long, val succeeded: Boolean) : Input
    data class NodeClickFinished(val generation: Long, val succeeded: Boolean) : Input
    data class VisualFinished(
        val generation: Long,
        val stage: Stage,
        val match: PixelPoint?,
    ) : Input

    data object ResultObserved : Input
    data class FatalError(val reason: String) : Input
}

sealed interface Effect {
    data class ScheduleTrigger(val elapsedDeadlineNanos: Long) : Effect
    data class ScheduleTick(val delayMillis: Long, val generation: Long) : Effect
    data class InspectCurrentWindow(val expectedStage: Stage) : Effect
    data class ClickNode(val stage: Stage) : Effect
    data class ClickCoordinate(val stage: Stage, val point: PixelPoint? = null) : Effect
    data class CaptureAndMatch(val stage: Stage) : Effect
    data object CancelPendingWork : Effect
    data class Publish(val snapshot: RuntimeSnapshot) : Effect
}

data class Transition(
    val snapshot: RuntimeSnapshot,
    val effects: List<Effect>,
)
