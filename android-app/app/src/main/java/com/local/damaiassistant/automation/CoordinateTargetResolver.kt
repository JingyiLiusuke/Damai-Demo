package com.local.damaiassistant.automation

import com.local.damaiassistant.config.NormalizedRect
import com.local.damaiassistant.config.PixelPoint
import com.local.damaiassistant.config.PixelRect

object CoordinateTargetResolver {
    fun resolve(
        bounds: NormalizedRect,
        screenWidth: Int,
        screenHeight: Int,
        windowBounds: PixelRect,
        usableDisplayBounds: PixelRect,
    ): PixelPoint? {
        val activeBounds = windowBounds.intersectionOrNull(usableDisplayBounds)
            ?: return null
        return bounds.toPixels(screenWidth, screenHeight)
            .intersectionOrNull(activeBounds)
            ?.center()
    }
}
