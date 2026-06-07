package com.local.damaiassistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.local.damaiassistant.config.PixelPoint

class GestureController(
    private val service: AccessibilityService,
) {
    private val lock = Any()
    private var inFlightGeneration: Long? = null

    fun click(
        point: PixelPoint,
        generation: Long,
        callback: (Long, Boolean) -> Unit,
    ): Boolean {
        require(point.x >= 0 && point.y >= 0) { "Gesture coordinates must be nonnegative" }
        synchronized(lock) {
            if (inFlightGeneration != null) return false
            inFlightGeneration = generation
        }

        val path = Path().apply {
            moveTo(point.x.toFloat(), point.y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 1L))
            .build()
        val resultCallback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                complete(generation, true, callback)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                complete(generation, false, callback)
            }
        }

        val dispatched = try {
            service.dispatchGesture(gesture, resultCallback, null)
        } catch (_: RuntimeException) {
            false
        }
        if (!dispatched) {
            complete(generation, false, callback)
        }
        return true
    }

    private fun complete(
        generation: Long,
        succeeded: Boolean,
        callback: (Long, Boolean) -> Unit,
    ) {
        val shouldNotify = synchronized(lock) {
            if (inFlightGeneration != generation) {
                false
            } else {
                inFlightGeneration = null
                true
            }
        }
        if (shouldNotify) {
            callback(generation, succeeded)
        }
    }
}
