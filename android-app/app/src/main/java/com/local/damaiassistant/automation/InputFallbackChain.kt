package com.local.damaiassistant.automation

class InputFallbackChain(
    private val directInject: () -> Boolean,
    private val shellInput: () -> Boolean,
) {
    fun tap(): InputAttempt {
        if (directInject()) {
            return InputAttempt(succeeded = true, mode = InputMode.DIRECT_INJECT)
        }
        if (shellInput()) {
            return InputAttempt(succeeded = true, mode = InputMode.SHELL_INPUT)
        }
        return InputAttempt(succeeded = false, mode = InputMode.UNAVAILABLE)
    }
}
