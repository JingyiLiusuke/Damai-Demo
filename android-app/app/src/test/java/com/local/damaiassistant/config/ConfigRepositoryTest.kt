package com.local.damaiassistant.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfigRepositoryTest {
    @Test
    fun normalizedRectClampsEdgesWhenConvertingToPixels() {
        val rect = NormalizedRect(0.75f, 0.90f, 1.10f, 1.20f)

        assertEquals(PixelRect(750, 1800, 1000, 2000), rect.toPixels(1000, 2000))
    }

    @Test
    fun pixelRectReturnsIntegerCenter() {
        assertEquals(PixelPoint(25, 40), PixelRect(10, 20, 40, 60).center())
    }

    @Test
    fun normalizedRectRejectsNonFiniteOrUnorderedEdges() {
        val invalidRects = listOf(
            { NormalizedRect(Float.NaN, 0f, 1f, 1f) },
            { NormalizedRect(0f, Float.POSITIVE_INFINITY, 1f, 1f) },
            { NormalizedRect(0f, 0f, Float.NEGATIVE_INFINITY, 1f) },
            { NormalizedRect(0f, 0f, 1f, Float.NaN) },
            { NormalizedRect(1f, 0f, 1f, 1f) },
            { NormalizedRect(0f, 1f, 1f, 1f) },
        )

        invalidRects.forEach { createRect ->
            assertThrows(IllegalArgumentException::class.java) { createRect() }
        }
    }

    @Test
    fun normalizedRectRejectsInvalidPixelDimensions() {
        val rect = NormalizedRect(0f, 0f, 1f, 1f)

        assertThrows(IllegalArgumentException::class.java) { rect.toPixels(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { rect.toPixels(100, -1) }
    }

    @Test
    fun normalizedRectRejectsGeometryCollapsedByClamping() {
        val rect = NormalizedRect(-0.5f, 0.2f, 0f, 0.8f)

        assertThrows(IllegalArgumentException::class.java) { rect.toPixels(100, 100) }
    }

    @Test
    fun pixelRectRejectsInvalidGeometry() {
        assertThrows(IllegalArgumentException::class.java) { PixelRect(10, 0, 10, 20) }
        assertThrows(IllegalArgumentException::class.java) { PixelRect(0, 20, 10, 20) }
    }

    @Test
    fun defaultsUseApprovedFirstVersionValues() {
        val config = AutomationConfig.defaults()
        val placeholder = NormalizedRect(0.55f, 0.80f, 0.95f, 0.95f)

        assertEquals(0L, config.targetEpochMillis)
        assertEquals(100L, config.preTriggerOffsetMillis)
        assertEquals(placeholder, config.stage1Rect)
        assertEquals(placeholder, config.stage2Rect)
        assertEquals(placeholder, config.stage3Rect)
        assertEquals(StagePolicy(18000L, 80L, 12, 0.90f), config.stage1)
        assertEquals(StagePolicy(15000L, 100L, 8, 0.90f), config.stage2)
        assertEquals(StagePolicy(15000L, 120L, 4, 0.90f), config.stage3)
        assertEquals(400L, config.screenshotMinIntervalMillis)
        assertEquals(3, config.maxScreenshotsPerStage)
        assertEquals(listOf("支付", "订单", "提交成功"), config.resultTexts)
        assertEquals(true, config.visualFallbackEnabled)
    }

    @Test
    fun stagePolicyRejectsInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) {
            StagePolicy(0L, 1L, 1, 0.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StagePolicy(1L, 0L, 1, 0.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StagePolicy(1L, 1L, 0, 0.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StagePolicy(1L, 1L, 1, 1.01f)
        }
    }

    @Test
    fun automationConfigNormalizesResultTextsAndCopiesInput() {
        val resultTexts = mutableListOf("  Pay  ", "", " Order ")

        val config = customConfig(resultTexts = resultTexts)
        resultTexts[0] = "Changed"

        assertEquals(listOf("Pay", "Order"), config.resultTexts)
    }

    @Test
    fun automationConfigResultTextsCannotBeMutatedExternally() {
        val defaults = AutomationConfig.defaults()
        val suppliedResultTexts = mutableListOf("Pay", "Order")
        val config = AutomationConfig(
            targetEpochMillis = defaults.targetEpochMillis,
            preTriggerOffsetMillis = defaults.preTriggerOffsetMillis,
            stage1Rect = defaults.stage1Rect,
            stage2Rect = defaults.stage2Rect,
            stage3Rect = defaults.stage3Rect,
            stage1 = defaults.stage1,
            stage2 = defaults.stage2,
            stage3 = defaults.stage3,
            screenshotMinIntervalMillis = defaults.screenshotMinIntervalMillis,
            maxScreenshotsPerStage = defaults.maxScreenshotsPerStage,
            normalizedResultTexts = suppliedResultTexts,
            visualFallbackEnabled = defaults.visualFallbackEnabled,
        )

        suppliedResultTexts[0] = " "
        runCatching {
            @Suppress("UNCHECKED_CAST")
            (config.resultTexts as MutableList<String>).add("")
        }

        assertEquals(listOf("Pay", "Order"), config.resultTexts)
    }

    @Test
    fun automationConfigCopySupportsPlanUpdates() {
        val updatedRect = NormalizedRect(0.63f, 0.86f, 0.98f, 0.89f)

        val updated = AutomationConfig.defaults().copy(
            targetEpochMillis = 1_800_000_000_000L,
            stage1Rect = updatedRect,
        )

        assertEquals(1_800_000_000_000L, updated.targetEpochMillis)
        assertEquals(updatedRect, updated.stage1Rect)
    }

    @Test
    fun automationConfigRejectsInvalidTopLevelValues() {
        assertThrows(IllegalArgumentException::class.java) {
            customConfig(targetEpochMillis = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            customConfig(preTriggerOffsetMillis = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            customConfig(screenshotMinIntervalMillis = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            customConfig(maxScreenshotsPerStage = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            customConfig(resultTexts = listOf(" ", "\t"))
        }
    }

    @Test
    fun repositoryRoundTripPreservesEveryConfigField() {
        val store = FakeKeyValueStore()
        val repository = ConfigRepository(store)
        val config = customConfig(
            targetEpochMillis = 1_800_000_123L,
            preTriggerOffsetMillis = 275L,
            stage1Rect = NormalizedRect(-0.10f, 0.11f, 0.40f, 0.41f),
            stage2Rect = NormalizedRect(0.21f, 0.31f, 0.61f, 0.71f),
            stage3Rect = NormalizedRect(0.51f, 0.61f, 1.10f, 1.20f),
            stage1 = StagePolicy(1001L, 101L, 11, 0.81f),
            stage2 = StagePolicy(2002L, 202L, 22, 0.82f),
            stage3 = StagePolicy(3003L, 303L, 33, 0.83f),
            screenshotMinIntervalMillis = 909L,
            maxScreenshotsPerStage = 7,
            resultTexts = listOf(" Paid ", "Order ready", "Complete"),
            visualFallbackEnabled = false,
        )

        repository.save(config)

        assertEquals(config, repository.load())
    }

    @Test
    fun savingShorterResultTextListRemovesStaleIndexedValues() {
        val store = FakeKeyValueStore()
        val repository = ConfigRepository(store)
        repository.save(customConfig(resultTexts = listOf("one", "two", "three")))

        repository.save(customConfig(resultTexts = listOf("only")))

        assertEquals(1, store.getInt("result_text_count", -1))
        assertEquals("only", store.getString("result_text_0", "missing"))
        assertFalse(store.contains("result_text_1"))
        assertFalse(store.contains("result_text_2"))
        assertEquals(listOf("only"), repository.load().resultTexts)
    }

    @Test
    fun corruptStoredConfigLoadsDeterministicDefaults() {
        val store = FakeKeyValueStore()
        val repository = ConfigRepository(store)
        repository.save(customConfig())
        store.putInt("stage2_max_clicks", 0)

        assertEquals(AutomationConfig.defaults(), repository.load())
        assertEquals(AutomationConfig.defaults(), repository.load())
    }

    @Test
    fun keyValueStoreGettersMatchNamedPlanDefaults() {
        val store: KeyValueStore = FakeKeyValueStore()

        assertEquals(1L, store.getLong(key = "long", default = 1L))
        assertEquals(2, store.getInt(key = "int", default = 2))
        assertEquals(3f, store.getFloat(key = "float", default = 3f))
        assertEquals(true, store.getBoolean(key = "boolean", default = true))
        assertEquals("fallback", store.getString(key = "string", default = "fallback"))
    }

    private fun customConfig(
        targetEpochMillis: Long = 123L,
        preTriggerOffsetMillis: Long = 50L,
        stage1Rect: NormalizedRect = NormalizedRect(0.10f, 0.20f, 0.30f, 0.40f),
        stage2Rect: NormalizedRect = NormalizedRect(0.20f, 0.30f, 0.40f, 0.50f),
        stage3Rect: NormalizedRect = NormalizedRect(0.30f, 0.40f, 0.50f, 0.60f),
        stage1: StagePolicy = StagePolicy(1000L, 10L, 1, 0.70f),
        stage2: StagePolicy = StagePolicy(2000L, 20L, 2, 0.80f),
        stage3: StagePolicy = StagePolicy(3000L, 30L, 3, 0.90f),
        screenshotMinIntervalMillis: Long = 200L,
        maxScreenshotsPerStage: Int = 2,
        resultTexts: List<String> = listOf("done"),
        visualFallbackEnabled: Boolean = true,
    ): AutomationConfig = AutomationConfig(
        targetEpochMillis = targetEpochMillis,
        preTriggerOffsetMillis = preTriggerOffsetMillis,
        stage1Rect = stage1Rect,
        stage2Rect = stage2Rect,
        stage3Rect = stage3Rect,
        stage1 = stage1,
        stage2 = stage2,
        stage3 = stage3,
        screenshotMinIntervalMillis = screenshotMinIntervalMillis,
        maxScreenshotsPerStage = maxScreenshotsPerStage,
        resultTexts = resultTexts,
        visualFallbackEnabled = visualFallbackEnabled,
    )

    private class FakeKeyValueStore : KeyValueStore {
        private val values = mutableMapOf<String, Any>()

        override fun putLong(key: String, value: Long) {
            values[key] = value
        }

        override fun getLong(key: String, default: Long): Long =
            values[key]?.let { it as Long } ?: default

        override fun putInt(key: String, value: Int) {
            values[key] = value
        }

        override fun getInt(key: String, default: Int): Int =
            values[key]?.let { it as Int } ?: default

        override fun putFloat(key: String, value: Float) {
            values[key] = value
        }

        override fun getFloat(key: String, default: Float): Float =
            values[key]?.let { it as Float } ?: default

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override fun getBoolean(key: String, default: Boolean): Boolean =
            values[key]?.let { it as Boolean } ?: default

        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun getString(key: String, default: String): String =
            values[key]?.let { it as String } ?: default

        override fun remove(key: String) {
            values.remove(key)
        }

        fun contains(key: String): Boolean = values.containsKey(key)
    }
}
