package com.local.damaiassistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.local.damaiassistant.AutomationControl
import com.local.damaiassistant.DamaiAssistantApp
import com.local.damaiassistant.automation.GestureController
import com.local.damaiassistant.automation.NodeDetector
import com.local.damaiassistant.automation.ScreenshotController
import com.local.damaiassistant.config.AutomationConfig
import com.local.damaiassistant.config.ConfigRepository
import com.local.damaiassistant.config.NormalizedRect
import com.local.damaiassistant.config.PixelPoint
import com.local.damaiassistant.config.SharedPreferencesKeyValueStore
import com.local.damaiassistant.debug.DebugCaptureManager
import com.local.damaiassistant.domain.RuntimeSnapshot
import com.local.damaiassistant.domain.Stage
import com.local.damaiassistant.logging.RunLogger
import com.local.damaiassistant.runtime.AutomationCoordinator
import com.local.damaiassistant.runtime.GestureGateway
import com.local.damaiassistant.runtime.NodeGateway
import com.local.damaiassistant.runtime.SystemRuntimeClock
import com.local.damaiassistant.runtime.TriggerScheduler
import com.local.damaiassistant.runtime.VisualGateway
import com.local.damaiassistant.runtime.WindowObservation
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DamaiAccessibilityService : AccessibilityService() {
    private val closed = AtomicBoolean()
    private lateinit var app: DamaiAssistantApp
    private lateinit var runtimeThread: HandlerThread
    private lateinit var runtimeHandler: Handler
    private lateinit var scheduler: TriggerScheduler
    private lateinit var screenshots: ScreenshotController
    private lateinit var gestureController: GestureController
    private lateinit var coordinator: AutomationCoordinator
    private lateinit var control: ServiceAutomationControl
    private lateinit var debugCaptureManager: DebugCaptureManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        closed.set(false)
        app = application as DamaiAssistantApp

        serviceInfo = serviceInfo.apply { packageNames = null }

        runtimeThread = HandlerThread(RUNTIME_THREAD_NAME).apply { start() }
        runtimeHandler = Handler(runtimeThread.looper)
        val serialExecutor = Executor { command ->
            if (Looper.myLooper() == runtimeHandler.looper) {
                command.run()
            } else {
                runtimeHandler.post(command)
            }
        }

        val repository = ConfigRepository(
            SharedPreferencesKeyValueStore(
                getSharedPreferences(CONFIG_PREFERENCES, Context.MODE_PRIVATE),
            ),
        )
        val initialConfig = repository.load()
        val detector = NodeDetector()
        gestureController = GestureController(this)
        scheduler = TriggerScheduler()
        screenshots = ScreenshotController(
            service = this,
            filesDir = filesDir,
            screenshotMinIntervalMillis = initialConfig.screenshotMinIntervalMillis,
        )
        val logger = RunLogger(LOG_CAPACITY)
        debugCaptureManager = DebugCaptureManager(filesDir, cacheDir)

        coordinator = AutomationCoordinator(
            executor = serialExecutor,
            clock = SystemRuntimeClock,
            scheduler = scheduler,
            nodes = AccessibilityNodeGateway(detector),
            gestures = AccessibilityGestureGateway(gestureController),
            visuals = AccessibilityVisualGateway(screenshots),
            publish = app::publishSnapshot,
            log = logger::record,
        )
        control = ServiceAutomationControl(repository, logger)
        app.register(control)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || closed.get()) return
        val eventPackage = event.packageName?.toString()
        val eventType = event.eventType
        if (eventType !in OBSERVED_EVENT_TYPES) return

        when {
            eventPackage == DAMAI_PACKAGE -> {
                app.updateForegroundPackage(eventPackage)
                coordinator.onWindowChanged(eventPackage)
            }

            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                eventPackage != this.packageName -> {
                app.updateForegroundPackage(eventPackage)
                coordinator.onWindowChanged(eventPackage)
            }
        }
    }

    override fun onInterrupt() {
        if (!closed.get() && ::coordinator.isInitialized) {
            gestureController.clearPending()
            scheduler.cancelAll()
            coordinator.stop()
        }
    }

    override fun onDestroy() {
        shutdownRuntime()
        super.onDestroy()
    }

    private fun shutdownRuntime() {
        if (!closed.compareAndSet(false, true) || !::runtimeHandler.isInitialized) return
        app.updateForegroundPackage(null)
        app.unregister(control)
        gestureController.clearPending()
        scheduler.cancelAll()
        runtimeHandler.postAtFrontOfQueue {
            coordinator.serviceDisconnected()
            screenshots.close()
            scheduler.close()
            runtimeThread.quitSafely()
        }
    }

    private inner class ServiceAutomationControl(
        private val repository: ConfigRepository,
        private val logger: RunLogger,
    ) : AutomationControl {
        override fun arm(config: AutomationConfig): Result<Unit> = runCatching {
            check(!closed.get()) { "Accessibility service is disconnected" }
            repository.save(config)
            coordinator.arm(config)
        }

        override fun stop() {
            if (!closed.get()) coordinator.stop()
        }

        override fun captureCalibration(
            stage: Stage,
            callback: (Result<Bitmap>) -> Unit,
        ) {
            if (closed.get()) {
                callback(Result.failure(IllegalStateException("Service is disconnected")))
                return
            }
            if (!isDamaiActiveWindow()) {
                callback(Result.failure(IllegalStateException("Damai is not the active window")))
                return
            }
            val accepted = screenshots.capture(FULL_SCREEN) { result ->
                logger.record(
                    category = "calibration",
                    message = "Captured calibration image for $stage",
                    wallMillis = System.currentTimeMillis(),
                    elapsedNanos = System.nanoTime(),
                )
                callback(result)
            }
            if (!accepted) {
                callback(
                    Result.failure(
                        IllegalStateException("Screenshot request is busy or throttled"),
                    ),
                )
            }
        }

        override fun captureDebug(callback: (Result<File>) -> Unit) {
            if (closed.get()) {
                callback(Result.failure(IllegalStateException("Service is disconnected")))
                return
            }
            if (!isDamaiActiveWindow()) {
                callback(Result.failure(IllegalStateException("Damai is not the active window")))
                return
            }
            val accepted = screenshots.capture(FULL_SCREEN) { captureResult ->
                captureResult.fold(
                    onSuccess = { bitmap ->
                        val posted = runtimeHandler.post {
                            callback(
                                debugCaptureManager.createBundle(
                                    bitmap = bitmap,
                                    root = rootInActiveWindow,
                                    config = repository.load(),
                                    logs = logger.snapshot(),
                                ),
                            )
                        }
                        if (!posted) {
                            bitmap.recycle()
                            callback(
                                Result.failure(
                                    IllegalStateException("Automation thread is closed"),
                                ),
                            )
                        }
                    },
                    onFailure = { error -> callback(Result.failure(error)) },
                )
            }
            if (!accepted) {
                callback(
                    Result.failure(
                        IllegalStateException("Screenshot request is busy or throttled"),
                    ),
                )
            }
        }

        override fun exportLog(callback: (Result<File>) -> Unit) {
            callback(debugCaptureManager.exportLog(logger.snapshot()))
        }

        override fun recentLogs() = logger.snapshot()

        override fun snapshot(): RuntimeSnapshot = coordinator.snapshot()
    }

    private inner class AccessibilityNodeGateway(
        private val detector: NodeDetector,
    ) : NodeGateway {
        override fun inspect(
            stage: Stage,
            resultTexts: List<String>,
        ): WindowObservation = withRoot { root ->
            when (stage) {
                Stage.STAGE_1 -> WindowObservation(
                    buyButtonVisible = hasViewId(root, BUY_BUTTON_ID),
                )

                Stage.STAGE_2 -> WindowObservation(
                    submitTextVisible = hasText(root, SUBMIT_TEXT),
                )

                Stage.STAGE_3 -> WindowObservation(
                    resultTextVisible = resultTexts.any { text ->
                        hasText(root, text)
                    },
                )
            }
        } ?: WindowObservation()

        override fun click(stage: Stage, callback: (Boolean) -> Unit): Boolean {
            if (closed.get()) return false
            val text = when (stage) {
                Stage.STAGE_1 -> return false
                Stage.STAGE_2 -> CONFIRM_PRICE_TEXT
                Stage.STAGE_3 -> SUBMIT_TEXT
            }
            val clicked = withRoot { root ->
                val target = detector.byExactText(root, text) ?: return@withRoot false
                try {
                    val clickable = detector.clickableNode(target) ?: return@withRoot false
                    try {
                        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } finally {
                        recycle(clickable)
                    }
                } finally {
                    recycle(target)
                }
            } ?: false
            callback(clicked)
            return true
        }

        private fun hasViewId(
            root: AccessibilityNodeInfo,
            shortId: String,
        ): Boolean {
            val node = detector.byViewId(root, "$DAMAI_PACKAGE:id/$shortId")
                ?: detector.byViewId(root, shortId)
            val found = node != null
            recycle(node)
            return found
        }

        private fun hasText(
            root: AccessibilityNodeInfo,
            text: String,
        ): Boolean {
            val node = detector.byExactText(root, text)
            val found = node != null
            recycle(node)
            return found
        }

        private inline fun <T> withRoot(
            block: (AccessibilityNodeInfo) -> T,
        ): T? {
            val root = rootInActiveWindow ?: return null
            return try {
                block(root)
            } finally {
                recycle(root)
            }
        }
    }

    private inner class AccessibilityGestureGateway(
        private val gestures: GestureController,
    ) : GestureGateway {
        private val requestId = AtomicLong()

        override fun click(
            stage: Stage,
            bounds: NormalizedRect,
            point: PixelPoint?,
            callback: (Boolean) -> Unit,
        ): Boolean {
            if (closed.get()) return false
            val target = point ?: bounds.toPixels(
                screenWidth(),
                screenHeight(),
            ).center()
            return gestures.click(target, requestId.incrementAndGet()) { _, succeeded ->
                callback(succeeded)
            }
        }

        override fun clearPending() {
            gestures.clearPending()
        }
    }

    private inner class AccessibilityVisualGateway(
        private val screenshots: ScreenshotController,
    ) : VisualGateway {
        override fun captureAndMatch(
            stage: Stage,
            bounds: NormalizedRect,
            threshold: Float,
            callback: (Result<PixelPoint?>) -> Unit,
        ): Boolean {
            if (closed.get()) return false
            return screenshots.captureAndMatch(stage, bounds, threshold, callback)
        }
    }

    private fun screenWidth(): Int =
        getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.width()

    private fun screenHeight(): Int =
        getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()

    private fun isDamaiActiveWindow(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            root.packageName?.toString() == DAMAI_PACKAGE
        } finally {
            recycle(root)
        }
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo?) {
        node?.recycle()
    }

    private companion object {
        const val DAMAI_PACKAGE = "cn.damai"
        const val BUY_BUTTON_ID = "btn_buy_view"
        const val CONFIRM_PRICE_TEXT = "确定票价"
        const val SUBMIT_TEXT = "立即提交"
        const val RUNTIME_THREAD_NAME = "damai-automation"
        const val CONFIG_PREFERENCES = "automation_config"
        const val LOG_CAPACITY = 500
        val FULL_SCREEN = NormalizedRect(0f, 0f, 1f, 1f)
        val OBSERVED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
        )
    }
}
