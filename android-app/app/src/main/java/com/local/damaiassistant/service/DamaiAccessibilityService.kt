package com.local.damaiassistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.local.damaiassistant.AutomationControl
import com.local.damaiassistant.DamaiAssistantApp
import com.local.damaiassistant.automation.GestureController
import com.local.damaiassistant.automation.CoordinateTargetResolver
import com.local.damaiassistant.automation.InputMode
import com.local.damaiassistant.automation.NodeDetector
import com.local.damaiassistant.automation.ScreenshotController
import com.local.damaiassistant.automation.ShizukuShellTapper
import com.local.damaiassistant.config.AutomationConfig
import com.local.damaiassistant.config.ConfigRepository
import com.local.damaiassistant.config.NormalizedRect
import com.local.damaiassistant.config.PixelPoint
import com.local.damaiassistant.config.PixelRect
import com.local.damaiassistant.config.SharedPreferencesKeyValueStore
import com.local.damaiassistant.debug.DebugCaptureManager
import com.local.damaiassistant.domain.RuntimeSnapshot
import com.local.damaiassistant.domain.RunState
import com.local.damaiassistant.domain.Stage
import com.local.damaiassistant.logging.RunLogger
import com.local.damaiassistant.logging.PerformanceEvent
import com.local.damaiassistant.logging.PerformanceTraceLogger
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
    private lateinit var performance: PerformanceTraceLogger
    private var wakeLock: PowerManager.WakeLock? = null
    private val foregroundArmGeneration = AtomicLong()

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
        performance = PerformanceTraceLogger(LOG_CAPACITY)
        debugCaptureManager = DebugCaptureManager(filesDir, cacheDir)

        coordinator = AutomationCoordinator(
            executor = serialExecutor,
            clock = SystemRuntimeClock,
            scheduler = scheduler,
            nodes = AccessibilityNodeGateway(detector),
            gestures = AccessibilityGestureGateway(gestureController),
            visuals = AccessibilityVisualGateway(screenshots),
            publish = { snapshot ->
                app.publishSnapshot(snapshot)
                if (
                    snapshot.state == RunState.DONE ||
                    snapshot.state == RunState.FAILED ||
                    snapshot.state == RunState.CANCELLED
                ) {
                    releaseWakeLock()
                }
            },
            log = { category, message, wallMillis, elapsedNanos ->
                Log.i(LOG_TAG, "runtime[$category] $message")
                logger.record(category, message, wallMillis, elapsedNanos)
            },
            performance = performance,
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
                eventPackage == this.packageName -> {
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
        releaseWakeLock()
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
            prepareRuntime()
            foregroundArmGeneration.incrementAndGet()
            coordinator.arm(config)
        }

        override fun armWhenDamaiForeground(
            config: AutomationConfig,
            immediateTestDelayMillis: Long?,
            timeoutMillis: Long,
            pollIntervalMillis: Long,
            stableMillis: Long,
            callback: (Result<Unit>) -> Unit,
        ): Result<Unit> = runCatching {
            check(!closed.get()) { "Accessibility service is disconnected" }
            require(immediateTestDelayMillis == null || immediateTestDelayMillis >= 0L) {
                "Immediate test delay must be nonnegative"
            }
            require(timeoutMillis > 0L) { "Foreground timeout must be positive" }
            require(pollIntervalMillis > 0L) { "Foreground poll interval must be positive" }
            require(stableMillis >= 0L) { "Foreground stable duration must be nonnegative" }

            val generation = foregroundArmGeneration.incrementAndGet()
            recordState("Waiting for Damai foreground before arming; generation=$generation")
            val posted = runtimeHandler.post {
                awaitDamaiForegroundAndArm(
                    config = config,
                    immediateTestDelayMillis = immediateTestDelayMillis,
                    timeoutMillis = timeoutMillis,
                    pollIntervalMillis = pollIntervalMillis,
                    stableMillis = stableMillis,
                    generation = generation,
                    callback = callback,
                )
            }
            check(posted) { "Automation thread is closed" }
        }

        override fun stop() {
            foregroundArmGeneration.incrementAndGet()
            if (!closed.get()) coordinator.stop()
        }

        override fun foregroundChanged(packageName: String?) {
            if (!closed.get()) coordinator.onWindowChanged(packageName)
        }

        override fun isDamaiActiveWindow(): Boolean =
            !closed.get() && this@DamaiAccessibilityService.isDamaiActiveWindow()

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
                val verifiedResult = result.fold(
                    onSuccess = { bitmap ->
                        if (isDamaiActiveWindow()) {
                            Result.success(bitmap)
                        } else {
                            bitmap.recycle()
                            Result.failure(
                                IllegalStateException("Damai left foreground during capture"),
                            )
                        }
                    },
                    onFailure = Result.Companion::failure,
                )
                logger.record(
                    category = "calibration",
                    message = "Captured calibration image for $stage",
                    wallMillis = System.currentTimeMillis(),
                    elapsedNanos = System.nanoTime(),
                )
                callback(verifiedResult)
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
                        val root = damaiRootInActiveWindow()
                        if (root == null) {
                            bitmap.recycle()
                            callback(
                                Result.failure(
                                    IllegalStateException(
                                        "Damai left foreground during debug capture",
                                    ),
                                ),
                            )
                            return@fold
                        }
                        val posted = runtimeHandler.post {
                            callback(
                                debugCaptureManager.createBundle(
                                    bitmap = bitmap,
                                    root = root,
                                    config = repository.load(),
                                    logs = logger.snapshot(),
                                    performanceTrace = performance.snapshot(),
                                ),
                            )
                        }
                        if (!posted) {
                            bitmap.recycle()
                            recycle(root)
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
            callback(
                debugCaptureManager.exportLog(
                    logger.snapshot(),
                    performance.snapshot(),
                ),
            )
        }

        override fun recentLogs() = logger.snapshot()

        override fun snapshot(): RuntimeSnapshot = coordinator.snapshot()

        private fun awaitDamaiForegroundAndArm(
            config: AutomationConfig,
            immediateTestDelayMillis: Long?,
            timeoutMillis: Long,
            pollIntervalMillis: Long,
            stableMillis: Long,
            generation: Long,
            callback: (Result<Unit>) -> Unit,
        ) {
            val startedAt = SystemClock.elapsedRealtime()
            var observedAt: Long? = null
            var lastLoggedPackage: String? = null

            fun checkForeground() {
                if (closed.get() || foregroundArmGeneration.get() != generation) return

                val now = SystemClock.elapsedRealtime()
                val currentPackage = currentForegroundForArm()
                if (currentPackage != lastLoggedPackage) {
                    lastLoggedPackage = currentPackage
                    recordState(
                        "Foreground wait observed package=$currentPackage; generation=$generation",
                    )
                }
                if (currentPackage == DAMAI_PACKAGE) {
                    val firstObservedAt = observedAt ?: now.also { observedAt = it }
                    if (now - firstObservedAt >= stableMillis) {
                        armForegroundReadyConfig(
                            config = config,
                            immediateTestDelayMillis = immediateTestDelayMillis,
                            callback = callback,
                        )
                        return
                    }
                } else {
                    observedAt = null
                }

                val elapsed = now - startedAt
                if (elapsed >= timeoutMillis) {
                    recordState("Damai foreground wait timed out; last=$currentPackage")
                    callback(
                        Result.failure(
                            IllegalStateException("Timed out waiting for Damai foreground"),
                        ),
                    )
                    return
                }
                runtimeHandler.postDelayed(
                    ::checkForeground,
                    minOf(pollIntervalMillis, timeoutMillis - elapsed),
                )
            }

            checkForeground()
        }

        private fun armForegroundReadyConfig(
            config: AutomationConfig,
            immediateTestDelayMillis: Long?,
            callback: (Result<Unit>) -> Unit,
        ) {
            val readyAt = System.currentTimeMillis()
            val runtimeConfig = try {
                if (immediateTestDelayMillis == null) {
                    config
                } else {
                    val runtimeTarget = Math.addExact(
                        Math.addExact(readyAt, config.preTriggerOffsetMillis),
                        immediateTestDelayMillis,
                    )
                    config.copy(targetEpochMillis = runtimeTarget)
                }
            } catch (exception: ArithmeticException) {
                callback(
                    Result.failure(
                        IllegalArgumentException(
                            "Immediate trigger time is outside the supported range",
                            exception,
                        ),
                    ),
                )
                return
            }

            if (runtimeConfig.targetEpochMillis - runtimeConfig.preTriggerOffsetMillis <= readyAt) {
                callback(
                    Result.failure(
                        IllegalArgumentException("Trigger time must be in the future"),
                    ),
                )
                return
            }

            recordState("Damai foreground observed; arming")
            runCatching { prepareRuntime() }.onFailure { error ->
                callback(Result.failure(error))
                return
            }
            coordinator.arm(runtimeConfig)
            callback(Result.success(Unit))
        }

        private fun currentForegroundForArm(): String? {
            val latestPackage = app.foregroundPackage()
            return if (
                latestPackage == DAMAI_PACKAGE ||
                this@DamaiAccessibilityService.isDamaiActiveWindow()
            ) {
                DAMAI_PACKAGE
            } else {
                latestPackage
            }
        }

        private fun recordState(message: String) {
            Log.i(LOG_TAG, message)
            logger.record(
                category = "state",
                message = message,
                wallMillis = System.currentTimeMillis(),
                elapsedNanos = System.nanoTime(),
            )
        }

        private fun prepareRuntime() {
            val mode = ShizukuShellTapper.warmUp()
            check(mode != InputMode.UNAVAILABLE) {
                "Shizuku input service is unavailable"
            }
            performance.record(
                event = PerformanceEvent.INPUT_WARMED,
                wallMillis = System.currentTimeMillis(),
                elapsedNanos = System.nanoTime(),
                inputMode = mode,
                foreground = app.foregroundPackage(),
            )
            acquireWakeLock()
        }
    }

    private inner class AccessibilityNodeGateway(
        private val detector: NodeDetector,
    ) : NodeGateway {
        override fun inspect(
            stage: Stage,
            resultTexts: List<String>,
        ): WindowObservation = withDamaiRoot { root ->
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
            if (closed.get() || !isDamaiActiveWindow()) return false
            val text = when (stage) {
                Stage.STAGE_1 -> return false
                Stage.STAGE_2 -> CONFIRM_PRICE_TEXT
                Stage.STAGE_3 -> SUBMIT_TEXT
            }
            val clicked = withDamaiRoot { root ->
                val target = detector.byExactText(root, text) ?: return@withDamaiRoot false
                try {
                    val clickable =
                        detector.clickableNode(target) ?: return@withDamaiRoot false
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

        private inline fun <T> withDamaiRoot(
            block: (AccessibilityNodeInfo) -> T,
        ): T? {
            val root = damaiRootInActiveWindow() ?: return null
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
            if (closed.get()) {
                Log.i(LOG_TAG, "coordinate click rejected; service closed stage=$stage")
                return false
            }
            val root = damaiRootInActiveWindow()
            if (root == null) {
                Log.i(LOG_TAG, "coordinate click rejected; Damai root missing stage=$stage")
                return false
            }
            val target = try {
                runCatching {
                    point ?: CoordinateTargetResolver.resolve(
                        bounds = bounds,
                        screenWidth = screenWidth(),
                        screenHeight = screenHeight(),
                        windowBounds = root.windowPixelRect(),
                        usableDisplayBounds = gestureUsableDisplayRect(),
                    )
                    ?: return false
                }.getOrElse { error ->
                    Log.w(LOG_TAG, "coordinate click target failed stage=$stage", error)
                    return false
                }
            } finally {
                recycle(root)
            }
            Log.i(LOG_TAG, "coordinate click requested stage=$stage x=${target.x} y=${target.y}")
            recordTapEvent(PerformanceEvent.STAGE_TAP_START, stage, null)
            val shizukuAttempt = ShizukuShellTapper.tap(target)
            if (shizukuAttempt.succeeded) {
                Log.i(
                    LOG_TAG,
                    "coordinate click finished through ${shizukuAttempt.mode} stage=$stage",
                )
                recordTapEvent(PerformanceEvent.STAGE_TAP_END, stage, shizukuAttempt.mode)
                callback(true)
                return true
            }
            Log.i(LOG_TAG, "Shizuku click unavailable; falling back to accessibility gesture")
            val accepted = gestures.click(target, requestId.incrementAndGet()) { _, succeeded ->
                Log.i(LOG_TAG, "coordinate click finished stage=$stage succeeded=$succeeded")
                recordTapEvent(
                    PerformanceEvent.STAGE_TAP_END,
                    stage,
                    InputMode.ACCESSIBILITY_GESTURE,
                    "succeeded=$succeeded",
                )
                callback(succeeded)
            }
            if (!accepted) {
                recordTapEvent(
                    PerformanceEvent.STAGE_TAP_END,
                    stage,
                    InputMode.UNAVAILABLE,
                    "gesture rejected",
                )
            }
            return accepted
        }

        override fun clearPending() {
            gestures.clearPending()
        }

        private fun AccessibilityNodeInfo.windowPixelRect(): PixelRect {
            val rect = Rect()
            getBoundsInScreen(rect)
            return PixelRect(rect.left, rect.top, rect.right, rect.bottom)
        }

        private fun recordTapEvent(
            event: PerformanceEvent,
            stage: Stage,
            inputMode: InputMode?,
            reason: String? = null,
        ) {
            performance.record(
                event = event,
                wallMillis = System.currentTimeMillis(),
                elapsedNanos = System.nanoTime(),
                stage = stage,
                inputMode = inputMode,
                foreground = DAMAI_PACKAGE,
                reason = reason,
            )
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
            if (closed.get() || !isDamaiActiveWindow()) return false
            return screenshots.captureAndMatch(stage, bounds, threshold) { result ->
                if (isDamaiActiveWindow()) {
                    callback(result)
                } else {
                    callback(
                        Result.failure(
                            IllegalStateException(
                                "Damai left foreground during visual capture",
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:automation",
            )
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun screenWidth(): Int =
        getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.width()

    private fun screenHeight(): Int =
        getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()

    private fun gestureUsableDisplayRect(): PixelRect {
        val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics
        val bounds = metrics.bounds
        val navigationInsets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.navigationBars(),
        )
        val bottomNavigationInset = maxOf(
            navigationInsets.bottom,
            navigationBarHeight(),
        )
        return PixelRect(
            left = bounds.left + navigationInsets.left,
            top = bounds.top,
            right = bounds.right - navigationInsets.right,
            bottom = bounds.bottom - bottomNavigationInset,
        )
    }

    private fun navigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun isDamaiActiveWindow(): Boolean {
        val root = damaiRootInActiveWindow() ?: return false
        recycle(root)
        return true
    }

    private fun damaiRootInActiveWindow(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() == DAMAI_PACKAGE) return root
        recycle(root)
        return null
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
        const val LOG_TAG = "DamaiAssistant"
        const val CONFIG_PREFERENCES = "automation_config"
        const val LOG_CAPACITY = 500
        const val WAKE_LOCK_TIMEOUT_MILLIS = 30L * 60L * 1_000L
        val FULL_SCREEN = NormalizedRect(0f, 0f, 1f, 1f)
        val OBSERVED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
        )
    }
}
