# Android Hybrid Ticket State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a side-loaded Android 11+ APK that executes the existing three-stage Damai flow using a timed coordinate fast path, accessibility-node detection, and rate-limited local screenshot matching.

**Architecture:** Keep timing, state transitions, retry limits, and visual matching in pure Kotlin modules. A single-threaded Android runtime adapts accessibility events, gestures, screenshots, configuration, and logging into state-machine inputs and executes returned effects. The UI only edits configuration, calibrates normalized rectangles/templates, and starts or stops the runtime.

**Tech Stack:** Kotlin, Android Views, Android AccessibilityService, `dispatchGesture`, `AccessibilityService.takeScreenshot`, SharedPreferences, JUnit 4, AndroidX Test, Gradle Kotlin DSL, JDK 17.

---

## File Structure

Create the Android application under `android-app/` so the existing Python scripts remain unchanged.

```text
android-app/
  settings.gradle.kts                         Gradle repositories and module inclusion
  build.gradle.kts                            Android plugin declaration
  gradle.properties                           AndroidX and JVM settings
  app/build.gradle.kts                        Android app, test, and SDK configuration
  app/proguard-rules.pro                      Release keep rules
  app/src/main/AndroidManifest.xml            Activities and accessibility service
  app/src/main/java/com/local/damaiassistant/
    DamaiAssistantApp.kt                      Process-wide runtime registry
    config/AutomationConfig.kt                Immutable configuration model
    config/ConfigRepository.kt                SharedPreferences persistence
    domain/AutomationModels.kt                States, inputs, effects, runtime snapshot
    domain/TicketStateMachine.kt              Pure deterministic transition logic
    domain/TriggerDeadline.kt                 Wall-clock to monotonic deadline conversion
    domain/VisualMatcher.kt                   Pure local grayscale template matcher
    logging/RunLogger.kt                      Bounded structured event log and export
    runtime/AutomationCoordinator.kt          Single-threaded state-machine/effect runtime
    runtime/TriggerScheduler.kt               Precise trigger thread
    automation/NodeDetector.kt                ID/text/description/bounds node lookup
    automation/GestureController.kt           Serialized dispatchGesture adapter
    automation/ScreenshotController.kt        Screenshot throttling, crop, template load/save
    debug/DebugCaptureManager.kt               Node summary and screenshot capture
    service/DamaiAccessibilityService.kt       Android service and package/event bridge
    ui/MainActivity.kt                         Configuration and run controls
    ui/CalibrationActivity.kt                  Stage rectangle/template calibration
    ui/RectSelectionView.kt                    Screenshot rectangle selection
  app/src/main/res/
    layout/activity_main.xml
    layout/activity_calibration.xml
    values/strings.xml
    values/ids.xml
    values/themes.xml
    xml/accessibility_service_config.xml
  app/src/test/java/com/local/damaiassistant/
    config/ConfigRepositoryTest.kt
    domain/TicketStateMachineTest.kt
    domain/TriggerDeadlineTest.kt
    domain/VisualMatcherTest.kt
    logging/RunLoggerTest.kt
    runtime/AutomationCoordinatorTest.kt
    ui/UiMappingTest.kt
  app/src/androidTest/java/com/local/damaiassistant/
    automation/NodeDetectorInstrumentedTest.kt
    ui/AutomationFixtureActivity.kt
```

## Fixed First-Version Defaults

Use these defaults initially and tune them only from measured device logs:

```kotlin
const val DAMAI_PACKAGE = "cn.damai"
const val STAGE_2_VIEW_ID = "cn.damai:id/btn_buy_view"
const val STAGE_3_TEXT = "立即提交"
const val SCREENSHOT_MIN_INTERVAL_MS = 400L
const val STAGE_1_RETRY_MS = 80L
const val STAGE_2_RETRY_MS = 100L
const val STAGE_3_RETRY_MS = 120L
const val STAGE_1_MAX_CLICKS = 12
const val STAGE_2_MAX_CLICKS = 8
const val STAGE_3_MAX_CLICKS = 4
const val STAGE_1_TIMEOUT_MS = 18_000L
const val STAGE_2_TIMEOUT_MS = 15_000L
const val STAGE_3_TIMEOUT_MS = 15_000L
```

Do not copy the Python script's speculative-success behavior. A stage advances only after a next-stage feature is observed.

### Task 1: Bootstrap the Android Build

**Files:**
- Create: `android-app/settings.gradle.kts`
- Create: `android-app/build.gradle.kts`
- Create: `android-app/gradle.properties`
- Create: `android-app/app/build.gradle.kts`
- Create: `android-app/app/proguard-rules.pro`
- Create: `android-app/app/src/main/AndroidManifest.xml`
- Create: `android-app/app/src/main/res/values/strings.xml`
- Create: `android-app/app/src/main/res/values/themes.xml`
- Create: `android-app/app/src/main/java/com/local/damaiassistant/ui/MainActivity.kt`

- [ ] **Step 1: Install or select the required toolchain**

As of June 7, 2026, Temurin JDK 17.0.19, Android API 36, build-tools 36.0.0,
platform-tools 37.0.0, and command-line tools 20.0 are installed. Open a new PowerShell so it
loads the updated environment, then verify:

