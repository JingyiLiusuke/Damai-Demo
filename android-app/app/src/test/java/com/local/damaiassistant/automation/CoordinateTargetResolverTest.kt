package com.local.damaiassistant.automation

import com.local.damaiassistant.config.NormalizedRect
import com.local.damaiassistant.config.PixelPoint
import com.local.damaiassistant.config.PixelRect
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateTargetResolverTest {
    @Test
    fun `resolves calibrated center using window and usable display only`() {
        val point = CoordinateTargetResolver.resolve(
            bounds = NormalizedRect(0.5603124f, 0.933097f, 0.94503224f, 0.9893664f),
            screenWidth = 1344,
            screenHeight = 2772,
            windowBounds = PixelRect(0, 0, 1344, 2632),
            usableDisplayBounds = PixelRect(0, 0, 1344, 2632),
        )

        assertEquals(PixelPoint(1011, 2609), point)
    }

    @Test
    fun `returns null when calibrated region is outside active window`() {
        val point = CoordinateTargetResolver.resolve(
            bounds = NormalizedRect(0.6f, 0.9f, 0.9f, 1f),
            screenWidth = 1000,
            screenHeight = 2000,
            windowBounds = PixelRect(0, 0, 1000, 1500),
            usableDisplayBounds = PixelRect(0, 0, 1000, 1900),
        )

        assertEquals(null, point)
    }
}
