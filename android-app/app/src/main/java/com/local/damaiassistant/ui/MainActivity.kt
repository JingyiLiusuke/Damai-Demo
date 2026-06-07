package com.local.damaiassistant.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.local.damaiassistant.DamaiAssistantApp
import com.local.damaiassistant.PendingCalibration
import com.local.damaiassistant.R
import com.local.damaiassistant.config.AutomationConfig
import com.local.damaiassistant.config.ConfigRepository
import com.local.damaiassistant.config.NormalizedRect
import com.local.damaiassistant.config.SharedPreferencesKeyValueStore
import com.local.damaiassistant.domain.RunState
import com.local.damaiassistant.domain.RuntimeSnapshot
import com.local.damaiassistant.domain.Stage
import java.time.Instant
import java.time.ZoneId
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {
    private lateinit var app: DamaiAssistantApp
    private lateinit var repository: ConfigRepository
    private lateinit var targetTime: EditText
    private lateinit var offset: EditText
    private lateinit var resultTexts: EditText
    private lateinit var visualFallback: Switch
    private lateinit var testNow: Switch
    private lateinit var accessibilityStatus: TextView
    private lateinit var runState: TextView
    private lateinit var recentLog: TextView
    private lateinit var armButton: Button
    private lateinit var stopButton: Button
    private lateinit var debugCaptureButton: Button
    private var snapshotSubscription: AutoCloseable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        app = application as DamaiAssistantApp
        repository = ConfigRepository(
            SharedPreferencesKeyValueStore(
                getSharedPreferences(CONFIG_PREFERENCES, Context.MODE_PRIVATE),
            ),
        )
        bindViews()
        bindActions()
        loadConfig()
    }

    override fun onResume() {
        super.onResume()
        val control = app.automationControl()
        if (app.snapshot().state in ACTIVE_STATES) {
            control?.stop()
        }
        loadConfig()
        refreshConnectionStatus()
        snapshotSubscription?.close()
        snapshotSubscription = app.addSnapshotListener { snapshot ->
            runOnUiThread { renderSnapshot(snapshot) }
        }
        app.consumeCompletedExport()?.let { file ->
            recentLog.text = file.absolutePath
        }
        app.consumePendingCalibration()?.let { pending ->
            startActivity(
                Intent(this, CalibrationActivity::class.java)
                    .putExtra(CalibrationActivity.EXTRA_STAGE, pending.stage.name)
                    .putExtra(
                        CalibrationActivity.EXTRA_SCREENSHOT_PATH,
                        pending.screenshot.absolutePath,
                    ),
            )
        }
    }

    override fun onPause() {
        snapshotSubscription?.close()
        snapshotSubscription = null
        super.onPause()
    }

    private fun bindViews() {
        targetTime = findViewById(R.id.target_time_input)
        offset = findViewById(R.id.pre_trigger_offset_input)
        resultTexts = findViewById(R.id.result_text_input)
        visualFallback = findViewById(R.id.visual_fallback_switch)
        testNow = findViewById(R.id.test_now_switch)
        accessibilityStatus = findViewById(R.id.accessibility_status)
        runState = findViewById(R.id.run_state)
        recentLog = findViewById(R.id.recent_log)
        armButton = findViewById(R.id.arm_button)
        stopButton = findViewById(R.id.stop_button)
        debugCaptureButton = findViewById(R.id.debug_capture_button)
    }

    private fun bindActions() {
        findViewById<Button>(R.id.open_accessibility_settings_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.calibrate_button).setOnClickListener {
            showStageChooser()
        }
        armButton.setOnClickListener { arm() }
        stopButton.setOnClickListener { app.automationControl()?.stop() }
        findViewById<Button>(R.id.export_log_button).setOnClickListener {
            app.automationControl()?.exportLog { result ->
                runOnUiThread {
                    result.fold(
                        onSuccess = { file -> recentLog.text = file.absolutePath },
                        onFailure = { error -> showMessage(error.message ?: "Export failed") },
                    )
                }
            } ?: showMessage("Accessibility service is not connected")
        }
        debugCaptureButton.setOnClickListener {
            beginDebugCapture()
        }
    }

    private fun loadConfig() {
        val config = repository.load()
        val target = if (config.targetEpochMillis > 0L) {
            Instant.ofEpochMilli(config.targetEpochMillis)
                .atZone(ZoneId.systemDefault())
                .format(TARGET_TIME_FORMATTER)
        } else {
            Instant.ofEpochMilli(System.currentTimeMillis() + DEFAULT_TARGET_DELAY_MILLIS)
                .atZone(ZoneId.systemDefault())
                .format(TARGET_TIME_FORMATTER)
        }
        targetTime.setText(target)
        offset.setText(config.preTriggerOffsetMillis.toString())
        resultTexts.setText(config.resultTexts.joinToString("\n"))
        visualFallback.isChecked = config.visualFallbackEnabled
        findViewById<TextView>(R.id.stage1_rect_summary).text =
            formatRect(Stage.STAGE_1, config.stage1Rect)
        findViewById<TextView>(R.id.stage2_rect_summary).text =
            formatRect(Stage.STAGE_2, config.stage2Rect)
        findViewById<TextView>(R.id.stage3_rect_summary).text =
            formatRect(Stage.STAGE_3, config.stage3Rect)
    }

    private fun arm() {
        val control = app.automationControl()
            ?: return showMessage("Enable and connect the accessibility service first")
        if (app.foregroundPackage() != DAMAI_PACKAGE) {
            return showMessage("Open Damai and wait for the service to observe it first")
        }
        val power = getSystemService(PowerManager::class.java)
        if (!power.isInteractive) {
            return showMessage("The screen must be awake")
        }

        val parsedTarget = parseTargetTime(targetTime.text.toString())
            ?: return showMessage("Target format: yyyy-MM-dd HH:mm:ss.SSS")
        val parsedOffset = offset.text.toString().toLongOrNull()
            ?.takeIf { it >= 0L }
            ?: return showMessage("Pre-trigger offset must be a nonnegative integer")
        val now = System.currentTimeMillis()
        val effectiveTarget = if (testNow.isChecked) {
            now + parsedOffset + TEST_NOW_DELAY_MILLIS
        } else {
            parsedTarget
        }
        if (effectiveTarget - parsedOffset <= now) {
            return showMessage("Trigger time must be in the future")
        }
        val configuredResultTexts = resultTexts.text.toString()
            .split('\n', ',', '，')
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (configuredResultTexts.isEmpty()) {
            return showMessage("At least one result text is required")
        }

        val config = repository.load().copy(
            targetEpochMillis = effectiveTarget,
            preTriggerOffsetMillis = parsedOffset,
            resultTexts = configuredResultTexts,
            visualFallbackEnabled = visualFallback.isChecked,
        )
        repository.save(config)
        control.arm(config).fold(
            onSuccess = { returnToDamai(control) },
            onFailure = { error -> showMessage(error.message ?: "Unable to arm") },
        )
    }

    private fun showStageChooser() {
        val stages = Stage.entries.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose calibration stage")
            .setItems(arrayOf("Stage 1", "Stage 2", "Stage 3")) { _, index ->
                beginCalibration(stages[index])
            }
            .show()
    }

    private fun beginCalibration(stage: Stage) {
        val control = app.automationControl()
            ?: return showMessage("Accessibility service is not connected")
        showMessage("Switching to Damai for capture. Return here after the screenshot.")
        if (!launchDamai()) return
        mainHandler.postDelayed(
            {
                if (app.foregroundPackage() != DAMAI_PACKAGE) {
                    return@postDelayed
                }
                control.captureCalibration(stage) { result ->
                    result.onSuccess { bitmap ->
                        val file = File(cacheDir, "pending-calibration-${stage.ordinal + 1}.png")
                        val saved = runCatching {
                            FileOutputStream(file).use { stream ->
                                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
                            }
                            app.setPendingCalibration(PendingCalibration(stage, file))
                        }
                        bitmap.recycle()
                        if (saved.isFailure) file.delete()
                    }
                }
            },
            CAPTURE_DELAY_MILLIS,
        )
    }

    private fun beginDebugCapture() {
        val control = app.automationControl()
            ?: return showMessage("Accessibility service is not connected")
        showMessage("Switching to Damai for debug capture. Return here when complete.")
        if (!launchDamai()) return
        mainHandler.postDelayed(
            {
                if (app.foregroundPackage() != DAMAI_PACKAGE) {
                    return@postDelayed
                }
                control.captureDebug { result ->
                    result.onSuccess(app::setCompletedExport)
                }
            },
            CAPTURE_DELAY_MILLIS,
        )
    }

    private fun returnToDamai(control: com.local.damaiassistant.AutomationControl) {
        if (!launchDamai()) {
            control.stop()
        }
    }

    private fun launchDamai(): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(DAMAI_PACKAGE)
        if (launchIntent == null) {
            showMessage("Damai is not installed")
            return false
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(launchIntent)
        return true
    }

    private fun refreshConnectionStatus() {
        val connected = app.automationControl() != null
        val foreground = app.foregroundPackage() ?: "none"
        accessibilityStatus.text =
            "Accessibility: ${if (connected) "connected" else "disconnected"}; " +
            "last package: $foreground"
    }

    private fun renderSnapshot(snapshot: RuntimeSnapshot) {
        refreshConnectionStatus()
        runState.text = "${snapshot.state}: ${snapshot.message}"
        recentLog.text = app.automationControl()
            ?.recentLogs()
            ?.takeLast(5)
            ?.joinToString("\n") { entry -> "${entry.category}: ${entry.message}" }
            .orEmpty()
        val active = snapshot.state in ACTIVE_STATES
        armButton.isEnabled = !active
        stopButton.isEnabled = active
        debugCaptureButton.isEnabled = !active
    }

    private fun formatRect(stage: Stage, rect: NormalizedRect): String =
        "$stage: %.3f, %.3f, %.3f, %.3f".format(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
        )

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val CONFIG_PREFERENCES = "automation_config"
        const val DAMAI_PACKAGE = "cn.damai"
        const val DEFAULT_TARGET_DELAY_MILLIS = 5 * 60 * 1000L
        const val TEST_NOW_DELAY_MILLIS = 500L
        const val CAPTURE_DELAY_MILLIS = 800L
        val ACTIVE_STATES = setOf(
            RunState.ARMED,
            RunState.STAGE_1_RESERVE,
            RunState.STAGE_2_CONFIRM_PRICE,
            RunState.STAGE_3_SUBMIT,
            RunState.DONE_PENDING_RESULT,
        )
    }
}
