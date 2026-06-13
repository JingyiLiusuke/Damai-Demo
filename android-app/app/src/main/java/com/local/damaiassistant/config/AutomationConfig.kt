package com.local.damaiassistant.config

import java.util.Collections

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "NormalizedRect edges must be finite"
        }
        require(left < right) { "NormalizedRect left must be less than right" }
        require(top < bottom) { "NormalizedRect top must be less than bottom" }
    }

    fun toPixels(width: Int, height: Int): PixelRect {
        require(width > 0) { "Pixel width must be positive" }
        require(height > 0) { "Pixel height must be positive" }

        val pixelLeft = (left.coerceIn(0f, 1f) * width).toInt()
        val pixelTop = (top.coerceIn(0f, 1f) * height).toInt()
        val pixelRight = (right.coerceIn(0f, 1f) * width).toInt()
        val pixelBottom = (bottom.coerceIn(0f, 1f) * height).toInt()

        require(pixelLeft < pixelRight && pixelTop < pixelBottom) {
            "NormalizedRect collapses to invalid pixel geometry after clamping"
        }
        return PixelRect(pixelLeft, pixelTop, pixelRight, pixelBottom)
    }
}

data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left < right) { "PixelRect left must be less than right" }
        require(top < bottom) { "PixelRect top must be less than bottom" }
    }

    fun center(): PixelPoint = PixelPoint(
        x = ((left.toLong() + right.toLong()) / 2L).toInt(),
        y = ((top.toLong() + bottom.toLong()) / 2L).toInt(),
    )

    fun clipTo(bounds: PixelRect): PixelRect = PixelRect(
        left = maxOf(left, bounds.left),
        top = maxOf(top, bounds.top),
        right = minOf(right, bounds.right),
        bottom = minOf(bottom, bounds.bottom),
    )

    fun intersectionOrNull(bounds: PixelRect): PixelRect? {
        val intersectionLeft = maxOf(left, bounds.left)
        val intersectionTop = maxOf(top, bounds.top)
        val intersectionRight = minOf(right, bounds.right)
        val intersectionBottom = minOf(bottom, bounds.bottom)
        if (
            intersectionLeft >= intersectionRight ||
            intersectionTop >= intersectionBottom
        ) {
            return null
        }
        return PixelRect(
            intersectionLeft,
            intersectionTop,
            intersectionRight,
            intersectionBottom,
        )
    }

    fun union(other: PixelRect): PixelRect = PixelRect(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )
}

data class PixelPoint(val x: Int, val y: Int)

data class StagePolicy(
    val timeoutMillis: Long,
    val retryMillis: Long,
    val maxClicks: Int,
    val visualMatchThreshold: Float,
) {
    init {
        require(timeoutMillis > 0L) { "Stage timeout must be positive" }
        require(retryMillis > 0L) { "Stage retry interval must be positive" }
        require(maxClicks > 0) { "Stage max clicks must be positive" }
        require(visualMatchThreshold.isFinite() && visualMatchThreshold in 0f..1f) {
            "Visual match threshold must be between 0.0 and 1.0"
        }
    }
}

