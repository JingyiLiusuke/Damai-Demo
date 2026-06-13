package com.local.damaiassistant.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputFallbackChainTest {
    @Test
    fun `input mode wire codes round trip`() {
        assertEquals(InputMode.DIRECT_INJECT, InputMode.fromWireCode(1))
        assertEquals(InputMode.SHELL_INPUT, InputMode.fromWireCode(2))
        assertEquals(InputMode.UNAVAILABLE, InputMode.fromWireCode(0))
        assertEquals(InputMode.UNAVAILABLE, InputMode.fromWireCode(99))
    }

    @Test
    fun directInjectionWinsWithoutStartingShellInput() {
        var shellCalls = 0
        val chain = InputFallbackChain(
            directInject = { true },
            shellInput = {
                shellCalls += 1
                true
            },
        )

        val attempt = chain.tap()

        assertTrue(attempt.succeeded)
        assertEquals(InputMode.DIRECT_INJECT, attempt.mode)
        assertEquals(0, shellCalls)
    }

    @Test
    fun shellInputRunsAfterDirectInjectionFails() {
        val calls = mutableListOf<String>()
        val chain = InputFallbackChain(
            directInject = {
                calls += "direct"
                false
            },
            shellInput = {
                calls += "shell"
                true
            },
        )

        val attempt = chain.tap()

        assertTrue(attempt.succeeded)
        assertEquals(InputMode.SHELL_INPUT, attempt.mode)
        assertEquals(listOf("direct", "shell"), calls)
    }

    @Test
    fun unavailableIsReportedWhenEveryShizukuInputPathFails() {
        val chain = InputFallbackChain(
            directInject = { false },
            shellInput = { false },
        )

        val attempt = chain.tap()

        assertFalse(attempt.succeeded)
        assertEquals(InputMode.UNAVAILABLE, attempt.mode)
    }
}
