package com.local.damaiassistant.domain

import com.local.damaiassistant.config.PixelPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class GrayImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(width.toLong() * height.toLong() == pixels.size.toLong()) {
            "Pixel count must match image dimensions"
        }
        require(pixels.all { it in 0..255 }) { "Grayscale pixels must be between 0 and 255" }
    }
}

data class VisualMatch(
    val center: PixelPoint,
    val score: Double,
) {
    init {
        require(score.isFinite() && score in 0.0..1.0) {
            "Visual score must be between 0.0 and 1.0"
        }
    }
}

object VisualMatcher {
    private const val MAX_SEARCH_WIDTH = 160

    fun score(firstArgb: IntArray, secondArgb: IntArray): Double {
        require(firstArgb.isNotEmpty()) { "Images must contain at least one pixel" }
        require(firstArgb.size == secondArgb.size) { "Pixel counts must match" }

        var difference = 0L
        for (index in firstArgb.indices) {
            difference += abs(luminance(firstArgb[index]) - luminance(secondArgb[index]))
        }
        return normalizedScore(difference, firstArgb.size)
    }

    fun fromArgb(
        width: Int,
        height: Int,
        argb: IntArray,
    ): GrayImage {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(width.toLong() * height.toLong() == argb.size.toLong()) {
            "Pixel count must match image dimensions"
        }
        return GrayImage(width, height, IntArray(argb.size) { luminance(argb[it]) })
    }

    fun findBest(search: GrayImage, template: GrayImage): VisualMatch {
        require(template.width <= search.width && template.height <= search.height) {
            "Template must fit inside the search image"
        }

        val scale = min(1.0, MAX_SEARCH_WIDTH.toDouble() / search.width.toDouble())
        val scaledSearch = scale(search, scale)
        val scaledTemplate = scale(template, scale)
        require(
            scaledTemplate.width <= scaledSearch.width &&
                scaledTemplate.height <= scaledSearch.height,
        ) {
            "Scaled template must fit inside the scaled search image"
        }

        var bestScore = Double.NEGATIVE_INFINITY
        var bestLeft = 0
        var bestTop = 0
        val maxLeft = scaledSearch.width - scaledTemplate.width
        val maxTop = scaledSearch.height - scaledTemplate.height

        for (top in 0..maxTop) {
            for (left in 0..maxLeft) {
                val candidateScore = scoreRegion(
                    search = scaledSearch,
                    template = scaledTemplate,
                    left = left,
                    top = top,
                )
                if (candidateScore > bestScore) {
                    bestScore = candidateScore
                    bestLeft = left
                    bestTop = top
                }
            }
        }

        val scaledCenterX = bestLeft + scaledTemplate.width / 2
        val scaledCenterY = bestTop + scaledTemplate.height / 2
        val originalCenter = PixelPoint(
            x = (scaledCenterX.toDouble() * search.width / scaledSearch.width)
                .roundToInt()
                .coerceIn(0, search.width - 1),
            y = (scaledCenterY.toDouble() * search.height / scaledSearch.height)
                .roundToInt()
                .coerceIn(0, search.height - 1),
        )
        return VisualMatch(originalCenter, bestScore)
    }

    private fun scale(image: GrayImage, scale: Double): GrayImage {
        if (scale >= 1.0) return image
        val targetWidth = max(1, (image.width * scale).roundToInt())
        val targetHeight = max(1, (image.height * scale).roundToInt())
        val targetPixels = IntArray(targetWidth * targetHeight)

        for (targetY in 0 until targetHeight) {
            val sourceY = (targetY.toLong() * image.height / targetHeight)
                .toInt()
                .coerceAtMost(image.height - 1)
            for (targetX in 0 until targetWidth) {
                val sourceX = (targetX.toLong() * image.width / targetWidth)
                    .toInt()
                    .coerceAtMost(image.width - 1)
                targetPixels[targetY * targetWidth + targetX] =
                    image.pixels[sourceY * image.width + sourceX]
            }
        }
        return GrayImage(targetWidth, targetHeight, targetPixels)
    }

    private fun scoreRegion(
        search: GrayImage,
        template: GrayImage,
        left: Int,
        top: Int,
    ): Double {
        var difference = 0L
        for (templateY in 0 until template.height) {
            val searchOffset = (top + templateY) * search.width + left
            val templateOffset = templateY * template.width
            for (templateX in 0 until template.width) {
                difference += abs(
                    search.pixels[searchOffset + templateX] -
                        template.pixels[templateOffset + templateX],
                )
            }
        }
        return normalizedScore(difference, template.pixels.size)
    }

    private fun normalizedScore(totalDifference: Long, pixelCount: Int): Double {
        val meanDifference = totalDifference.toDouble() / pixelCount.toDouble()
        return (1.0 - meanDifference / 255.0).coerceIn(0.0, 1.0)
    }

    private fun luminance(argb: Int): Int {
        val red = argb ushr 16 and 0xff
        val green = argb ushr 8 and 0xff
        val blue = argb and 0xff
        return (77 * red + 150 * green + 29 * blue) ushr 8
    }
}
