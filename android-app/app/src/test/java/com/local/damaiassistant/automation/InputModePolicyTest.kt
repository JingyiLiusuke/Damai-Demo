package com.local.damaiassistant.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class InputModePolicyTest {
    @Test
    fun `Huawei devices prefer shell input because direct injection is not reliable`() {
        assertEquals(InputMode.SHELL_INPUT, InputModePolicy.preferredMode("HUAWEI", true))
        assertEquals(InputMode.SHELL_INPUT, InputModePolicy.preferredMode("Honor", true))
    }

    @Test
    fun `other devices use direct injection when available`() {
        assertEquals(InputMode.DIRECT_INJECT, InputModePolicy.preferredMode("Google", true))
        assertEquals(InputMode.SHELL_INPUT, InputModePolicy.preferredMode("Google", false))
    }
}