```powershell
java -version
& 'F:\Damai\androidsdk\cmdline-tools\latest\bin\sdkmanager.bat' --list_installed
```

Expected: Java reports 17.0.19 and the SDK list contains only `platforms;android-36`,
`build-tools;36.0.0`, and current platform tools. If a clean machine is used instead, install
Temurin 17:

```powershell
winget install --id EclipseAdoptium.Temurin.17.JDK --exact --silent
```

Install current Android command-line tools through Android Studio's SDK Manager if
`cmdline-tools\latest` is absent, then run:

```powershell
& 'F:\Damai\androidsdk\cmdline-tools\latest\bin\sdkmanager.bat' `
  'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
```

Expected: `java -version` reports 17 and SDK packages show Android 36/build-tools 36.0.0.

- [ ] **Step 2: Create the Gradle project files**

Use AGP 9.2.0, built-in Kotlin, Gradle 9.4.1, `compileSdk = 36`, `targetSdk = 36`,
and `minSdk = 30`.

```kotlin
// android-app/settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DamaiAssistant"
include(":app")
```

```kotlin
// android-app/build.gradle.kts
plugins {
    id("com.android.application") version "9.2.0" apply false
}
```

```properties
# android-app/gradle.properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
```

```kotlin
// android-app/app/build.gradle.kts
plugins {
    id("com.android.application")
}

android {
    namespace = "com.local.damaiassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.local.damaiassistant"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}
```

- [ ] **Step 3: Add the minimal manifest, theme, and launcher Activity**

The initial manifest must request no network permission and must not yet declare the
accessibility service:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.DamaiAssistant">
        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

```kotlin
package com.local.damaiassistant.ui

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = getString(R.string.app_name) })
    }
}
```

- [ ] **Step 4: Generate the wrapper and verify the empty application**

Download a temporary Gradle 9.4.1 distribution and generate the repository-owned wrapper:

```powershell
$zip = Join-Path $env:TEMP 'gradle-9.4.1-bin.zip'
$dir = Join-Path $env:TEMP 'gradle-9.4.1'
Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-9.4.1-bin.zip' -OutFile $zip
Expand-Archive -LiteralPath $zip -DestinationPath $env:TEMP -Force
& (Join-Path $dir 'bin\gradle.bat') wrapper --gradle-version 9.4.1
.\gradlew.bat testDebugUnitTest assembleDebug
```

Expected: `BUILD SUCCESSFUL` and
`android-app/app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 5: Commit**

```powershell
git add android-app
git commit -m "build: bootstrap Android assistant app"
```

### Task 2: Add Configuration and Coordinate Models

**Files:**
- Create: `android-app/app/src/main/java/com/local/damaiassistant/config/AutomationConfig.kt`
- Create: `android-app/app/src/main/java/com/local/damaiassistant/config/ConfigRepository.kt`
- Create: `android-app/app/src/test/java/com/local/damaiassistant/config/ConfigRepositoryTest.kt`

- [ ] **Step 1: Write failing tests for normalized rectangles and persistence**

```kotlin
class ConfigRepositoryTest {
    @Test
    fun normalizedRect_convertsToPixelsAndClamps() {
        val rect = NormalizedRect(0.75f, 0.90f, 1.10f, 1.20f)
        assertEquals(PixelRect(750, 1800, 1000, 2000), rect.toPixels(1000, 2000))
    }

    @Test
    fun configRoundTrip_preservesTimingAndStageRects() {
        val prefs = FakeKeyValueStore()
        val repository = ConfigRepository(prefs)
        val expected = AutomationConfig.defaults().copy(
            targetEpochMillis = 1_800_000_000_000L,
            preTriggerOffsetMillis = 100L,
            stage1Rect = NormalizedRect(0.63f, 0.86f, 0.98f, 0.89f),
        )

        repository.save(expected)

        assertEquals(expected, repository.load())
    }
}

private class FakeKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, Any>()

    override fun putLong(key: String, value: Long) { values[key] = value }
    override fun putInt(key: String, value: Int) { values[key] = value }
    override fun putFloat(key: String, value: Float) { values[key] = value }
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun putString(key: String, value: String) { values[key] = value }
    override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
    override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
    override fun getFloat(key: String, default: Float) = values[key] as? Float ?: default
    override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    override fun getString(key: String, default: String) = values[key] as? String ?: default
}
```

- [ ] **Step 2: Run the focused test and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ConfigRepositoryTest'
```

Expected: compilation fails because the configuration classes do not exist.

- [ ] **Step 3: Implement immutable configuration and a key-value adapter**

`AutomationConfig` must contain target time, trigger offset, three normalized rectangles,
per-stage timeout/retry/click limits, screenshot throttle, visual thresholds, result texts,
and `visualFallbackEnabled`.

```kotlin
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left < right && top < bottom)
    }

    fun toPixels(width: Int, height: Int): PixelRect = PixelRect(
        left = (left.coerceIn(0f, 1f) * width).toInt(),
        top = (top.coerceIn(0f, 1f) * height).toInt(),
        right = (right.coerceIn(0f, 1f) * width).toInt(),
        bottom = (bottom.coerceIn(0f, 1f) * height).toInt(),
    )
}

