package com.local.damaiassistant.ui

import com.local.damaiassistant.config.NormalizedRect
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiMappingTest {
    @Test
    fun parsesTargetTimeInRequestedZone() {
        val parsed = parseTargetTime(
            value = "2026-06-07 19:30:15.250",
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        assertEquals(1_780_831_815_250L, parsed)
    }

    @Test
    fun rejectsInvalidTargetTime() {
        assertNull(parseTargetTime("2026-02-30 10:00:00.000", ZoneId.of("UTC")))
        assertNull(parseTargetTime("not-a-time", ZoneId.of("UTC")))
    }

    @Test
    fun selectionMapsToNormalizedBitmapCoordinates() {
        assertEquals(
            NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f),
            selectionToNormalized(
                selection = FloatSelection(50f, 100f, 150f, 300f),
                imageWidth = 200,
                imageHeight = 400,
            ),
        )
    }

    @Test
    fun fitCenterMapsViewCoordinatesBackToImage() {
        val transform = FitCenterTransform.create(
            imageWidth = 200,
            imageHeight = 100,
            viewWidth = 300,
            viewHeight = 300,
        )

        assertEquals(ImagePoint(0f, 0f), transform.viewToImage(0f, 75f))
        assertEquals(ImagePoint(200f, 100f), transform.viewToImage(300f, 225f))
        assertEquals(ImagePoint(100f, 50f), transform.viewToImage(150f, 150f))
    }
}
