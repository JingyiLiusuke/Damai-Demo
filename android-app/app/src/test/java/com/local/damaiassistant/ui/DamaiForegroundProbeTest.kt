package com.local.damaiassistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DamaiForegroundProbeTest {
    @Test
    fun acceptsDamaiForegroundEventEvenWhenActiveWindowPollIsNotReady() {
        val probe = DamaiForegroundProbe(
            damaiPackage = "cn.damai",
            foregroundPackage = { "cn.damai" },
            isDamaiActiveWindow = { false },
        )

        assertEquals("cn.damai", probe.currentPackage())
    }

    @Test
    fun acceptsActiveWindowWhenForegroundEventIsStale() {
        val probe = DamaiForegroundProbe(
            damaiPackage = "cn.damai",
            foregroundPackage = { null },
            isDamaiActiveWindow = { true },
        )

        assertEquals("cn.damai", probe.currentPackage())
    }

    @Test
    fun rejectsNonDamaiForegroundWhenActiveWindowIsNotDamai() {
        val probe = DamaiForegroundProbe(
            damaiPackage = "cn.damai",
            foregroundPackage = { "com.local.damaiassistant" },
            isDamaiActiveWindow = { false },
        )

        assertNull(probe.currentPackage())
    }
}
