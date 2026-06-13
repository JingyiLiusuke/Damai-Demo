package com.local.damaiassistant.debug

import com.local.damaiassistant.config.AutomationConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCaptureManagerTest {
    @Test
    fun configSummaryExcludesResultDetectionText() {
        val config = AutomationConfig.defaults().copy(
            resultTexts = listOf("secret-result-marker"),
        )

        val summary = DebugCaptureManager.formatConfig(config)

        assertFalse(summary.contains("secret-result-marker"))
        assertTrue(summary.contains("stage1Rect="))
        assertTrue(summary.contains("visualFallbackEnabled="))
    }

    @Test
    fun nodeTextIsBoundedAndSensitiveDigitsAreRedacted() {
        val raw = "order 123456789 " + "x".repeat(100)

        val sanitized = DebugCaptureManager.sanitizeNodeText(raw)

        assertFalse(sanitized.contains("123456789"))
        assertTrue(sanitized.contains("[REDACTED]"))
        assertTrue(sanitized.length <= 80)
    }
}
