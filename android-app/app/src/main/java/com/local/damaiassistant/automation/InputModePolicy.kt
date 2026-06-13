package com.local.damaiassistant.automation

object InputModePolicy {
    fun preferredMode(
        manufacturer: String,
        directAvailable: Boolean,
    ): InputMode {
        val directReliable = manufacturer.lowercase() !in UNRELIABLE_MANUFACTURERS
        return if (directAvailable && directReliable) {
            InputMode.DIRECT_INJECT
        } else {
            InputMode.SHELL_INPUT
        }
    }

    private val UNRELIABLE_MANUFACTURERS = setOf("huawei", "honor")
}
