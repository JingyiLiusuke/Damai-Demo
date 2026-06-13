package com.local.damaiassistant.automation

enum class InputMode(
    val wireCode: Int,
) {
    DIRECT_INJECT(1),
    SHELL_INPUT(2),
    ACCESSIBILITY_GESTURE(3),
    UNAVAILABLE(0),
    ;

    companion object {
        fun fromWireCode(code: Int): InputMode =
            entries.firstOrNull { it.wireCode == code } ?: UNAVAILABLE
    }
}

data class InputAttempt(
    val succeeded: Boolean,
    val mode: InputMode,
)
