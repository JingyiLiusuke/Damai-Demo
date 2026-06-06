package com.local.damaiassistant.config

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

data class AutomationConfig(
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
    private val normalizedResultTexts: List<String>,
    val visualFallbackEnabled: Boolean,
) {
    val resultTexts: List<String>
        get() = normalizedResultTexts

    init {
        require(targetEpochMillis >= 0L) { "Target epoch must be nonnegative" }
        require(preTriggerOffsetMillis >= 0L) { "Pre-trigger offset must be nonnegative" }
        require(screenshotMinIntervalMillis >= 0L) {
            "Screenshot minimum interval must be nonnegative"
        }
        require(maxScreenshotsPerStage > 0) { "Maximum screenshots per stage must be positive" }
        require(normalizedResultTexts.isNotEmpty()) {
            "At least one nonblank result text is required"
        }
        require(normalizedResultTexts.all { it.isNotBlank() && it == it.trim() }) {
            "Result texts must be trimmed and nonblank"
        }
    }

    companion object {
        operator fun invoke(
            targetEpochMillis: Long,
            preTriggerOffsetMillis: Long,
            stage1Rect: NormalizedRect,
            stage2Rect: NormalizedRect,
            stage3Rect: NormalizedRect,
            stage1: StagePolicy,
            stage2: StagePolicy,
            stage3: StagePolicy,
            screenshotMinIntervalMillis: Long,
            maxScreenshotsPerStage: Int,
            resultTexts: List<String>,
            visualFallbackEnabled: Boolean,
        ): AutomationConfig {
            val normalizedResultTexts = resultTexts
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            require(normalizedResultTexts.isNotEmpty()) {
                "At least one nonblank result text is required"
            }

            return AutomationConfig(
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
                normalizedResultTexts = normalizedResultTexts,
                visualFallbackEnabled = visualFallbackEnabled,
            )
        }

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
            )
        }
    }
}
