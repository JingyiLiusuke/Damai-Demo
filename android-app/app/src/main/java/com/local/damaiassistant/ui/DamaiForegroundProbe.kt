package com.local.damaiassistant.ui

class DamaiForegroundProbe(
    private val damaiPackage: String,
    private val foregroundPackage: () -> String?,
    private val isDamaiActiveWindow: () -> Boolean,
) {
    fun currentPackage(): String? =
        if (foregroundPackage() == damaiPackage || isDamaiActiveWindow()) {
            damaiPackage
        } else {
            null
        }
}
