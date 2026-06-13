# V2 Low-Latency Android Assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce the Android assistant's local hot-path latency by prewarming Shizuku input, preferring coordinate clicks in all three stages, delaying visual recovery, and exporting precise performance events.

**Architecture:** Extend the existing Shizuku UserService with a direct-injection probe and shell fallback, while exposing the active input mode to the app. Keep the existing state machine and coordinator, but change stage entry to coordinate-first and use node/visual work only after the configured recovery delay. Add a focused performance trace logger and surface readiness/configuration in the existing activity.

**Tech Stack:** Kotlin, Android AccessibilityService, Shizuku UserService/AIDL, JUnit 4, Gradle Android plugin.

---

### Task 1: Fast Input Mode Model and Fallback Policy

**Files:**
- Create: `android-app/app/src/main/java/com/local/damaiassistant/automation/InputMode.kt`
- Create: `android-app/app/src/main/java/com/local/damaiassistant/automation/InputFallbackChain.kt`
- Test: `android-app/app/src/test/java/com/local/damaiassistant/automation/InputFallbackChainTest.kt`

- [ ] Write tests proving direct injection wins, shell input is used after direct failure, and total failure reports `UNAVAILABLE`.
- [ ] Run `:app:testDebugUnitTest --tests com.local.damaiassistant.automation.InputFallbackChainTest` and verify the tests fail because the types do not exist.
- [ ] Implement `InputMode`, `InputAttempt`, and a small fallback chain with injected lambdas.
- [ ] Re-run the focused test and verify it passes.

### Task 2: Shizuku Fast Input UserService

**Files:**
- Modify: `android-app/app/src/main/aidl/com/local/damaiassistant/automation/IShizukuInputService.aidl`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/automation/ShizukuInputService.kt`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/automation/ShizukuShellTapper.kt`
- Test: `android-app/app/src/test/java/com/local/damaiassistant/automation/InputFallbackChainTest.kt`

- [ ] Extend AIDL with `warmUp()`, delayed `tap()`, and `status()`.
- [ ] Implement a reflection-based `InputManager.injectInputEvent` adapter that creates DOWN/UP events and catches all hidden-API/OEM failures.
- [ ] Keep `/system/bin/input tap` as the second attempt.
- [ ] Make `ShizukuShellTapper.warmUp()` bind before arming and cache the reported mode.
- [ ] Expose a nonblocking `status()` snapshot for UI and logging.
- [ ] Build `:app:assembleDebug` to verify AIDL generation and Android compilation.

### Task 3: Coordinate-First Hot Path

**Files:**
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/domain/TicketStateMachine.kt`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/domain/AutomationModels.kt`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/runtime/AutomationCoordinator.kt`
- Test: `android-app/app/src/test/java/com/local/damaiassistant/domain/TicketStateMachineTest.kt`
- Test: `android-app/app/src/test/java/com/local/damaiassistant/runtime/AutomationCoordinatorTest.kt`

- [ ] Add failing tests proving stage two and stage three enter with `ClickCoordinate`, not `ClickNode`.
- [ ] Add failing tests proving a completed coordinate click waits for a recovery delay before requesting node inspection/click.
- [ ] Add an explicit recovery action phase so coordinate success does not immediately trigger screenshots.
- [ ] Change stage entry and retry behavior to coordinate-first while preserving click limits, stale callback protection, and result confirmation.
- [ ] Re-run focused state machine and coordinator tests.

### Task 4: Delayed Visual Recovery Configuration

**Files:**
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/config/AutomationConfig.kt`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/config/ConfigRepository.kt`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/domain/TicketStateMachine.kt`
- Test: `android-app/app/src/test/java/com/local/damaiassistant/config/ConfigRepositoryTest.kt`
- Test: `android-app/app/src/test/java/com/local/damaiassistant/domain/TicketStateMachineTest.kt`

- [ ] Add failing tests for `lowLatencyEnabled`, `visualFallbackDelayMillis`, and V1-compatible defaults.
- [ ] Persist both values using SharedPreferences-backed repository keys.
- [ ] Use the visual fallback delay before entering screenshot recovery.
- [ ] Keep existing screenshot interval and maximum screenshot limits.
- [ ] Run focused config and state machine tests.

### Task 5: Performance Trace Events

**Files:**
- Create: `android-app/app/src/main/java/com/local/damaiassistant/logging/PerformanceTraceLogger.kt`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/runtime/AutomationCoordinator.kt`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/service/DamaiAccessibilityService.kt`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/DamaiAssistantApp.kt`
- Test: `android-app/app/src/test/java/com/local/damaiassistant/logging/PerformanceTraceLoggerTest.kt`
- Test: `android-app/app/src/test/java/com/local/damaiassistant/runtime/AutomationCoordinatorTest.kt`

- [ ] Add failing tests for ordered events, adjacent duration calculation, capacity, and TSV export.
- [ ] Implement trace events carrying wall time, monotonic nanoseconds, stage, input mode, foreground activity/package, and reason.
- [ ] Record arm, warmup, trigger, stage tap start/end, stage observations, result, cancel, and failure events.
- [ ] Export performance events alongside the existing run log.
- [ ] Run focused logger and coordinator tests.

### Task 6: Wakelock and Runtime Readiness

**Files:**
- Modify: `android-app/app/src/main/AndroidManifest.xml`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/service/DamaiAccessibilityService.kt`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/automation/ShizukuShellTapper.kt`

- [ ] Add `android.permission.WAKE_LOCK`.
- [ ] Acquire a bounded partial wakelock when a run is armed.
- [ ] Release it on stop, failure, cancellation, completion, and service shutdown.
- [ ] Prewarm Shizuku before `coordinator.arm()` and fail arming with a clear reason only when Shizuku is unavailable; direct injection failure alone must fall back to shell input.
- [ ] Build the debug APK.

### Task 7: Chinese Low-Latency Status UI

**Files:**
- Modify: `android-app/app/src/main/res/layout/activity_main.xml`
- Modify: `android-app/app/src/main/res/values/strings.xml`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/ui/MainActivity.kt`
- Modify: `android-app/app/src/main/java/com/local/damaiassistant/config/AutomationConfig.kt`
- Test: `android-app/app/src/test/java/com/local/damaiassistant/config/ConfigRepositoryTest.kt`

- [ ] Add a low-latency switch and visual fallback delay input.
- [ ] Add a Shizuku status text showing server, authorization, binding, and active mode.
- [ ] Save the settings without changing the user-entered target time in immediate-test mode.
- [ ] Refresh Shizuku status on resume, permission result, and snapshot changes.
- [ ] Add Chinese guidance explaining that USB can be removed after Shizuku starts, but reboot requires restart.
- [ ] Run unit tests and build.

### Task 8: Verification and Device QA

**Files:**
- No production file changes unless verification exposes a defect.

- [ ] Run `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --rerun-tasks`.
- [ ] Install the debug APK with `adb install -r`.
- [ ] Restart Shizuku using the device-specific adb command if necessary.
- [ ] Re-enable accessibility manually or through the existing adb test setup.
- [ ] Verify the UI reports Shizuku ready and the detected mode.
- [ ] Run immediate test on the restored Damai stage-one page.
- [ ] Confirm log evidence for warmup, tap mode, stage-one transition, and performance timestamps.
- [ ] If direct injection is rejected, confirm automatic `SHELL_INPUT` fallback still enters `NcovSkuActivity`.
