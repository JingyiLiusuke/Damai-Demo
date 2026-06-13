package com.local.damaiassistant.ui

import com.local.damaiassistant.config.AutomationConfig

data class ArmConfigPlan(
    val savedConfig: AutomationConfig,
    val immediateTest: Boolean,
) {
    fun runtimeConfig(
        nowMillis: Long,
        immediateDelayMillis: Long,
    ): AutomationConfig {
        require(nowMillis >= 0L) { "Current time must be nonnegative" }
        require(immediateDelayMillis >= 0L) { "Immediate delay must be nonnegative" }
        if (!immediateTest) return savedConfig

        val runtimeTarget = try {
            Math.addExact(
                Math.addExact(nowMillis, savedConfig.preTriggerOffsetMillis),
                immediateDelayMillis,
            )
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException("Immediate trigger time is outside the supported range", exception)
        }
        return savedConfig.copy(targetEpochMillis = runtimeTarget)
    }
}
