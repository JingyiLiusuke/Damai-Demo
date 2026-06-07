package com.local.damaiassistant.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.Display
import com.local.damaiassistant.config.NormalizedRect
import com.local.damaiassistant.config.PixelPoint
import com.local.damaiassistant.domain.Stage
import com.local.damaiassistant.domain.VisualMatcher
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScreenshotController(
    private val service: AccessibilityService,
    private val filesDir: File,
    private val screenshotMinIntervalMillis: Long,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
) : AutoCloseable {
    private val lock = Any()
    private var captureInFlight = false
    private var lastCaptureStartedAtMillis: Long? = null

    init {
        require(screenshotMinIntervalMillis >= 0L) {
            "Screenshot minimum interval must be nonnegative"
        }
    }

    fun capture(
        bounds: NormalizedRect,
        callback: (Result<Bitmap>) -> Unit,
    ): Boolean = captureRegion(bounds) { result ->
        callback(result.map { it.bitmap })
    }

    fun captureAndMatch(
        stage: Stage,
        bounds: NormalizedRect,
        threshold: Float,
        callback: (Result<PixelPoint?>) -> Unit,
    ): Boolean {
        require(threshold.isFinite() && threshold in 0f..1f) {
            "Match threshold must be between 0.0 and 1.0"
        }
        return captureRegion(bounds) { captureResult ->
            val matched = captureResult.mapCatching { region ->
                var template: Bitmap? = null
                try {
                    template = loadTemplate(stage)
                        ?: throw IllegalStateException("No template saved for $stage")
                    val searchImage = region.bitmap.toGrayImage()
                    val templateImage = template.toGrayImage()
                    val match = VisualMatcher.findBest(searchImage, templateImage)
                    if (match.score >= threshold) {
                        PixelPoint(
                            x = region.origin.x + match.center.x,
                            y = region.origin.y + match.center.y,
                        )
                    } else {
                        null
                    }
                } finally {
                    template?.recycle()
                    region.bitmap.recycle()
                }
            }
            callback(matched)
        }
    }

    fun saveTemplate(stage: Stage, bitmap: Bitmap): Result<File> = runCatching {
        val directory = File(filesDir, TEMPLATE_DIRECTORY)
        check(directory.exists() || directory.mkdirs()) {
            "Unable to create template directory"
        }
        val output = templateFile(stage)
        val software = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IllegalStateException("Unable to copy template bitmap")
        try {
            FileOutputStream(output).use { stream ->
                check(software.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Unable to encode template bitmap"
                }
            }
        } finally {
            software.recycle()
        }
        output
    }

    fun loadTemplate(stage: Stage): Bitmap? =
        BitmapFactory.decodeFile(templateFile(stage).absolutePath)

    private fun captureRegion(
        bounds: NormalizedRect,
        callback: (Result<CapturedRegion>) -> Unit,
    ): Boolean {
        val now = elapsedRealtimeMillis()
        require(now >= 0L) { "Elapsed realtime must be nonnegative" }
        synchronized(lock) {
            if (captureInFlight) return false
            val previous = lastCaptureStartedAtMillis
            if (
                previous != null &&
                now >= previous &&
                now - previous < screenshotMinIntervalMillis
            ) {
                return false
            }
            captureInFlight = true
            lastCaptureStartedAtMillis = now
        }

        val screenshotCallback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(
                screenshot: AccessibilityService.ScreenshotResult,
            ) {
                processingExecutor.execute {
                    val result = runCatching { screenshot.toCapturedRegion(bounds) }
                    finishCapture()
                    callback(result)
                }
            }

            override fun onFailure(errorCode: Int) {
                processingExecutor.execute {
                    finishCapture()
                    callback(
                        Result.failure(
                            ScreenshotFailureException(errorCode),
                        ),
                    )
                }
            }
        }

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                callbackExecutor,
                screenshotCallback,
            )
        } catch (exception: RuntimeException) {
            processingExecutor.execute {
                finishCapture()
                callback(Result.failure(exception))
            }
        }
        return true
    }

    private fun AccessibilityService.ScreenshotResult.toCapturedRegion(
        bounds: NormalizedRect,
    ): CapturedRegion {
        val buffer = hardwareBuffer
        try {
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                ?: throw IllegalStateException("Unable to wrap screenshot hardware buffer")
            val fullSoftware = try {
                hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("Unable to copy screenshot bitmap")
            } finally {
                hardwareBitmap.recycle()
            }

            val crop = bounds.toPixels(fullSoftware.width, fullSoftware.height)
            val isFullBitmap = crop.left == 0 &&
                crop.top == 0 &&
                crop.right == fullSoftware.width &&
                crop.bottom == fullSoftware.height
            if (isFullBitmap) {
                return CapturedRegion(fullSoftware, PixelPoint(0, 0))
            }

            try {
                val cropped = Bitmap.createBitmap(
                    fullSoftware,
                    crop.left,
                    crop.top,
                    crop.right - crop.left,
                    crop.bottom - crop.top,
                )
                try {
                    val independent = cropped.copy(Bitmap.Config.ARGB_8888, false)
                        ?: throw IllegalStateException("Unable to copy cropped screenshot")
                    return CapturedRegion(
                        independent,
                        PixelPoint(crop.left, crop.top),
                    )
                } finally {
                    if (cropped !== fullSoftware) cropped.recycle()
                }
            } finally {
                fullSoftware.recycle()
            }
        } finally {
            buffer.close()
        }
    }

    private fun Bitmap.toGrayImage() = IntArray(width * height).let { pixels ->
        getPixels(pixels, 0, width, 0, 0, width, height)
        VisualMatcher.fromArgb(width, height, pixels)
    }

    private fun finishCapture() {
        synchronized(lock) {
            captureInFlight = false
        }
    }

    private fun templateFile(stage: Stage): File =
        File(File(filesDir, TEMPLATE_DIRECTORY), "stage-${stage.number}.png")

    override fun close() {
        callbackExecutor.shutdownNow()
        processingExecutor.shutdownNow()
    }

    private data class CapturedRegion(
        val bitmap: Bitmap,
        val origin: PixelPoint,
    )

    private class ScreenshotFailureException(
        errorCode: Int,
    ) : RuntimeException("Accessibility screenshot failed with code $errorCode")

    private val Stage.number: Int
        get() = when (this) {
            Stage.STAGE_1 -> 1
            Stage.STAGE_2 -> 2
            Stage.STAGE_3 -> 3
        }

    private companion object {
        const val TEMPLATE_DIRECTORY = "templates"
    }
}
