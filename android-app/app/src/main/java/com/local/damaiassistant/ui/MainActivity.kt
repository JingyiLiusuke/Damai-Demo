package com.local.damaiassistant.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.local.damaiassistant.DamaiAssistantApp
import com.local.damaiassistant.PendingCalibration
import com.local.damaiassistant.R
import com.local.damaiassistant.automation.InputMode
import com.local.damaiassistant.automation.ShizukuShellTapper
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
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var app: DamaiAssistantApp
    private lateinit var repository: ConfigRepository
    private lateinit var targetTime: EditText
    private lateinit var offset: EditText
    private lateinit var resultTexts: EditText
    private lateinit var visualFallback: Switch
    private lateinit var lowLatency: Switch
    private lateinit var visualFallbackDelay: EditText
    private lateinit var testNow: Switch
    private lateinit var accessibilityStatus: TextView
    private lateinit var shizukuStatus: TextView
    private lateinit var runState: TextView
    private lateinit var recentLog: TextView
    private lateinit var armButton: Button
    private lateinit var stopButton: Button
    private lateinit var debugCaptureButton: Button
    private var snapshotSubscription: AutoCloseable? = null
    private var foregroundAwaitHandle: ForegroundAwaitHandle? = null
    private var resumed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuShellTapper.REQUEST_CODE) {
                showMessage(
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        "Shizuku 已授权，请再次点击“开始待命”。"
                    } else {
                        "Shizuku 授权被拒绝。"
                    },
                )
                refreshShizukuStatus(prewarm = grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }

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
        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        val control = app.automationControl()
        if (app.snapshot().state in ACTIVE_STATES) {
            control?.foregroundChanged(packageName)
        }
        loadConfig()
        refreshConnectionStatus()
        refreshShizukuStatus(prewarm = true)
        snapshotSubscription?.close()
        snapshotSubscription = app.addSnapshotListener { snapshot ->
            runOnUiThread { renderSnapshot(snapshot) }
        }
        deliverCompletedWork()
    }

    override fun onPause() {
        resumed = false
        snapshotSubscription?.close()
        snapshotSubscription = null
        super.onPause()
    }

    override fun onDestroy() {
        foregroundAwaitHandle?.cancel()
        foregroundAwaitHandle = null
        runCatching {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
        super.onDestroy()
    }

    private fun bindViews() {
        targetTime = findViewById(R.id.target_time_input)
        offset = findViewById(R.id.pre_trigger_offset_input)
        resultTexts = findViewById(R.id.result_text_input)
        visualFallback = findViewById(R.id.visual_fallback_switch)
        lowLatency = findViewById(R.id.low_latency_switch)
        visualFallbackDelay = findViewById(R.id.visual_fallback_delay_input)
        testNow = findViewById(R.id.test_now_switch)
        accessibilityStatus = findViewById(R.id.accessibility_status)
        shizukuStatus = findViewById(R.id.shizuku_status)
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
                        onFailure = { showMessage(getString(R.string.export_failed)) },
                    )
                }
            } ?: showMessage(getString(R.string.service_not_connected))
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
        lowLatency.isChecked = config.lowLatencyEnabled
        visualFallbackDelay.setText(config.visualFallbackDelayMillis.toString())
        findViewById<TextView>(R.id.stage1_rect_summary).text =
            formatRect(Stage.STAGE_1, config.stage1Rect)
        findViewById<TextView>(R.id.stage2_rect_summary).text =
            formatRect(Stage.STAGE_2, config.stage2Rect)
        findViewById<TextView>(R.id.stage3_rect_summary).text =
            formatRect(Stage.STAGE_3, config.stage3Rect)
    }

    private fun arm() {
        val control = app.automationControl()
            ?: return showMessage(getString(R.string.service_not_connected))
        val power = getSystemService(PowerManager::class.java)
        if (!power.isInteractive) {
            return showMessage(getString(R.string.screen_must_be_awake))
        }
        if (!ensureShizukuReady()) return

        val parsedTarget = parseTargetTime(targetTime.text.toString())
            ?: return showMessage(getString(R.string.invalid_target_time))
        val parsedOffset = offset.text.toString().toLongOrNull()
            ?.takeIf { it >= 0L }
            ?: return showMessage(getString(R.string.invalid_offset))
        val parsedFallbackDelay = visualFallbackDelay.text.toString().toLongOrNull()
            ?.takeIf { it >= 0L }
            ?: return showMessage(getString(R.string.invalid_visual_fallback_delay))
        val now = System.currentTimeMillis()
        val immediateTest = testNow.isChecked
        if (!immediateTest && parsedTarget - parsedOffset <= now) {
            return showMessage(getString(R.string.trigger_must_be_future))
        }
        val configuredResultTexts = resultTexts.text.toString()
            .split('\n', ',', '，')
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (configuredResultTexts.isEmpty()) {
            return showMessage(getString(R.string.result_text_required))
        }

        val savedConfig = repository.load().copy(
            targetEpochMillis = parsedTarget,
            preTriggerOffsetMillis = parsedOffset,
            resultTexts = configuredResultTexts,
            visualFallbackEnabled = visualFallback.isChecked,
            lowLatencyEnabled = lowLatency.isChecked,
            visualFallbackDelayMillis = parsedFallbackDelay,
        )
        val plan = ArmConfigPlan(savedConfig, immediateTest)
        repository.save(plan.savedConfig)
        Log.i(
            LOG_TAG,
            "Arm requested; immediateTest=$immediateTest target=${plan.savedConfig.targetEpochMillis}",
        )
        showInstruction(
            if (immediateTest) {
                R.string.return_to_damai_for_immediate_test
            } else {
                R.string.return_to_damai_manually
            },
        )
        control.armWhenDamaiForeground(
            config = plan.savedConfig,
            immediateTestDelayMillis = if (immediateTest) TEST_NOW_DELAY_MILLIS else null,
            timeoutMillis = DAMAI_FOREGROUND_TIMEOUT_MILLIS,
            pollIntervalMillis = DAMAI_FOREGROUND_POLL_MILLIS,
            stableMillis = DAMAI_FOREGROUND_STABLE_MILLIS,
        ) { result ->
            result.onFailure { error ->
                Log.w(LOG_TAG, "Service foreground arm failed", error)
                showMessageOnUiThread(
                    if (error.message == "Timed out waiting for Damai foreground") {
                        R.string.damai_switch_timeout
                    } else {
                        R.string.arm_failed
                    },
                )
            }
        }.onFailure {
            Log.w(LOG_TAG, "Failed to request service foreground arm", it)
            showMessageOnUiThread(R.string.arm_failed)
        }
    }

    private fun ensureShizukuReady(): Boolean {
        if (!ShizukuShellTapper.isBinderAvailable()) {
            showMessage("Shizuku 未运行，请先通过 adb 启动。")
            return false
        }
        if (ShizukuShellTapper.hasPermission()) return true
        if (ShizukuShellTapper.shouldShowRequestPermissionRationale()) {
            showMessage("请在 Shizuku 中允许本应用使用 Shizuku。")
            return false
        }
        ShizukuShellTapper.requestPermission()
        showMessage("正在请求 Shizuku 权限，授权后请再次点击“开始待命”。")
        return false
    }

    private fun showStageChooser() {
        val stages = Stage.entries.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_stage)
            .setItems(
                arrayOf(
                    getString(R.string.stage_1_name),
                    getString(R.string.stage_2_name),
                    getString(R.string.stage_3_name),
                ),
            ) { _, index ->
                beginCalibration(stages[index])
            }
            .show()
    }

    private fun beginCalibration(stage: Stage) {
        val control = app.automationControl()
            ?: return showMessage(getString(R.string.service_not_connected))
        showInstruction(R.string.switching_for_calibration)
        awaitDamaiForeground(control) {
            control.captureCalibration(stage) { result ->
                result.fold(
                    onSuccess = { bitmap ->
                        val file =
                            File(cacheDir, "pending-calibration-${stage.ordinal + 1}.png")
                        val saved = runCatching {
                            FileOutputStream(file).use { stream ->
                                check(
                                    bitmap.compress(
                                        android.graphics.Bitmap.CompressFormat.PNG,
                                        100,
                                        stream,
                                    ),
                                )
                            }
                            app.setPendingCalibration(
                                PendingCalibration(stage, file),
                            )
                            deliverCompletedWorkOnUiThread()
                        }
                        bitmap.recycle()
                        if (saved.isFailure) {
                            file.delete()
                            showMessageOnUiThread(R.string.calibration_capture_failed)
                        }
                    },
                    onFailure = {
                        showMessageOnUiThread(R.string.calibration_capture_failed)
                    },
                )
            }
        }
    }

    private fun beginDebugCapture() {
        val control = app.automationControl()
            ?: return showMessage(getString(R.string.service_not_connected))
        showInstruction(R.string.switching_for_debug)
        awaitDamaiForeground(control) {
            control.captureDebug { result ->
                result.fold(
                    onSuccess = { file ->
                        app.setCompletedExport(file)
                        deliverCompletedWorkOnUiThread()
                    },
                    onFailure = {
                        showMessageOnUiThread(R.string.debug_capture_failed)
                    },
                )
            }
        }
    }

    private fun awaitDamaiForeground(
        control: com.local.damaiassistant.AutomationControl,
        onReady: () -> Unit,
    ) {
        foregroundAwaitHandle?.cancel()
        val foregroundProbe = DamaiForegroundProbe(
            damaiPackage = DAMAI_PACKAGE,
            foregroundPackage = app::foregroundPackage,
            isDamaiActiveWindow = control::isDamaiActiveWindow,
        )
        foregroundAwaitHandle = ForegroundPackageAwaiter(
            currentPackage = foregroundProbe::currentPackage,
            nowMillis = SystemClock::elapsedRealtime,
            schedule = { delayMillis, action ->
                mainHandler.postDelayed(action, delayMillis)
            },
        ).await(
            expectedPackage = DAMAI_PACKAGE,
            timeoutMillis = DAMAI_FOREGROUND_TIMEOUT_MILLIS,
            pollIntervalMillis = DAMAI_FOREGROUND_POLL_MILLIS,
            stableMillis = DAMAI_FOREGROUND_STABLE_MILLIS,
            onReady = onReady,
            onTimeout = {
                showMessageOnUiThread(R.string.damai_switch_timeout)
            },
        )
    }

    private fun deliverCompletedWorkOnUiThread() {
        runOnUiThread {
            if (resumed) deliverCompletedWork()
        }
    }

    private fun deliverCompletedWork() {
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

    private fun refreshConnectionStatus() {
        val connected = app.automationControl() != null
        val foreground = app.foregroundPackage() ?: getString(R.string.none)
        accessibilityStatus.text = getString(
            R.string.accessibility_status_format,
            getString(if (connected) R.string.connected else R.string.disconnected),
            foreground,
        )
        refreshShizukuStatus()
    }

    private fun refreshShizukuStatus(prewarm: Boolean = false) {
        val status = ShizukuShellTapper.status()
        shizukuStatus.text = getString(
            R.string.shizuku_status_format,
            getString(
                if (status.serverRunning) {
                    R.string.shizuku_running
                } else {
                    R.string.shizuku_not_running
                },
            ),
            getString(
                if (status.authorized) {
                    R.string.shizuku_authorized
                } else {
                    R.string.shizuku_not_authorized
                },
            ),
            getString(
                if (status.bound) R.string.shizuku_bound else R.string.shizuku_not_bound,
            ),
            inputModeLabel(status.mode),
        )
        if (prewarm && status.serverRunning && status.authorized && !status.bound) {
            Thread(
                {
                    ShizukuShellTapper.warmUp()
                    runOnUiThread {
                        if (resumed) refreshShizukuStatus()
                    }
                },
                "shizuku-ui-warmup",
            ).start()
        }
    }

    private fun inputModeLabel(mode: InputMode): String = when (mode) {
        InputMode.DIRECT_INJECT -> "直接注入"
        InputMode.SHELL_INPUT -> "Shell input 兜底"
        InputMode.ACCESSIBILITY_GESTURE -> "无障碍手势"
        InputMode.UNAVAILABLE -> "尚不可用"
    }

    private fun renderSnapshot(snapshot: RuntimeSnapshot) {
        refreshConnectionStatus()
        runState.text = getString(
            R.string.run_state_format,
            stateLabel(snapshot.state),
            messageLabel(snapshot.message),
        )
        recentLog.text = app.automationControl()
            ?.recentLogs()
            ?.takeLast(5)
            ?.joinToString("\n") { entry ->
                "${categoryLabel(entry.category)}：${messageLabel(entry.message)}"
            }
            .orEmpty()
        val active = snapshot.state in ACTIVE_STATES
        armButton.isEnabled = !active
        stopButton.isEnabled = active
        debugCaptureButton.isEnabled = !active
    }

    private fun formatRect(stage: Stage, rect: NormalizedRect): String =
        "${stageLabel(stage)}：%.3f, %.3f, %.3f, %.3f".format(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
        )

    private fun stageLabel(stage: Stage): String = getString(
        when (stage) {
            Stage.STAGE_1 -> R.string.stage_1_name
            Stage.STAGE_2 -> R.string.stage_2_name
            Stage.STAGE_3 -> R.string.stage_3_name
        },
    )

    private fun stateLabel(state: RunState): String = when (state) {
        RunState.IDLE -> "空闲"
        RunState.ARMED -> "等待触发"
        RunState.STAGE_1_RESERVE -> "阶段一：立即预定"
        RunState.STAGE_2_CONFIRM_PRICE -> "阶段二：确定票价"
        RunState.STAGE_3_SUBMIT -> "阶段三：立即提交"
        RunState.DONE_PENDING_RESULT -> "已提交，等待结果确认"
        RunState.DONE -> "流程完成"
        RunState.FAILED -> "运行失败"
        RunState.CANCELLED -> "已取消"
    }

    private fun categoryLabel(category: String): String = when (category) {
        "state" -> "状态"
        "calibration" -> "校准"
        else -> category
    }

    private fun messageLabel(message: String): String = when {
        message.isBlank() -> ""
        message == "Waiting for trigger" -> "已进入待命，等待目标时间"
        message == "Stage 1 coordinate click requested" -> "已请求阶段一坐标点击"
        message == "Stage 1 coordinate retry requested" -> "正在重试阶段一坐标点击"
        message == "Node click sent; waiting for next stage" -> "节点点击已发送，等待页面进入下一阶段"
        message == "Node click failed; coordinate fallback requested" ->
            "节点点击失败，已请求坐标点击兜底"
        message == "Visual fallback requested" -> "正在执行截图模板匹配兜底"
        message == "Visual fallback did not match" -> "截图模板未匹配，准备重试节点"
        message == "Visual match coordinate click requested" -> "模板匹配成功，已请求坐标点击"
        message == "Submit click limit reached; result requires confirmation" ->
            "已达到提交点击上限，等待成功结果文字确认"
        message == "Configured result feature observed" -> "检测到配置的成功结果文字"
        message == "Automation cancelled" -> "自动化流程已取消"
        message.startsWith("Damai is no longer foreground:") -> {
            val foregroundPackage = message.substringAfter(':').trim()
            "大麦已离开前台，检测到前台应用：$foregroundPackage，流程自动取消"
        }
        message == "Stage timed out" -> "当前阶段已超时"
        message == "Stage click limit reached" -> "当前阶段已达到点击次数上限"
        message.startsWith("STAGE_2 node click requested") -> "已请求点击“确定票价”节点"
        message.startsWith("STAGE_3 node click requested") -> "已请求点击“立即提交”节点"
        message.startsWith("STAGE_2 node retry requested") -> "正在重试“确定票价”节点"
        message.startsWith("STAGE_3 node retry requested") -> "正在重试“立即提交”节点"
        message.startsWith("Captured calibration image for ") -> "阶段截图已采集"
        else -> message
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showMessageOnUiThread(messageResId: Int) {
        runOnUiThread { showMessage(getString(messageResId)) }
    }

    private fun showInstruction(messageResId: Int) {
        recentLog.text = getString(messageResId)
    }

    private companion object {
        const val CONFIG_PREFERENCES = "automation_config"
        const val LOG_TAG = "DamaiAssistant"
        const val DAMAI_PACKAGE = "cn.damai"
        const val DEFAULT_TARGET_DELAY_MILLIS = 5 * 60 * 1000L
        const val TEST_NOW_DELAY_MILLIS = 500L
        const val DAMAI_FOREGROUND_TIMEOUT_MILLIS = 10_000L
        const val DAMAI_FOREGROUND_POLL_MILLIS = 100L
        const val DAMAI_FOREGROUND_STABLE_MILLIS = 250L
        val ACTIVE_STATES = setOf(
            RunState.ARMED,
            RunState.STAGE_1_RESERVE,
            RunState.STAGE_2_CONFIRM_PRICE,
            RunState.STAGE_3_SUBMIT,
            RunState.DONE_PENDING_RESULT,
        )
    }
}
