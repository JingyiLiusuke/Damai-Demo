package com.local.damaiassistant.ui

import com.local.damaiassistant.config.AutomationConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ArmConfigPlanTest {
    @Test
    fun immediateTestKeepsSavedTargetAndUsesTransientRuntimeTarget() {
        val savedTarget = 1_800_000_000_000L
        val now = 1_700_000_000_000L
        val config = AutomationConfig.defaults().copy(
            targetEpochMillis = savedTarget,
            preTriggerOffsetMillis = 100L,
        )

        val plan = ArmConfigPlan(
            savedConfig = config,
            immediateTest = true,
        )

        assertEquals(savedTarget, plan.savedConfig.targetEpochMillis)
        assertEquals(
            now + 100L + 500L,
            plan.runtimeConfig(now, 500L).targetEpochMillis,
        )
    }

    @Test
    fun scheduledRunUsesSavedTargetWithoutChangingIt() {
        val savedTarget = 1_800_000_000_000L
        val config = AutomationConfig.defaults().copy(
            targetEpochMillis = savedTarget,
            preTriggerOffsetMillis = 100L,
        )

        val plan = ArmConfigPlan(
            savedConfig = config,
            immediateTest = false,
        )

        assertEquals(savedTarget, plan.runtimeConfig(1_700_000_000_000L, 500L).targetEpochMillis)
        assertEquals(savedTarget, plan.savedConfig.targetEpochMillis)
    }
}
