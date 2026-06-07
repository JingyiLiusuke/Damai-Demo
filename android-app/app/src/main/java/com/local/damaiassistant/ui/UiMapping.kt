package com.local.damaiassistant.ui

import com.local.damaiassistant.config.NormalizedRect
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.math.min

data class FloatSelection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left < right) { "Selection left must be less than right" }
        require(top < bottom) { "Selection top must be less than bottom" }
    }
}

data class ImagePoint(
    val x: Float,
    val y: Float,
)

data class FitCenterTransform(
    val imageWidth: Int,
    val imageHeight: Int,
    val viewWidth: Int,
    val viewHeight: Int,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    fun viewToImage(x: Float, y: Float): ImagePoint = ImagePoint(
        x = ((x - offsetX) / scale).coerceIn(0f, imageWidth.toFloat()),
        y = ((y - offsetY) / scale).coerceIn(0f, imageHeight.toFloat()),
    )

    fun imageToView(point: ImagePoint): ImagePoint = ImagePoint(
        x = offsetX + point.x * scale,
        y = offsetY + point.y * scale,
    )

    companion object {
        fun create(
            imageWidth: Int,
            imageHeight: Int,
            viewWidth: Int,
            viewHeight: Int,
        ): FitCenterTransform {
            require(imageWidth > 0 && imageHeight > 0) {
                "Image dimensions must be positive"
            }
            require(viewWidth > 0 && viewHeight > 0) {
                "View dimensions must be positive"
            }
            val scale = min(
                viewWidth.toFloat() / imageWidth,
                viewHeight.toFloat() / imageHeight,
            )
            val drawnWidth = imageWidth * scale
            val drawnHeight = imageHeight * scale
            return FitCenterTransform(
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                scale = scale,
                offsetX = (viewWidth - drawnWidth) / 2f,
                offsetY = (viewHeight - drawnHeight) / 2f,
            )
        }
    }
}

fun parseTargetTime(
    value: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long? = runCatching {
    LocalDateTime.parse(value.trim(), TARGET_TIME_FORMATTER)
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()
}.getOrNull()

fun selectionToNormalized(
    selection: FloatSelection,
    imageWidth: Int,
    imageHeight: Int,
): NormalizedRect {
    require(imageWidth > 0 && imageHeight > 0) {
        "Image dimensions must be positive"
    }
    return NormalizedRect(
        left = (selection.left / imageWidth).coerceIn(0f, 1f),
        top = (selection.top / imageHeight).coerceIn(0f, 1f),
        right = (selection.right / imageWidth).coerceIn(0f, 1f),
        bottom = (selection.bottom / imageHeight).coerceIn(0f, 1f),
    )
}

val TARGET_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS")
        .withResolverStyle(ResolverStyle.STRICT)
