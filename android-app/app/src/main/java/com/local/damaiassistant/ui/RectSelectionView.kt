package com.local.damaiassistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.local.damaiassistant.config.NormalizedRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RectSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint().apply { color = 0x66000000 }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    private var bitmap: Bitmap? = null
    private var sourceSelection: RectF? = null
    private var dragMode = DragMode.CREATE
    private var activeCorner = Corner.BOTTOM_RIGHT
    private var downPoint = ImagePoint(0f, 0f)
    private var startingSelection: RectF? = null

    fun setBitmap(bitmap: Bitmap) {
        this.bitmap = bitmap
        invalidate()
    }

    fun clearBitmap() {
        bitmap = null
        sourceSelection = null
        invalidate()
    }

    fun setSelection(rect: NormalizedRect?) {
        val image = bitmap ?: return
        sourceSelection = rect?.let {
            RectF(
                it.left * image.width,
                it.top * image.height,
                it.right * image.width,
                it.bottom * image.height,
            )
        }
        invalidate()
    }

    fun selection(): NormalizedRect? {
        val image = bitmap ?: return null
        val rect = sourceSelection ?: return null
        if (rect.width() < MIN_SOURCE_SIZE || rect.height() < MIN_SOURCE_SIZE) return null
        return selectionToNormalized(
            FloatSelection(rect.left, rect.top, rect.right, rect.bottom),
            image.width,
            image.height,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        if (width <= 0 || height <= 0) return
        val transform = transform(image)
        val destination = RectF(
            transform.offsetX,
            transform.offsetY,
            transform.offsetX + image.width * transform.scale,
            transform.offsetY + image.height * transform.scale,
        )
        canvas.drawBitmap(image, null, destination, bitmapPaint)

        sourceSelection?.let { source ->
            val topLeft = transform.imageToView(ImagePoint(source.left, source.top))
            val bottomRight = transform.imageToView(ImagePoint(source.right, source.bottom))
            val viewRect = RectF(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
            canvas.drawRect(0f, 0f, width.toFloat(), viewRect.top, shadePaint)
            canvas.drawRect(0f, viewRect.bottom, width.toFloat(), height.toFloat(), shadePaint)
            canvas.drawRect(0f, viewRect.top, viewRect.left, viewRect.bottom, shadePaint)
            canvas.drawRect(viewRect.right, viewRect.top, width.toFloat(), viewRect.bottom, shadePaint)
            canvas.drawRect(viewRect, selectionPaint)
            corners(viewRect).forEach { point ->
                canvas.drawCircle(point.x, point.y, HANDLE_RADIUS, handlePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val image = bitmap ?: return false
        if (width <= 0 || height <= 0) return false
        val transform = transform(image)
        val point = transform.viewToImage(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downPoint = point
                startingSelection = sourceSelection?.let(::RectF)
                chooseDragMode(point, transform.scale)
                if (dragMode == DragMode.CREATE) {
                    sourceSelection = RectF(point.x, point.y, point.x, point.y)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateSelection(point, image.width.toFloat(), image.height.toFloat())
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                normalizeSelection()
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun chooseDragMode(point: ImagePoint, scale: Float) {
        val current = sourceSelection
        if (current == null) {
            dragMode = DragMode.CREATE
            return
        }
        val threshold = HANDLE_HIT_RADIUS / scale
        val nearest = Corner.entries.minBy { corner ->
            val cornerPoint = corner.sourcePoint(current)
            abs(cornerPoint.x - point.x) + abs(cornerPoint.y - point.y)
        }
        val nearestPoint = nearest.sourcePoint(current)
        if (
            abs(nearestPoint.x - point.x) <= threshold &&
            abs(nearestPoint.y - point.y) <= threshold
        ) {
            dragMode = DragMode.RESIZE
            activeCorner = nearest
        } else if (current.contains(point.x, point.y)) {
            dragMode = DragMode.MOVE
        } else {
            dragMode = DragMode.CREATE
        }
    }

    private fun updateSelection(point: ImagePoint, maxWidth: Float, maxHeight: Float) {
        val current = sourceSelection ?: return
        when (dragMode) {
            DragMode.CREATE -> {
                current.set(downPoint.x, downPoint.y, point.x, point.y)
            }

            DragMode.MOVE -> {
                val start = startingSelection ?: return
                val dx = point.x - downPoint.x
                val dy = point.y - downPoint.y
                val clampedDx = dx.coerceIn(-start.left, maxWidth - start.right)
                val clampedDy = dy.coerceIn(-start.top, maxHeight - start.bottom)
                current.set(start)
                current.offset(clampedDx, clampedDy)
            }

            DragMode.RESIZE -> {
                val start = startingSelection ?: return
                current.set(start)
                when (activeCorner) {
                    Corner.TOP_LEFT -> {
                        current.left = point.x
                        current.top = point.y
                    }

                    Corner.TOP_RIGHT -> {
                        current.right = point.x
                        current.top = point.y
                    }

                    Corner.BOTTOM_LEFT -> {
                        current.left = point.x
                        current.bottom = point.y
                    }

                    Corner.BOTTOM_RIGHT -> {
                        current.right = point.x
                        current.bottom = point.y
                    }
                }
            }
        }
        current.left = current.left.coerceIn(0f, maxWidth)
        current.right = current.right.coerceIn(0f, maxWidth)
        current.top = current.top.coerceIn(0f, maxHeight)
        current.bottom = current.bottom.coerceIn(0f, maxHeight)
    }

    private fun normalizeSelection() {
        val rect = sourceSelection ?: return
        rect.set(
            min(rect.left, rect.right),
            min(rect.top, rect.bottom),
            max(rect.left, rect.right),
            max(rect.top, rect.bottom),
        )
    }

    private fun transform(image: Bitmap): FitCenterTransform =
        FitCenterTransform.create(image.width, image.height, width, height)

    private fun corners(rect: RectF): List<ImagePoint> = listOf(
        ImagePoint(rect.left, rect.top),
        ImagePoint(rect.right, rect.top),
        ImagePoint(rect.left, rect.bottom),
        ImagePoint(rect.right, rect.bottom),
    )

    private enum class DragMode {
        CREATE,
        MOVE,
        RESIZE,
    }

    private enum class Corner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT;

        fun sourcePoint(rect: RectF): ImagePoint = when (this) {
            TOP_LEFT -> ImagePoint(rect.left, rect.top)
            TOP_RIGHT -> ImagePoint(rect.right, rect.top)
            BOTTOM_LEFT -> ImagePoint(rect.left, rect.bottom)
            BOTTOM_RIGHT -> ImagePoint(rect.right, rect.bottom)
        }
    }

    private companion object {
        const val MIN_SOURCE_SIZE = 20f
        const val HANDLE_RADIUS = 12f
        const val HANDLE_HIT_RADIUS = 32f
    }
}