data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun center(): PixelPoint = PixelPoint((left + right) / 2, (top + bottom) / 2)
}

data class PixelPoint(val x: Int, val y: Int)
```

Define a `KeyValueStore` interface matching the fake's typed getters/setters, implement
`SharedPreferencesKeyValueStore`, and persist every field under a stable explicit key.
Do not serialize the complete object with Java serialization.

- [ ] **Step 4: Run tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ConfigRepositoryTest'
```

Expected: both tests pass.

- [ ] **Step 5: Commit**

```powershell
git add android-app/app/src/main/java/com/local/damaiassistant/config `
        android-app/app/src/test/java/com/local/damaiassistant/config
git commit -m "feat: add automation configuration model"
```

### Task 3: Implement Timing and the Pure State Machine

**Files:**
- Create: `android-app/app/src/main/java/com/local/damaiassistant/domain/AutomationModels.kt`
- Create: `android-app/app/src/main/java/com/local/damaiassistant/domain/TriggerDeadline.kt`
- Create: `android-app/app/src/main/java/com/local/damaiassistant/domain/TicketStateMachine.kt`
- Create: `android-app/app/src/test/java/com/local/damaiassistant/domain/TriggerDeadlineTest.kt`
- Create: `android-app/app/src/test/java/com/local/damaiassistant/domain/TicketStateMachineTest.kt`

- [ ] **Step 1: Write failing trigger deadline tests**

```kotlin
class TriggerDeadlineTest {
    @Test
    fun computesMonotonicDeadlineFromWallClockDelta() {
        assertEquals(
            7_900_000_000L,
            TriggerDeadline.compute(
                targetWallMillis = 10_000L,
                preTriggerOffsetMillis = 100L,
                nowWallMillis = 2_000L,
                nowElapsedNanos = 0L,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPastTrigger() {
        TriggerDeadline.compute(1_000L, 0L, 1_001L, 0L)
    }
}
```

- [ ] **Step 2: Write failing state-machine tests**

Cover all critical rules:

```kotlin
@Test
fun triggerStartsStage1WithImmediateCoordinateClick() {
    val transition = machine.reduce(armedSnapshot(), Input.Trigger, config, now = 10L)
    assertEquals(RunState.STAGE_1_RESERVE, transition.snapshot.state)
    assertEquals(listOf(Effect.ClickCoordinate(Stage.STAGE_1)), transition.effects)
}

@Test
fun stage1OnlyAdvancesWhenStage2FeatureIsObserved() {
    val transition = machine.reduce(stage1Snapshot(), Input.FeatureObserved(Stage.STAGE_2), config, 20L)
    assertEquals(RunState.STAGE_2_CONFIRM_PRICE, transition.snapshot.state)
    assertEquals(Effect.ClickNode(Stage.STAGE_2), transition.effects.first())
}

@Test
fun staleGestureCallbackCannotChangeNewGeneration() {
    val transition = machine.reduce(
        stage2Snapshot(generation = 4),
        Input.GestureFinished(generation = 3, succeeded = true),
        config,
        30L,
    )
    assertEquals(stage2Snapshot(generation = 4), transition.snapshot)
    assertTrue(transition.effects.isEmpty())
}

@Test
fun stage3ClickLimitStopsAtPendingResult() {
    val snapshot = stage3Snapshot(clicks = config.stage3.maxClicks)
    val transition = machine.reduce(snapshot, Input.Tick, config, 40L)
    assertEquals(RunState.DONE_PENDING_RESULT, transition.snapshot.state)
    assertTrue(transition.effects.none { it is Effect.ClickCoordinate })
}

@Test
fun packageChangeCancelsActiveRun() {
    val transition = machine.reduce(stage1Snapshot(), Input.ForegroundPackage("other.app"), config, 50L)
    assertEquals(RunState.CANCELLED, transition.snapshot.state)
    assertEquals(listOf(Effect.CancelPendingWork), transition.effects)
}
```

Also test stop, service disconnect, timeout, screenshot limit, visual match/no-match,
gesture cancellation, result detection, duplicate events, and stage-specific retry delay.

- [ ] **Step 3: Run tests and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*TriggerDeadlineTest' --tests '*TicketStateMachineTest'
```

Expected: compilation fails because domain classes do not exist.

- [ ] **Step 4: Implement domain types**

Use enums and immutable data:

```kotlin
enum class RunState {
    IDLE, ARMED, STAGE_1_RESERVE, STAGE_2_CONFIRM_PRICE, STAGE_3_SUBMIT,
    DONE_PENDING_RESULT, DONE, FAILED, CANCELLED,
}

enum class Stage { STAGE_1, STAGE_2, STAGE_3 }

data class RuntimeSnapshot(
    val state: RunState = RunState.IDLE,
    val generation: Long = 0,
    val enteredAtNanos: Long = 0,
    val clickCount: Int = 0,
    val screenshotCount: Int = 0,
    val gestureInFlight: Boolean = false,
    val lastClickAtNanos: Long? = null,
    val message: String = "",
)

sealed interface Input {
    data object Arm : Input
    data object Trigger : Input
    data object Tick : Input
    data object Stop : Input
    data object ServiceDisconnected : Input
    data class ForegroundPackage(val packageName: String?) : Input
    data class FeatureObserved(val stage: Stage) : Input
    data class GestureFinished(val generation: Long, val succeeded: Boolean) : Input
    data class NodeClickFinished(val generation: Long, val succeeded: Boolean) : Input
    data class VisualFinished(
        val generation: Long,
        val stage: Stage,
        val match: PixelPoint?,
    ) : Input
    data object ResultObserved : Input
    data class FatalError(val reason: String) : Input
}

sealed interface Effect {
    data class ScheduleTrigger(val elapsedDeadlineNanos: Long) : Effect
    data class ScheduleTick(val delayMillis: Long, val generation: Long) : Effect
    data class InspectCurrentWindow(val expectedStage: Stage) : Effect
    data class ClickNode(val stage: Stage) : Effect
    data class ClickCoordinate(val stage: Stage, val point: PixelPoint? = null) : Effect
    data class CaptureAndMatch(val stage: Stage) : Effect
    data object CancelPendingWork : Effect
    data class Publish(val snapshot: RuntimeSnapshot) : Effect
}

data class Transition(val snapshot: RuntimeSnapshot, val effects: List<Effect>)
```

- [ ] **Step 5: Implement the reducer**

`TicketStateMachine.reduce()` must:

1. Reject stale callbacks whose generation differs.
2. Convert `Trigger` in `ARMED` to stage one and emit an immediate coordinate click.
3. Advance only on `FeatureObserved(nextStage)`.
4. For stage two and three, try node click first, then coordinate after a failed node click,
   then screenshot only after the next tick still sees no next-stage feature.
5. Enforce timeout, click count, screenshot count, retry delay, and one in-flight gesture.
6. Enter `DONE_PENDING_RESULT` after stage-three click limit.
7. Enter `DONE` only after `ResultObserved`.
8. Cancel on stop, service disconnect, or package change.

Keep `reduce()` free of Android types, threads, clocks, and I/O.

- [ ] **Step 6: Run tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*TriggerDeadlineTest' --tests '*TicketStateMachineTest'
```

Expected: all timing and state-machine tests pass.

- [ ] **Step 7: Commit**

```powershell
git add android-app/app/src/main/java/com/local/damaiassistant/domain `
        android-app/app/src/test/java/com/local/damaiassistant/domain
git commit -m "feat: add deterministic ticket state machine"
```

### Task 4: Add Structured Logging

**Files:**
- Create: `android-app/app/src/main/java/com/local/damaiassistant/logging/RunLogger.kt`
- Create: `android-app/app/src/test/java/com/local/damaiassistant/logging/RunLoggerTest.kt`

- [ ] **Step 1: Write failing bounded-log and redaction tests**

```kotlin
class RunLoggerTest {
    @Test
    fun keepsOnlyNewestEntries() {
        val logger = RunLogger(capacity = 2)
        logger.record("state", "one", 1L, 1L)
        logger.record("state", "two", 2L, 2L)
        logger.record("state", "three", 3L, 3L)
        assertEquals(listOf("two", "three"), logger.snapshot().map { it.message })
    }

    @Test
    fun redactsLongDigitSequences() {
        val logger = RunLogger(capacity = 2)
        logger.record("node", "phone=13800138000 id=123456789012345678", 1L, 1L)
        assertFalse(logger.snapshot().single().message.contains("13800138000"))
    }
}
```

- [ ] **Step 2: Run and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*RunLoggerTest'
```

Expected: compilation fails because `RunLogger` does not exist.

- [ ] **Step 3: Implement the logger**

Use a synchronized `ArrayDeque<RunLogEntry>`, a fixed capacity, tab-separated export, and
redact digit runs of 7 or more characters:

```kotlin
data class RunLogEntry(
    val category: String,
    val message: String,
    val wallMillis: Long,
    val elapsedNanos: Long,
)

private val sensitiveDigits = Regex("""\d{7,}""")
```

Expose `record()`, `snapshot()`, `clear()`, and `writeTo(file: File)`. Export timestamps,
category, and sanitized message only.

- [ ] **Step 4: Run tests and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*RunLoggerTest'
git add android-app/app/src/main/java/com/local/damaiassistant/logging `
        android-app/app/src/test/java/com/local/damaiassistant/logging
git commit -m "feat: add bounded automation run logging"
```

### Task 5: Implement the Local Visual Matcher

**Files:**
- Create: `android-app/app/src/main/java/com/local/damaiassistant/domain/VisualMatcher.kt`
- Create: `android-app/app/src/test/java/com/local/damaiassistant/domain/VisualMatcherTest.kt`

- [ ] **Step 1: Write failing matcher tests**

```kotlin
class VisualMatcherTest {
    @Test
    fun identicalImagesScoreOne() {
        val pixels = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt())
        assertEquals(1.0, VisualMatcher.score(pixels, pixels), 0.0001)
    }

    @Test
    fun locatesTemplateInsideSearchImage() {
        val search = GrayImage(4, 3, intArrayOf(
            0, 0, 0, 0,
            0, 20, 40, 0,
            0, 60, 80, 0,
        ))
        val template = GrayImage(2, 2, intArrayOf(20, 40, 60, 80))
        val result = VisualMatcher.findBest(search, template)
        assertEquals(PixelPoint(2, 2), result.center)
        assertTrue(result.score >= 0.99)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedPixelCounts() {
        VisualMatcher.score(intArrayOf(0), intArrayOf(0, 1))
    }
}
```

- [ ] **Step 2: Run and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*VisualMatcherTest'
```

Expected: compilation fails because visual matcher types do not exist.

- [ ] **Step 3: Implement grayscale conversion and normalized absolute difference**

Define:

```kotlin
data class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
    }
}

data class VisualMatch(val center: PixelPoint, val score: Double)
```

Convert ARGB to luminance using integer weights `(77*r + 150*g + 29*b) shr 8`.
Calculate a score of `1.0 - meanAbsoluteDifference / 255.0`. Search only within the already
cropped stage region, downscale both images to a maximum width of 160 pixels, and reject a
template larger than the search image.

- [ ] **Step 4: Run tests and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*VisualMatcherTest'
git add android-app/app/src/main/java/com/local/damaiassistant/domain/VisualMatcher.kt `
        android-app/app/src/test/java/com/local/damaiassistant/domain/VisualMatcherTest.kt
git commit -m "feat: add local visual template matcher"
```

### Task 6: Add Android Node, Gesture, and Screenshot Adapters

**Files:**
- Create: `android-app/app/src/main/java/com/local/damaiassistant/automation/NodeDetector.kt`
- Create: `android-app/app/src/main/java/com/local/damaiassistant/automation/GestureController.kt`
- Create: `android-app/app/src/main/java/com/local/damaiassistant/automation/ScreenshotController.kt`
- Create: `android-app/app/src/androidTest/java/com/local/damaiassistant/ui/AutomationFixtureActivity.kt`
- Create: `android-app/app/src/androidTest/java/com/local/damaiassistant/automation/NodeDetectorInstrumentedTest.kt`
- Create: `android-app/app/src/main/res/values/ids.xml`

- [ ] **Step 1: Create the fixture Activity and failing node tests**

The fixture must render:

```kotlin
LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    addView(Button(context).apply {
        id = R.id.fixture_stage2
        text = "确定票价"
    })
    addView(FrameLayout(context).apply {
        isClickable = true
        addView(TextView(context).apply { text = "立即提交" })
    })
}
```

Declare the stable fixture ID:

```xml
<resources>
    <item name="fixture_stage2" type="id" />
</resources>
```

Instrumented tests must launch it, get
`InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow`, and verify:

1. ID lookup returns the stage-two button.
2. Exact-text lookup returns the text node.
3. `clickableNode()` climbs from the text node to its clickable parent.

- [ ] **Step 2: Run and verify failure**

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.local.damaiassistant.automation.NodeDetectorInstrumentedTest
```

Expected: compilation fails because `NodeDetector` does not exist.

- [ ] **Step 3: Implement `NodeDetector`**

Expose:

```kotlin
class NodeDetector {
    fun byViewId(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo?
    fun byExactText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo?
    fun byDescription(root: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo?
    fun clickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo?
    fun clickableInside(root: AccessibilityNodeInfo, bounds: Rect): AccessibilityNodeInfo?
}
```

Use Android's direct `findAccessibilityNodeInfosByViewId()` and
`findAccessibilityNodeInfosByText()` before bounded depth-first traversal. Recycle only nodes
created as copies by this class; do not recycle the service-owned root.

- [ ] **Step 4: Implement serialized gestures**

`GestureController` must reject a second request while one gesture is in flight and invoke one
completion callback:

```kotlin
fun click(point: PixelPoint, generation: Long, callback: (Long, Boolean) -> Unit): Boolean
```

Build a zero-duration path with one `moveTo()`, use a 1 ms stroke, and clear the in-flight flag
from both `onCompleted()` and `onCancelled()`.

- [ ] **Step 5: Implement screenshot acquisition and throttling**

`ScreenshotController.capture()` must:

1. Reject concurrent captures.
2. Reject captures less than `screenshotMinIntervalMillis` apart.
3. Call `AccessibilityService.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)`.
4. Wrap the returned `HardwareBuffer`, copy to `ARGB_8888`, close the hardware buffer, and
   return a software Bitmap.
5. Crop only the normalized stage rectangle.
6. Load/save stage templates under `filesDir/templates/stage-N.png`.
7. Invoke `VisualMatcher` off the accessibility callback thread.

Always recycle temporary Bitmap copies after use.

- [ ] **Step 6: Run adapter tests and assemble**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug
```

Expected: unit tests and `NodeDetectorInstrumentedTest` pass.

- [ ] **Step 7: Commit**

```powershell
git add android-app/app/src/main/java/com/local/damaiassistant/automation `
        android-app/app/src/androidTest
git commit -m "feat: add Android automation adapters"
```

### Task 7: Build the Coordinator and Precise Trigger Scheduler

**Files:**
- Create: `android-app/app/src/main/java/com/local/damaiassistant/runtime/TriggerScheduler.kt`
- Create: `android-app/app/src/main/java/com/local/damaiassistant/runtime/AutomationCoordinator.kt`
- Create: `android-app/app/src/test/java/com/local/damaiassistant/runtime/AutomationCoordinatorTest.kt`

- [ ] **Step 1: Write failing coordinator tests using fake gateways**

Define fakes for clock, scheduler, node lookup, gestures, screenshots, and logger. Test:

```kotlin
@Test
fun armSchedulesComputedMonotonicDeadline() {
    coordinator.arm(config)
    assertEquals(expectedDeadline, scheduler.lastDeadline)
}

@Test
fun accessibilityEventsAreSerializedAndOnlyInspectCurrentStage() {
    coordinator.arm(config)
    scheduler.fire()
    coordinator.onWindowChanged("cn.damai")
    executor.runAll()
    assertEquals(listOf(Stage.STAGE_1), nodes.inspections)
}

@Test
fun stopInvalidatesPendingGestureCallback() {
    coordinator.arm(config)
    scheduler.fire()
    val oldCallback = gestures.callback
    coordinator.stop()
    oldCallback.invoke(true)
    executor.runAll()
    assertEquals(RunState.CANCELLED, coordinator.snapshot().state)
}
```

- [ ] **Step 2: Run and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*AutomationCoordinatorTest'
```

Expected: compilation fails because runtime classes do not exist.

- [ ] **Step 3: Implement `TriggerScheduler`**

Use one dedicated daemon thread. Sleep until two seconds before the deadline, then use
`LockSupport.parkNanos(minOf(remaining, 1_000_000L))` until the deadline. Cancellation increments
a token so stale scheduler callbacks are ignored. Post `Input.Trigger` to the coordinator rather
than invoking the state machine directly.

- [ ] **Step 4: Implement `AutomationCoordinator`**

Use a `HandlerThread("damai-automation")` in Android and an injected serial executor in tests.
For each input:

1. Read monotonic time from `Clock`.
2. Call `TicketStateMachine.reduce()`.
3. Store and publish the returned snapshot.
4. Execute effects in returned order.
5. Convert all asynchronous callbacks back into generation-tagged inputs on the serial executor.

Node inspection must map current window features as follows:

```text
stage 1 sees btn_buy_view       -> FeatureObserved(STAGE_2)
stage 2 sees "立即提交"          -> FeatureObserved(STAGE_3)
stage 3 sees configured result  -> ResultObserved
```

Do not hold `AccessibilityNodeInfo` objects across asynchronous boundaries.

- [ ] **Step 5: Run tests and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*AutomationCoordinatorTest'
git add android-app/app/src/main/java/com/local/damaiassistant/runtime `
        android-app/app/src/test/java/com/local/damaiassistant/runtime
git commit -m "feat: coordinate timed automation effects"
```

### Task 8: Add the Accessibility Service and Runtime Registry

**Files:**
- Create: `android-app/app/src/main/java/com/local/damaiassistant/DamaiAssistantApp.kt`
- Create: `android-app/app/src/main/java/com/local/damaiassistant/service/DamaiAccessibilityService.kt`
- Create: `android-app/app/src/main/res/xml/accessibility_service_config.xml`
- Modify: `android-app/app/src/main/AndroidManifest.xml`
- Modify: `android-app/app/src/main/res/values/strings.xml`

- [ ] **Step 1: Declare the application and accessibility service**

Add:

```xml
<service
    android:name=".service.DamaiAccessibilityService"
    android:exported="false"
    android:label="@string/accessibility_service_name"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

Use this service config:

```xml
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewScrolled"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:canTakeScreenshot="true"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="0"
    android:packageNames="cn.damai" />
```

- [ ] **Step 2: Implement the process registry**

`DamaiAssistantApp` owns a thread-safe registry containing the currently connected service
runtime and observable latest snapshot. It must not retain an Activity.

Expose:

```kotlin
interface AutomationControl {
    fun arm(config: AutomationConfig): Result<Unit>
    fun stop()
    fun captureCalibration(stage: Stage, callback: (Result<Bitmap>) -> Unit)
    fun captureDebug(callback: (Result<File>) -> Unit)
    fun snapshot(): RuntimeSnapshot
}
```

- [ ] **Step 3: Implement service lifecycle and event filtering**

`DamaiAccessibilityService` must:

1. Construct adapters and coordinator in `onServiceConnected()`.
2. Register itself in `DamaiAssistantApp`.
3. On every event, copy only `event.packageName` and `event.eventType`.
4. Ignore all packages except `cn.damai`.
5. Send package/window change into the coordinator without traversing nodes on the callback thread.
6. Call `Input.ServiceDisconnected` and unregister in `onInterrupt()`/`onDestroy()`.

Update `serviceInfo.packageNames` at connection time as a second package filter.

- [ ] **Step 4: Build and inspect the merged manifest**

```powershell
.\gradlew.bat :app:processDebugMainManifest :app:assembleDebug
```

Expected: merged manifest contains one non-exported accessibility service, no INTERNET
permission, and `canTakeScreenshot="true"`.

- [ ] **Step 5: Commit**

```powershell
git add android-app/app/src/main/AndroidManifest.xml `
        android-app/app/src/main/java/com/local/damaiassistant/DamaiAssistantApp.kt `
        android-app/app/src/main/java/com/local/damaiassistant/service `
        android-app/app/src/main/res/xml/accessibility_service_config.xml `
        android-app/app/src/main/res/values/strings.xml
git commit -m "feat: connect Damai accessibility service"
```

### Task 9: Build the Control and Calibration UI

**Files:**
- Create: `android-app/app/src/main/res/layout/activity_main.xml`
- Create: `android-app/app/src/main/res/layout/activity_calibration.xml`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/ui/MainActivity.kt`
- Create: `android-app/app/src/main/java/com/local/damaiassistant/ui/CalibrationActivity.kt`
- Create: `android-app/app/src/main/java/com/local/damaiassistant/ui/RectSelectionView.kt`
- Create: `android-app/app/src/test/java/com/local/damaiassistant/ui/UiMappingTest.kt`
- Modify: `android-app/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create the main layout**

Include fields with these exact IDs:

```text
target_time_input
pre_trigger_offset_input
stage1_rect_summary
stage2_rect_summary
stage3_rect_summary
visual_fallback_switch
result_text_input
accessibility_status
run_state
recent_log
open_accessibility_settings_button
calibrate_button
arm_button
stop_button
export_log_button
debug_capture_button
```

Use a `ScrollView` containing a vertical `LinearLayout`; do not add Compose dependencies.

- [ ] **Step 2: Implement configuration validation**

`MainActivity` must reject arm requests unless:

1. Target time parses as `yyyy-MM-dd HH:mm:ss.SSS`.
2. Trigger time is in the future, except when explicit test-now mode is selected.
3. All three rectangles exist and are valid.
4. Accessibility service is enabled and connected.
5. Foreground package most recently observed by the service is `cn.damai`.
6. The screen is interactive.

Open `Settings.ACTION_ACCESSIBILITY_SETTINGS` from the settings button. Disable `arm_button`
while a run is active. `stop_button` must remain enabled during every nonterminal state.

- [ ] **Step 3: Implement rectangle selection**

`RectSelectionView` displays the calibration Bitmap with `FIT_CENTER`, converts touch
coordinates back to bitmap coordinates, and supports drag-to-create plus drag-to-adjust.
Expose:

```kotlin
fun setBitmap(bitmap: Bitmap)
fun setSelection(rect: NormalizedRect?)
fun selection(): NormalizedRect?
```

Reject rectangles smaller than 20 x 20 source pixels.

- [ ] **Step 4: Implement template capture**

`CalibrationActivity` accepts a `Stage`, asks the service for a screenshot, lets the user choose
the rectangle, and on save:

1. Persists the normalized rectangle.
2. Crops the selected bitmap.
3. Writes `filesDir/templates/stage-N.png`.
4. Displays the saved dimensions and path.

Never display or export a full-screen screenshot after calibration is closed.

- [ ] **Step 5: Add Activity tests for parsing and rectangle transforms**

Extract target-time parsing and bitmap/view coordinate transforms into pure functions and test:

```kotlin
assertEquals(
    NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f),
    selectionToNormalized(RectF(50f, 100f, 150f, 300f), imageWidth = 200, imageHeight = 400),
)
```

- [ ] **Step 6: Run tests and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
git add android-app/app/src/main/java/com/local/damaiassistant/ui `
        android-app/app/src/main/res/layout `
        android-app/app/src/main/AndroidManifest.xml
git commit -m "feat: add run controls and stage calibration"
```

### Task 10: Add Debug Capture and Log Export

**Files:**
- Create: `android-app/app/src/main/java/com/local/damaiassistant/debug/DebugCaptureManager.kt`
- Modify: `android-app/app/src/main/AndroidManifest.xml`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/ui/MainActivity.kt`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/service/DamaiAccessibilityService.kt`

- [ ] **Step 1: Implement bounded node summaries**

The debug traversal may walk the active tree but must cap output at 500 nodes and depth 30.
Write one line per node with:

```text
depth | class | viewId | text(redacted) | description(redacted) | clickable | bounds
```

Do not include raw text longer than 80 characters. Apply `RunLogger` redaction before writing.

- [ ] **Step 2: Capture a debug bundle**

Create:

```text
cache/debug-capture-<timestamp>/
  nodes.txt
  screen.png
  config.txt
  run-log.txt
```

`config.txt` excludes account data and contains only timings, normalized rectangles, thresholds,
and enabled feature flags. Use Android's ZIP APIs to create one archive in `cache/exports/`.

- [ ] **Step 3: Configure secure sharing**

Declare a non-exported `FileProvider` with
`androidx.core.content.FileProvider` only if AndroidX Core is added. To avoid adding that
dependency, first-version export writes the absolute internal path into the UI and supports
`adb pull` from a debug build:

```powershell
adb shell run-as com.local.damaiassistant ls files/exports
adb exec-out run-as com.local.damaiassistant cat files/exports/<file>.zip > capture.zip
```

For run logs, use the same internal export directory. Do not use world-readable files.

- [ ] **Step 4: Wire buttons and verify**

`debug_capture_button` must be disabled during an active stage to avoid screenshot contention.
`export_log_button` may operate at any time.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Expected: build passes and no storage permission appears in the merged manifest.

- [ ] **Step 5: Commit**

```powershell
git add android-app/app/src/main/java/com/local/damaiassistant/debug `
        android-app/app/src/main/java/com/local/damaiassistant/ui/MainActivity.kt `
        android-app/app/src/main/java/com/local/damaiassistant/service/DamaiAccessibilityService.kt `
        android-app/app/src/main/AndroidManifest.xml
git commit -m "feat: add local debug capture and log export"
```

### Task 11: Verify the Complete Automated Test Matrix

**Files:**
- Modify: tests created in Tasks 2-10 where coverage gaps are found

- [ ] **Step 1: Run all JVM tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: all configuration, timing, state-machine, matcher, logger, coordinator, and UI helper
tests pass.

- [ ] **Step 2: Run lint**

```powershell
.\gradlew.bat :app:lintDebug
```

Expected: no fatal errors. Fix lifecycle leaks, unrecycled Bitmaps, accessibility-service
declaration issues, and locale-sensitive formatting instead of suppressing them.

- [ ] **Step 3: Run connected instrumentation tests**

```powershell
adb devices
.\gradlew.bat :app:connectedDebugAndroidTest
```

Expected: Android 12 device is listed and all node detector/fixture tests pass.

- [ ] **Step 4: Build debug and release APKs**

```powershell
.\gradlew.bat clean :app:assembleDebug :app:assembleRelease
```

Expected: both APKs build. The release APK may remain unsigned until local signing is configured.

- [ ] **Step 5: Commit any test corrections**

```powershell
git add android-app
git commit -m "test: complete Android assistant verification"
```

Skip the commit if verification required no tracked changes.

### Task 12: Perform Safe Device Calibration and Latency Measurement

**Files:**
- Create: `docs/android-device-calibration.md`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/config/AutomationConfig.kt` only if measured conservative defaults need adjustment

- [ ] **Step 1: Install and enable the debug APK**

```powershell
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.local.damaiassistant/.ui.MainActivity
```

Manually enable the accessibility service. Confirm the service reports connected before opening
Damai.

- [ ] **Step 2: Calibrate each stage without placing an order**

Use a non-sale or harmless test path:

1. Open the relevant Damai page manually.
2. Capture the stage screenshot.
3. Select a tight button rectangle.
4. Save the stage template.
5. Repeat for all stages.
6. Export a debug capture and verify IDs/text against `damaiv2.py`.

Do not run Appium clicks concurrently. Appium Inspector may be opened read-only to compare the
node tree.

- [ ] **Step 3: Re-run the deterministic flow tests before enabling real actions**

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests '*TicketStateMachineTest' `
  --tests '*AutomationCoordinatorTest'
```

Expected: tests cover
`ARMED -> STAGE_1_RESERVE -> STAGE_2_CONFIRM_PRICE -> STAGE_3_SUBMIT`,
plus stop, package change, timeout, click limits, stale callbacks, and unknown-page failure.
Do not add a separate dry-run behavior to production code.

- [ ] **Step 4: Measure real adapter latency**

For at least 30 samples per path, record:

```text
trigger deadline -> dispatchGesture called
dispatchGesture called -> onCompleted
accessibility event received -> node found
node ACTION_CLICK -> next-stage event received
takeScreenshot called -> bitmap received
bitmap received -> visual score produced
```

Document average, p95, and maximum in `docs/android-device-calibration.md`. Adjust retry values
only when p95 data shows the default is too aggressive or too slow.

- [ ] **Step 5: Verify standalone operation**

Disconnect USB, close Appium Server, keep the phone unlocked and Damai foreground, then run a
non-order-producing test. Confirm no PC process is required.

- [ ] **Step 6: Commit calibration documentation and justified tuning**

```powershell
git add docs/android-device-calibration.md android-app
git commit -m "docs: record Android device calibration"
```

## Final Verification

Run from `android-app/`:

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug connectedDebugAndroidTest assembleDebug
git status --short
```

Expected:

- Gradle reports `BUILD SUCCESSFUL`.
- Debug APK exists.
- Android 12 connected tests pass.
- No unexpected generated files are tracked.
- Existing `damaiv2.py` user changes remain untouched.
- Active flow stops on package change, timeout, click limit, service disconnect, or user stop.
- Stage one issues a coordinate gesture at the monotonic deadline without first querying nodes.
- Stages two and three use node, coordinate, then screenshot fallback in that order.
- Screenshot matching is never polled faster than once per 400 ms.
- A sent stage-three click remains `DONE_PENDING_RESULT` until a configured result feature appears.

## Official References

- Android Gradle Plugin 9.2 compatibility:
  https://developer.android.com/build/releases/gradle-plugin
- Built-in Kotlin with AGP 9+:
  https://developer.android.com/build/migrate-to-built-in-kotlin
- Java versions used by Android builds:
  https://developer.android.com/build/jdks
- Accessibility service configuration:
  https://developer.android.com/guide/topics/ui/accessibility/service
- Accessibility gestures and screenshots:
  https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
