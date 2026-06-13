package com.local.damaiassistant.domain

import com.local.damaiassistant.config.PixelPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualMatcherTest {
    @Test
    fun identicalImagesScoreOne() {
        val pixels = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt())

        assertEquals(1.0, VisualMatcher.score(pixels, pixels), 0.0001)
    }

    @Test
    fun oppositeBlackAndWhitePixelsScoreZero() {
        assertEquals(
            0.0,
            VisualMatcher.score(
                intArrayOf(0xff000000.toInt()),
                intArrayOf(0xffffffff.toInt()),
            ),
            0.0001,
        )
    }

    @Test
    fun convertsArgbPixelsUsingSpecifiedIntegerLuminance() {
        val image = VisualMatcher.fromArgb(
            width = 3,
            height = 1,
            argb = intArrayOf(
                0xffff0000.toInt(),
                0xff00ff00.toInt(),
                0xff0000ff.toInt(),
            ),
        )

        assertEquals(listOf(76, 149, 28), image.pixels.toList())
    }

    @Test
    fun locatesTemplateInsideSearchImage() {
        val search = GrayImage(
            4,
            3,
            intArrayOf(
                0, 0, 0, 0,
                0, 20, 40, 0,
                0, 60, 80, 0,
            ),
        )
        val template = GrayImage(2, 2, intArrayOf(20, 40, 60, 80))

        val result = VisualMatcher.findBest(search, template)

        assertEquals(PixelPoint(2, 2), result.center)
        assertTrue(result.score >= 0.99)
    }

    @Test
    fun returnsCenterInOriginalCoordinatesAfterDownscaling() {
        val searchPixels = IntArray(320 * 4)
        for (y in 0 until 4) {
            for (x in 200 until 204) {
                searchPixels[y * 320 + x] = 200
            }
        }
        val search = GrayImage(320, 4, searchPixels)
        val template = GrayImage(4, 4, IntArray(16) { 200 })

        val result = VisualMatcher.findBest(search, template)

        assertTrue(result.center.x in 200..204)
        assertTrue(result.center.y in 1..3)
        assertTrue(result.score >= 0.99)
    }

    @Test
    fun rejectsMismatchedPixelCountsAndEmptyScores() {
        assertThrows(IllegalArgumentException::class.java) {
            VisualMatcher.score(intArrayOf(0), intArrayOf(0, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VisualMatcher.score(intArrayOf(), intArrayOf())
        }
    }

    @Test
    fun rejectsInvalidGrayImagesAndOversizedTemplate() {
        assertThrows(IllegalArgumentException::class.java) {
            GrayImage(2, 2, intArrayOf(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GrayImage(1, 1, intArrayOf(256))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VisualMatcher.findBest(
                GrayImage(1, 1, intArrayOf(0)),
                GrayImage(2, 1, intArrayOf(0, 0)),
            )
        }
    }
}
