package com.local.damaiassistant.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.local.damaiassistant.R
import com.local.damaiassistant.config.AutomationConfig
import com.local.damaiassistant.config.ConfigRepository
import com.local.damaiassistant.config.SharedPreferencesKeyValueStore
import com.local.damaiassistant.domain.Stage
import java.io.File
import java.io.FileOutputStream

class CalibrationActivity : Activity() {
    private lateinit var repository: ConfigRepository
    private lateinit var selectionView: RectSelectionView
    private lateinit var status: TextView
    private lateinit var stage: Stage
    private var screenshot: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screenshotFile = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)?.let(::File)
        stage = intent.getStringExtra(EXTRA_STAGE)
            ?.let { runCatching { Stage.valueOf(it) }.getOrNull() }
            ?: run {
                finish()
                return
            }
        if (screenshotFile == null || !screenshotFile.isFile) {
            finish()
            return
        }
        setContentView(R.layout.activity_calibration)
        title = "Calibrate $stage"
        repository = ConfigRepository(
            SharedPreferencesKeyValueStore(
                getSharedPreferences(CONFIG_PREFERENCES, Context.MODE_PRIVATE),
            ),
        )
        selectionView = findViewById(R.id.rect_selection_view)
        status = findViewById(R.id.calibration_status)
        findViewById<Button>(R.id.save_calibration_button).setOnClickListener {
            saveCalibration()
        }
        findViewById<Button>(R.id.cancel_calibration_button).setOnClickListener {
            finish()
        }
        loadScreenshot(screenshotFile)
    }

    override fun onDestroy() {
        selectionView.clearBitmap()
        screenshot?.recycle()
        screenshot = null
        super.onDestroy()
    }

    private fun loadScreenshot(file: File) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        file.delete()
        if (bitmap == null) {
            status.text = "Unable to decode calibration screenshot"
            return
        }
        screenshot = bitmap
        selectionView.setBitmap(bitmap)
        selectionView.setSelection(repository.load().rectFor(stage))
        status.text = "Drag to select; drag inside to move; drag corners to resize"
    }

    private fun saveCalibration() {
        val bitmap = screenshot ?: return showMessage("No screenshot available")
        val selection = selectionView.selection()
            ?: return showMessage("Select an area at least 20 x 20 pixels")
        val pixels = selection.toPixels(bitmap.width, bitmap.height)
        val cropped = Bitmap.createBitmap(
            bitmap,
            pixels.left,
            pixels.top,
            pixels.right - pixels.left,
            pixels.bottom - pixels.top,
        )
        val result = runCatching {
            val directory = File(filesDir, TEMPLATE_DIRECTORY)
            check(directory.exists() || directory.mkdirs()) {
                "Unable to create template directory"
            }
            val output = File(directory, "stage-${stage.number}.png")
            FileOutputStream(output).use { stream ->
                check(cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Unable to encode template"
                }
            }
            val current = repository.load()
            repository.save(current.withRect(stage, selection))
            output
        }
        if (cropped !== bitmap) {
            cropped.recycle()
        }
        result.fold(
            onSuccess = { file ->
                status.text =
                    "Saved ${pixels.right - pixels.left} x " +
                    "${pixels.bottom - pixels.top}: ${file.absolutePath}"
            },
            onFailure = { error ->
                showMessage(error.message ?: "Unable to save calibration")
            },
        )
    }

    private fun AutomationConfig.rectFor(stage: Stage) = when (stage) {
        Stage.STAGE_1 -> stage1Rect
        Stage.STAGE_2 -> stage2Rect
        Stage.STAGE_3 -> stage3Rect
    }

    private fun AutomationConfig.withRect(
        stage: Stage,
        rect: com.local.damaiassistant.config.NormalizedRect,
    ): AutomationConfig = when (stage) {
        Stage.STAGE_1 -> copy(stage1Rect = rect)
        Stage.STAGE_2 -> copy(stage2Rect = rect)
        Stage.STAGE_3 -> copy(stage3Rect = rect)
    }

    private val Stage.number: Int
        get() = ordinal + 1

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_STAGE = "stage"
        const val EXTRA_SCREENSHOT_PATH = "screenshot_path"
        private const val CONFIG_PREFERENCES = "automation_config"
        private const val TEMPLATE_DIRECTORY = "templates"
    }
}
