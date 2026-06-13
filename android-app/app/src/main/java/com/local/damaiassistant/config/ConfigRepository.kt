package com.local.damaiassistant.config

import android.content.SharedPreferences

interface KeyValueStore {
    fun putLong(key: String, value: Long)
    fun getLong(key: String, default: Long): Long
    fun putInt(key: String, value: Int)
    fun getInt(key: String, default: Int): Int
    fun putFloat(key: String, value: Float)
    fun getFloat(key: String, default: Float): Float
    fun putBoolean(key: String, value: Boolean)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putString(key: String, value: String)
    fun getString(key: String, default: String): String
    fun remove(key: String)
}

class SharedPreferencesKeyValueStore(
    private val preferences: SharedPreferences,
) : KeyValueStore {
    override fun putLong(key: String, value: Long) {
        preferences.edit().putLong(key, value).apply()
    }

    override fun getLong(key: String, default: Long): Long =
        preferences.getLong(key, default)

    override fun putInt(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
    }

    override fun getInt(key: String, default: Int): Int =
        preferences.getInt(key, default)

    override fun putFloat(key: String, value: Float) {
        preferences.edit().putFloat(key, value).apply()
    }

    override fun getFloat(key: String, default: Float): Float =
        preferences.getFloat(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        preferences.getBoolean(key, default)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun getString(key: String, default: String): String =
        preferences.getString(key, default) ?: default

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}

class ConfigRepository(
    private val store: KeyValueStore,
) {
    fun save(config: AutomationConfig) {
        val previousResultTextCount = store.getInt(RESULT_TEXT_COUNT, 0).coerceAtLeast(0)

        store.putLong(TARGET_EPOCH_MILLIS, config.targetEpochMillis)
        store.putLong(PRE_TRIGGER_OFFSET_MILLIS, config.preTriggerOffsetMillis)
        saveRect(
            config.stage1Rect,
            STAGE1_RECT_LEFT,
            STAGE1_RECT_TOP,
            STAGE1_RECT_RIGHT,
            STAGE1_RECT_BOTTOM,
        )
        saveRect(
            config.stage2Rect,
            STAGE2_RECT_LEFT,
            STAGE2_RECT_TOP,
            STAGE2_RECT_RIGHT,
            STAGE2_RECT_BOTTOM,
        )
        saveRect(
            config.stage3Rect,
            STAGE3_RECT_LEFT,
            STAGE3_RECT_TOP,
            STAGE3_RECT_RIGHT,
            STAGE3_RECT_BOTTOM,
        )
        saveStage(
            config.stage1,
            STAGE1_TIMEOUT_MILLIS,
            STAGE1_RETRY_MILLIS,
            STAGE1_MAX_CLICKS,
            STAGE1_VISUAL_MATCH_THRESHOLD,
        )
        saveStage(
            config.stage2,
            STAGE2_TIMEOUT_MILLIS,
            STAGE2_RETRY_MILLIS,
            STAGE2_MAX_CLICKS,
            STAGE2_VISUAL_MATCH_THRESHOLD,
        )
        saveStage(
            config.stage3,
            STAGE3_TIMEOUT_MILLIS,
            STAGE3_RETRY_MILLIS,
            STAGE3_MAX_CLICKS,
            STAGE3_VISUAL_MATCH_THRESHOLD,
        )
        store.putLong(SCREENSHOT_MIN_INTERVAL_MILLIS, config.screenshotMinIntervalMillis)
        store.putInt(MAX_SCREENSHOTS_PER_STAGE, config.maxScreenshotsPerStage)
        store.putBoolean(VISUAL_FALLBACK_ENABLED, config.visualFallbackEnabled)
        store.putBoolean(LOW_LATENCY_ENABLED, config.lowLatencyEnabled)
        store.putLong(VISUAL_FALLBACK_DELAY_MILLIS, config.visualFallbackDelayMillis)

        store.putInt(RESULT_TEXT_COUNT, config.resultTexts.size)
        config.resultTexts.forEachIndexed { index, resultText ->
            store.putString(resultTextKey(index), resultText)
        }
        for (index in config.resultTexts.size until previousResultTextCount) {
            store.remove(resultTextKey(index))
        }
    }

    fun load(): AutomationConfig {
        val defaults = AutomationConfig.defaults()
        return try {
            val resultTextCount = store.getInt(RESULT_TEXT_COUNT, defaults.resultTexts.size)
            require(resultTextCount >= 0) { "Result text count must be nonnegative" }
            val resultTexts = (0 until resultTextCount).map { index ->
                store.getString(
                    resultTextKey(index),
                    defaults.resultTexts.getOrElse(index) { "" },
                )
            }

            AutomationConfig(
                targetEpochMillis = store.getLong(
                    TARGET_EPOCH_MILLIS,
                    defaults.targetEpochMillis,
                ),
                preTriggerOffsetMillis = store.getLong(
                    PRE_TRIGGER_OFFSET_MILLIS,
                    defaults.preTriggerOffsetMillis,
                ),
                stage1Rect = loadRect(
                    defaults.stage1Rect,
                    STAGE1_RECT_LEFT,
                    STAGE1_RECT_TOP,
                    STAGE1_RECT_RIGHT,
                    STAGE1_RECT_BOTTOM,
                ),
                stage2Rect = loadRect(
                    defaults.stage2Rect,
                    STAGE2_RECT_LEFT,
                    STAGE2_RECT_TOP,
                    STAGE2_RECT_RIGHT,
                    STAGE2_RECT_BOTTOM,
                ),
                stage3Rect = loadRect(
                    defaults.stage3Rect,
                    STAGE3_RECT_LEFT,
                    STAGE3_RECT_TOP,
                    STAGE3_RECT_RIGHT,
                    STAGE3_RECT_BOTTOM,
                ),
                stage1 = loadStage(
                    defaults.stage1,
                    STAGE1_TIMEOUT_MILLIS,
                    STAGE1_RETRY_MILLIS,
                    STAGE1_MAX_CLICKS,
                    STAGE1_VISUAL_MATCH_THRESHOLD,
                ),
                stage2 = loadStage(
                    defaults.stage2,
                    STAGE2_TIMEOUT_MILLIS,
                    STAGE2_RETRY_MILLIS,
                    STAGE2_MAX_CLICKS,
                    STAGE2_VISUAL_MATCH_THRESHOLD,
                ),
                stage3 = loadStage(
                    defaults.stage3,
                    STAGE3_TIMEOUT_MILLIS,
                    STAGE3_RETRY_MILLIS,
                    STAGE3_MAX_CLICKS,
                    STAGE3_VISUAL_MATCH_THRESHOLD,
                ),
                screenshotMinIntervalMillis = store.getLong(
                    SCREENSHOT_MIN_INTERVAL_MILLIS,
                    defaults.screenshotMinIntervalMillis,
                ),
                maxScreenshotsPerStage = store.getInt(
                    MAX_SCREENSHOTS_PER_STAGE,
                    defaults.maxScreenshotsPerStage,
                ),
                resultTexts = resultTexts,
                visualFallbackEnabled = store.getBoolean(
                    VISUAL_FALLBACK_ENABLED,
                    defaults.visualFallbackEnabled,
                ),
                lowLatencyEnabled = store.getBoolean(
                    LOW_LATENCY_ENABLED,
                    defaults.lowLatencyEnabled,
                ),
                visualFallbackDelayMillis = store.getLong(
                    VISUAL_FALLBACK_DELAY_MILLIS,
                    defaults.visualFallbackDelayMillis,
                ),
            )
        } catch (_: RuntimeException) {
            defaults
        }
    }

    private fun saveRect(
        rect: NormalizedRect,
        leftKey: String,
        topKey: String,
        rightKey: String,
        bottomKey: String,
    ) {
        store.putFloat(leftKey, rect.left)
        store.putFloat(topKey, rect.top)
        store.putFloat(rightKey, rect.right)
        store.putFloat(bottomKey, rect.bottom)
    }

    private fun loadRect(
        default: NormalizedRect,
        leftKey: String,
        topKey: String,
        rightKey: String,
        bottomKey: String,
    ): NormalizedRect = NormalizedRect(
        left = store.getFloat(leftKey, default.left),
        top = store.getFloat(topKey, default.top),
        right = store.getFloat(rightKey, default.right),
        bottom = store.getFloat(bottomKey, default.bottom),
    )

    private fun saveStage(
        stage: StagePolicy,
        timeoutKey: String,
        retryKey: String,
        maxClicksKey: String,
        thresholdKey: String,
    ) {
        store.putLong(timeoutKey, stage.timeoutMillis)
        store.putLong(retryKey, stage.retryMillis)
        store.putInt(maxClicksKey, stage.maxClicks)
        store.putFloat(thresholdKey, stage.visualMatchThreshold)
    }

    private fun loadStage(
        default: StagePolicy,
        timeoutKey: String,
        retryKey: String,
        maxClicksKey: String,
        thresholdKey: String,
    ): StagePolicy = StagePolicy(
        timeoutMillis = store.getLong(timeoutKey, default.timeoutMillis),
        retryMillis = store.getLong(retryKey, default.retryMillis),
        maxClicks = store.getInt(maxClicksKey, default.maxClicks),
        visualMatchThreshold = store.getFloat(thresholdKey, default.visualMatchThreshold),
    )

    private fun resultTextKey(index: Int): String = "result_text_$index"

    private companion object {
        const val TARGET_EPOCH_MILLIS = "target_epoch_millis"
        const val PRE_TRIGGER_OFFSET_MILLIS = "pre_trigger_offset_millis"

        const val STAGE1_RECT_LEFT = "stage1_rect_left"
        const val STAGE1_RECT_TOP = "stage1_rect_top"
        const val STAGE1_RECT_RIGHT = "stage1_rect_right"
        const val STAGE1_RECT_BOTTOM = "stage1_rect_bottom"
        const val STAGE2_RECT_LEFT = "stage2_rect_left"
        const val STAGE2_RECT_TOP = "stage2_rect_top"
        const val STAGE2_RECT_RIGHT = "stage2_rect_right"
        const val STAGE2_RECT_BOTTOM = "stage2_rect_bottom"
        const val STAGE3_RECT_LEFT = "stage3_rect_left"
        const val STAGE3_RECT_TOP = "stage3_rect_top"
        const val STAGE3_RECT_RIGHT = "stage3_rect_right"
        const val STAGE3_RECT_BOTTOM = "stage3_rect_bottom"

        const val STAGE1_TIMEOUT_MILLIS = "stage1_timeout_millis"
        const val STAGE1_RETRY_MILLIS = "stage1_retry_millis"
        const val STAGE1_MAX_CLICKS = "stage1_max_clicks"
        const val STAGE1_VISUAL_MATCH_THRESHOLD = "stage1_visual_match_threshold"
        const val STAGE2_TIMEOUT_MILLIS = "stage2_timeout_millis"
        const val STAGE2_RETRY_MILLIS = "stage2_retry_millis"
        const val STAGE2_MAX_CLICKS = "stage2_max_clicks"
        const val STAGE2_VISUAL_MATCH_THRESHOLD = "stage2_visual_match_threshold"
        const val STAGE3_TIMEOUT_MILLIS = "stage3_timeout_millis"
        const val STAGE3_RETRY_MILLIS = "stage3_retry_millis"
        const val STAGE3_MAX_CLICKS = "stage3_max_clicks"
        const val STAGE3_VISUAL_MATCH_THRESHOLD = "stage3_visual_match_threshold"

        const val SCREENSHOT_MIN_INTERVAL_MILLIS = "screenshot_min_interval_millis"
        const val MAX_SCREENSHOTS_PER_STAGE = "max_screenshots_per_stage"
        const val RESULT_TEXT_COUNT = "result_text_count"
        const val VISUAL_FALLBACK_ENABLED = "visual_fallback_enabled"
        const val LOW_LATENCY_ENABLED = "low_latency_enabled"
        const val VISUAL_FALLBACK_DELAY_MILLIS = "visual_fallback_delay_millis"
    }
}