class AutomationConfig(
    val targetEpochMillis: Long,
    val preTriggerOffsetMillis: Long,
    val stage1Rect: NormalizedRect,
    val stage2Rect: NormalizedRect,
    val stage3Rect: NormalizedRect,
    val stage1: StagePolicy,
    val stage2: StagePolicy,
    val stage3: StagePolicy,
    val screenshotMinIntervalMillis: Long,
    val maxScreenshotsPerStage: Int,
    resultTexts: List<String>,
    val visualFallbackEnabled: Boolean,
    val lowLatencyEnabled: Boolean = true,
    val visualFallbackDelayMillis: Long = 300L,
) {
    private val normalizedResultTexts: List<String> =
        Collections.unmodifiableList(
            resultTexts
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList(),
        )

    val resultTexts: List<String>
        get() = normalizedResultTexts

    init {
        require(targetEpochMillis >= 0L) { "Target epoch must be nonnegative" }
        require(preTriggerOffsetMillis >= 0L) { "Pre-trigger offset must be nonnegative" }
        require(screenshotMinIntervalMillis >= 0L) {
            "Screenshot minimum interval must be nonnegative"
        }
        require(maxScreenshotsPerStage > 0) { "Maximum screenshots per stage must be positive" }
        require(visualFallbackDelayMillis >= 0L) {
            "Visual fallback delay must be nonnegative"
        }
        require(normalizedResultTexts.isNotEmpty()) {
            "At least one nonblank result text is required"
        }
        require(normalizedResultTexts.all { it.isNotBlank() && it == it.trim() }) {
            "Result texts must be trimmed and nonblank"
        }
    }

    fun copy(
        targetEpochMillis: Long = this.targetEpochMillis,
        preTriggerOffsetMillis: Long = this.preTriggerOffsetMillis,
        stage1Rect: NormalizedRect = this.stage1Rect,
        stage2Rect: NormalizedRect = this.stage2Rect,
        stage3Rect: NormalizedRect = this.stage3Rect,
        stage1: StagePolicy = this.stage1,
        stage2: StagePolicy = this.stage2,
        stage3: StagePolicy = this.stage3,
        screenshotMinIntervalMillis: Long = this.screenshotMinIntervalMillis,
        maxScreenshotsPerStage: Int = this.maxScreenshotsPerStage,
        resultTexts: List<String> = this.normalizedResultTexts,
        visualFallbackEnabled: Boolean = this.visualFallbackEnabled,
        lowLatencyEnabled: Boolean = this.lowLatencyEnabled,
        visualFallbackDelayMillis: Long = this.visualFallbackDelayMillis,
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
        lowLatencyEnabled = lowLatencyEnabled,
        visualFallbackDelayMillis = visualFallbackDelayMillis,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AutomationConfig) return false

        return targetEpochMillis == other.targetEpochMillis &&
            preTriggerOffsetMillis == other.preTriggerOffsetMillis &&
            stage1Rect == other.stage1Rect &&
            stage2Rect == other.stage2Rect &&
            stage3Rect == other.stage3Rect &&
            stage1 == other.stage1 &&
            stage2 == other.stage2 &&
            stage3 == other.stage3 &&
            screenshotMinIntervalMillis == other.screenshotMinIntervalMillis &&
            maxScreenshotsPerStage == other.maxScreenshotsPerStage &&
            normalizedResultTexts == other.normalizedResultTexts &&
            visualFallbackEnabled == other.visualFallbackEnabled &&
            lowLatencyEnabled == other.lowLatencyEnabled &&
            visualFallbackDelayMillis == other.visualFallbackDelayMillis
    }

    override fun hashCode(): Int {
        var result = targetEpochMillis.hashCode()
        result = 31 * result + preTriggerOffsetMillis.hashCode()
        result = 31 * result + stage1Rect.hashCode()
        result = 31 * result + stage2Rect.hashCode()
        result = 31 * result + stage3Rect.hashCode()
        result = 31 * result + stage1.hashCode()
        result = 31 * result + stage2.hashCode()
        result = 31 * result + stage3.hashCode()
        result = 31 * result + screenshotMinIntervalMillis.hashCode()
        result = 31 * result + maxScreenshotsPerStage
        result = 31 * result + normalizedResultTexts.hashCode()
        result = 31 * result + visualFallbackEnabled.hashCode()
        result = 31 * result + lowLatencyEnabled.hashCode()
        result = 31 * result + visualFallbackDelayMillis.hashCode()
        return result
    }

    override fun toString(): String =
        "AutomationConfig(" +
            "targetEpochMillis=$targetEpochMillis, " +
            "preTriggerOffsetMillis=$preTriggerOffsetMillis, " +
            "stage1Rect=$stage1Rect, " +
            "stage2Rect=$stage2Rect, " +
            "stage3Rect=$stage3Rect, " +
            "stage1=$stage1, " +
            "stage2=$stage2, " +
            "stage3=$stage3, " +
            "screenshotMinIntervalMillis=$screenshotMinIntervalMillis, " +
            "maxScreenshotsPerStage=$maxScreenshotsPerStage, " +
            "resultTexts=$normalizedResultTexts, " +
            "visualFallbackEnabled=$visualFallbackEnabled, " +
            "lowLatencyEnabled=$lowLatencyEnabled, " +
            "visualFallbackDelayMillis=$visualFallbackDelayMillis" +
            ")"

    companion object {
        fun defaults(): AutomationConfig {
            val placeholderRect = NormalizedRect(0.55f, 0.80f, 0.95f, 0.95f)
            return AutomationConfig(
                targetEpochMillis = 0L,
                preTriggerOffsetMillis = 100L,
                stage1Rect = placeholderRect,
                stage2Rect = placeholderRect,
                stage3Rect = placeholderRect,
                stage1 = StagePolicy(18000L, 80L, 12, 0.90f),
                stage2 = StagePolicy(15000L, 100L, 8, 0.90f),
                stage3 = StagePolicy(15000L, 120L, 4, 0.90f),
                screenshotMinIntervalMillis = 400L,
                maxScreenshotsPerStage = 3,
                resultTexts = listOf("支付", "订单", "提交成功"),
                visualFallbackEnabled = true,
                lowLatencyEnabled = true,
                visualFallbackDelayMillis = 300L,
            )
        }
    }
}
