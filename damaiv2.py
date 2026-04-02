from __future__ import annotations

import datetime
import subprocess
import statistics
import time
from threading import Lock
from collections import defaultdict
from dataclasses import dataclass
from typing import Callable, DefaultDict, Iterable, List, Optional, Sequence, TextIO, Tuple

from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy
from selenium.webdriver import ActionChains
from selenium.webdriver.common.actions import interaction
from selenium.webdriver.common.actions.action_builder import ActionBuilder
from selenium.webdriver.common.actions.pointer_input import PointerInput

# ================= Configuration =================
# Replace with the value from `adb devices`
DEVICE_NAME = "PQY0221129024300"
APP_PACKAGE = "cn.damai"
APP_ACTIVITY = "cn.damai.homepage.MainActivity"
COMMAND_EXECUTOR = "http://127.0.0.1:4723/wd/hub"
TARGET_TIME_STR = "2026-04-02 15:18:00.000"

# Unified action-button bounds for stage1/stage2/stage3
ACTION_BUTTON_BOUNDS = (815, 2591, 1270, 2632)  # [x1,y1,x2,y2][438,1758][1050,1893]
#ACTION_BUTTON_BOUNDS = (438, 1758, 1050, 1893)  # 虚拟机

# Time windows
FAST_POLL_WINDOW_SECONDS = 50
SUBMIT_WINDOW_SECONDS = 100

# Hot-loop tuning
SUBMIT_LOOP_SLEEP_SECONDS = 0.03
SUCCESS_WAIT_TIMEOUT_SECONDS = 0.12
SUCCESS_WAIT_POLL_SECONDS = 0.04
PRE_TRIGGER_OFFSET_MS = 100      #负偏移触发/提前启动
STAGE1_AUX_LOCATOR_EVERY_TAPS = 2
STAGE1_SUCCESS_WAIT_TIMEOUT_SECONDS = 0.0
STAGE1_SUCCESS_WAIT_POLL_SECONDS = 0.02
STAGE1_MAX_CLICK_ATTEMPTS = 48
STAGE1_MAX_SECONDS = 18.0
STAGE1_LOOP_SLEEP_SECONDS = 0.0
STAGE1_INITIAL_BURST_TAPS = 3
STAGE1_INITIAL_BURST_GAP_SECONDS = 0.01
STAGE1_POST_BURST_PURE_TAP_SECONDS = 0.20
STAGE1_SPECULATIVE_ADVANCE = True
STAGE2_SUCCESS_WAIT_TIMEOUT_SECONDS = 0.03
STAGE2_SUCCESS_WAIT_POLL_SECONDS = 0.015
W3C_STAGE1_TAP_EVERY_LOOPS = 3
W3C_NON_STAGE1_TAP_EVERY_MISSES = 3
ENABLE_ADB_STAGE1_TAP = True
ENABLE_ADB_TAP_NON_STAGE1 = True
ADB_TAP_TIMEOUT_SECONDS = 1.0
ENABLE_PERSISTENT_ADB_SHELL = True
STAGE3_SLOW_LOCATOR_EVERY_MISSES = 4
STAGE3_ASSUME_SUCCESS_AFTER_TAPS = 2
STAGE3_CHECK_TARGET_DISAPPEAR = False

# Runtime Appium settings for hot loop
NORMAL_DRIVER_SETTINGS = {
    "waitForIdleTimeout": 50,
    "waitForSelectorTimeout": 500,
    "actionAcknowledgmentTimeout": 100,
    "scrollAcknowledgmentTimeout": 100,
}
FAST_DRIVER_SETTINGS = {
    "waitForIdleTimeout": 0,
    "waitForSelectorTimeout": 10,
    "actionAcknowledgmentTimeout": 0,
    "scrollAcknowledgmentTimeout": 0,
}

# stage1: 立即预定 (prefer tap first)
STAGE1_LOCATORS: Sequence[Tuple[str, str]] = (
    (AppiumBy.ID, "cn.damai:id/trade_project_detail_purchase_status_bar_container_fl"),
)

# stage2: 确定票价
STAGE2_LOCATORS: Sequence[Tuple[str, str]] = (
    (AppiumBy.ID, "cn.damai:id/btn_buy_view"),
)

# stage3: 立即提交 (xpath from real-device capture)
STAGE3_FAST_LOCATORS: Sequence[Tuple[str, str]] = (
    (AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("立即提交")'),
    (AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().textContains("立即提交")'),
    (
        AppiumBy.ANDROID_UIAUTOMATOR,
        'new UiSelector().className("android.widget.TextView").text("立即提交").clickable(true)',
    ),
)
STAGE3_SLOW_LOCATORS: Sequence[Tuple[str, str]] = (
    (
        AppiumBy.XPATH,
        "/hierarchy/android.widget.FrameLayout/android.widget.LinearLayout/android.widget.FrameLayout/"
        "android.widget.LinearLayout/android.widget.FrameLayout/android.widget.FrameLayout/"
        "android.widget.FrameLayout/android.widget.RelativeLayout/android.widget.LinearLayout[3]/"
        "android.widget.FrameLayout/android.widget.TextView",
    ),
)
STAGE3_LOCATORS: Sequence[Tuple[str, str]] = STAGE3_FAST_LOCATORS
STAGE2_SUCCESS_LOCATORS: Sequence[Tuple[str, str]] = (
    (AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("立即提交")'),
)
STAGE1_SUCCESS_LOCATORS: Sequence[Tuple[str, str]] = STAGE2_LOCATORS


@dataclass
class PerfCounter:
    samples: DefaultDict[str, List[float]]

    @classmethod
    def create(cls) -> "PerfCounter":
        return cls(samples=defaultdict(list))

    def measure(self, label: str, fn: Callable[[], None]) -> None:
        start = time.perf_counter()
        fn()
        self.samples[label].append((time.perf_counter() - start) * 1000)

    def summary(self) -> None:
        if not self.samples:
            return
        print("\n===== Perf Summary =====")
        for label, values in sorted(self.samples.items()):
            if not values:
                continue
            print(
                "{} count={} avg={:.2f}ms p95={:.2f}ms max={:.2f}ms".format(
                    label,
                    len(values),
                    statistics.mean(values),
                    _percentile(values, 95),
                    max(values),
                )
            )


class DamaiBot:
    def __init__(self) -> None:
        caps = {
            "platformName": "Android",
            "platformVersion": "12",
            "automationName": "UiAutomator2",
            "deviceName": DEVICE_NAME,
            "appPackage": APP_PACKAGE,
            "appActivity": APP_ACTIVITY,
            "noReset": True,
            "newCommandTimeout": 6000,
            "ignoreUnimportantViews": True,
            "mjpegServerScreenshotQuality": 20,
            "mjpegServerPort": 8100,
            "appium:settings": NORMAL_DRIVER_SETTINGS,
        }
        options = UiAutomator2Options()
        options.load_capabilities(caps)

        self.options = options
        self.driver: Optional[webdriver.Remote] = None
        self.perf = PerfCounter.create()
        self.tap_backend = "unknown"
        self._legacy_tap_supported = False
        self._tap_disabled = False
        self._adb_stage1_tap_enabled = False
        self._adb_device_serial: Optional[str] = None
        self._adb_shell_proc: Optional[subprocess.Popen[str]] = None
        self._adb_shell_stdin: Optional[TextIO] = None
        self._adb_shell_lock = Lock()
        self._locator_last_hit: dict[str, int] = {}

        self.action_tap_points = _build_tap_points(ACTION_BUTTON_BOUNDS)
        self.action_tap_index = 0

    def start(self) -> None:
        print(">>> Connecting to phone...")
        self.driver = webdriver.Remote(COMMAND_EXECUTOR, options=self.options)
        self.driver.implicitly_wait(0)
        self._init_tap_backend()
        self._init_adb_stage1_tap()

        self.perf.measure("ping_context", lambda: self.driver.current_context)
        self.perf.measure("ping_window_size", lambda: self.driver.get_window_size())

        print(">>> Ready. Please navigate to the target detail page manually.")
        print(">>> Script will start in 3 seconds...")
        time.sleep(3)
        self.buy_process()

    def buy_process(self) -> None:
        self._apply_driver_settings(FAST_DRIVER_SETTINGS)
        self.wait_for_target_time()
        print(">>> Entering 3-stage order flow...")

        try:
            self._run_order_stages()
        finally:
            self._apply_driver_settings(NORMAL_DRIVER_SETTINGS)
            self.perf.summary()

    def wait_for_target_time(self) -> None:
        try:
            target_time = datetime.datetime.strptime(TARGET_TIME_STR, "%Y-%m-%d %H:%M:%S.%f")
        except ValueError:
            raise ValueError(f"Invalid TARGET_TIME_STR: {TARGET_TIME_STR}")

        trigger_offset_seconds = max(0.0, PRE_TRIGGER_OFFSET_MS / 1000.0)
        trigger_time = target_time - datetime.timedelta(seconds=trigger_offset_seconds)
        now = datetime.datetime.now()
        if trigger_time <= now:
            print(">>> Trigger time is already in the past, starting immediately.")
            return

        print(f">>> Target time: {target_time.strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]}")
        print(
            f">>> Trigger time: {trigger_time.strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]} "
            f"(offset -{PRE_TRIGGER_OFFSET_MS}ms)"
        )
        last_print = 0.0
        while True:
            delta = (trigger_time - datetime.datetime.now()).total_seconds()
            if delta <= 0:
                break
            if delta > 2:
                if time.perf_counter() - last_print > 0.5:
                    print(f"Waiting... {delta:.3f}s", end="\r")
                    last_print = time.perf_counter()
                time.sleep(0.05)
            elif delta > 0.2:
                time.sleep(0.005)
            else:
                time.sleep(0.0005)
        print("\n>>> Trigger time reached, start.")

    def _run_order_stages(self) -> None:
        flow_start = time.perf_counter()
        global_deadline = time.perf_counter() + SUBMIT_WINDOW_SECONDS
        stage1_deadline = min(global_deadline, time.perf_counter() + STAGE1_MAX_SECONDS)
        total_click_count = 0

        # stage1: immediate reserve; tap priority by request
        stage1_ok, stage1_clicks = self._complete_stage(
            stage_name="stage1_立即预定",
            target_locators=STAGE1_LOCATORS,
            success_locators=STAGE1_SUCCESS_LOCATORS,
            stage_deadline=stage1_deadline,
            max_click_attempts=STAGE1_MAX_CLICK_ATTEMPTS,
            tap_first=True,
            terminal=False,
            initial_success_probe=False,
            immediate_first_tap=True,
            success_wait_timeout_seconds=STAGE1_SUCCESS_WAIT_TIMEOUT_SECONDS,
            success_wait_poll_seconds=STAGE1_SUCCESS_WAIT_POLL_SECONDS,
            loop_sleep_seconds=STAGE1_LOOP_SLEEP_SECONDS,
            slow_target_locators=(),
            slow_target_every_misses=0,
            success_on_target_disappear=True,
            speculative_success_after_initial_burst=STAGE1_SPECULATIVE_ADVANCE,
            terminal_assume_success_after_taps=0,
            terminal_check_target_disappear=True,
        )
        total_click_count += stage1_clicks
        if not stage1_ok:
            print("!!! stage1 failed within time window.")
            print(f">>> Flow finished. total_clicks={total_click_count}")
            return
        print(f">>> stage1 elapsed={time.perf_counter() - flow_start:.3f}s")

        # stage2: confirm ticket price
        stage2_ok, stage2_clicks = self._complete_stage(
            stage_name="stage2_确定票价",
            target_locators=STAGE2_LOCATORS,
            success_locators=STAGE2_SUCCESS_LOCATORS,
            stage_deadline=global_deadline,
            max_click_attempts=20,
            tap_first=False,
            terminal=False,
            initial_success_probe=False,
            immediate_first_tap=False,
            success_wait_timeout_seconds=STAGE2_SUCCESS_WAIT_TIMEOUT_SECONDS,
            success_wait_poll_seconds=STAGE2_SUCCESS_WAIT_POLL_SECONDS,
            loop_sleep_seconds=SUBMIT_LOOP_SLEEP_SECONDS,
            slow_target_locators=(),
            slow_target_every_misses=0,
            success_on_target_disappear=False,
            speculative_success_after_initial_burst=False,
            terminal_assume_success_after_taps=0,
            terminal_check_target_disappear=True,
        )
        total_click_count += stage2_clicks
        if not stage2_ok:
            print("!!! stage2 failed within time window.")
            print(f">>> Flow finished. total_clicks={total_click_count}")
            return
        print(f">>> stage2 elapsed={time.perf_counter() - flow_start:.3f}s")

        # stage3: immediate submit (terminal stage)
        stage3_ok, stage3_clicks = self._complete_stage(
            stage_name="stage3_立即提交",
            target_locators=STAGE3_LOCATORS,
            success_locators=(),
            stage_deadline=global_deadline,
            max_click_attempts=24,
            tap_first=False,
            terminal=True,
            initial_success_probe=True,
            immediate_first_tap=False,
            success_wait_timeout_seconds=SUCCESS_WAIT_TIMEOUT_SECONDS,
            success_wait_poll_seconds=SUCCESS_WAIT_POLL_SECONDS,
            loop_sleep_seconds=SUBMIT_LOOP_SLEEP_SECONDS,
            slow_target_locators=STAGE3_SLOW_LOCATORS,
            slow_target_every_misses=STAGE3_SLOW_LOCATOR_EVERY_MISSES,
            success_on_target_disappear=False,
            speculative_success_after_initial_burst=False,
            terminal_assume_success_after_taps=STAGE3_ASSUME_SUCCESS_AFTER_TAPS,
            terminal_check_target_disappear=STAGE3_CHECK_TARGET_DISAPPEAR,
        )
        total_click_count += stage3_clicks
        if not stage3_ok:
            print("!!! stage3 not confirmed, but click attempts were sent.")
        else:
            print(f">>> stage3 elapsed={time.perf_counter() - flow_start:.3f}s")

        print(f">>> Flow finished. total_clicks={total_click_count}")

    def _complete_stage(
        self,
        stage_name: str,
        target_locators: Sequence[Tuple[str, str]],
        success_locators: Sequence[Tuple[str, str]],
        stage_deadline: float,
        max_click_attempts: int,
        tap_first: bool,
        terminal: bool,
        initial_success_probe: bool,
        immediate_first_tap: bool,
        success_wait_timeout_seconds: float,
        success_wait_poll_seconds: float,
        loop_sleep_seconds: float,
        slow_target_locators: Sequence[Tuple[str, str]],
        slow_target_every_misses: int,
        success_on_target_disappear: bool,
        speculative_success_after_initial_burst: bool,
        terminal_assume_success_after_taps: int,
        terminal_check_target_disappear: bool,
    ) -> Tuple[bool, int]:
        click_attempts = 0

        success_cache_key = f"{stage_name}:success"
        target_cache_key = f"{stage_name}:target"
        slow_target_cache_key = f"{stage_name}:target_slow"
        stage_key = stage_name.split("_", 1)[0]
        stage_perf_label = f"state_check_{stage_key}"
        stage_slow_perf_label = f"state_check_{stage_key}_slow_xpath"
        miss_since_last_tap = 0
        miss_since_last_slow_target_check = 0
        stage_started = time.perf_counter()
        use_adb_tap = self._adb_stage1_tap_enabled and (tap_first or ENABLE_ADB_TAP_NON_STAGE1)
        tap_fn = self._tap_stage1_action_zone_once if use_adb_tap else self._tap_action_zone_once

        if (
            initial_success_probe
            and success_locators
            and self._exists_any(
                success_locators,
                cache_key=success_cache_key,
                perf_label=stage_perf_label,
            )
        ):
            print(f">>> {stage_name}: already passed (next stage detected).")
            return True, click_attempts

        while time.perf_counter() < stage_deadline and click_attempts < max_click_attempts:
            if tap_first:
                if immediate_first_tap and click_attempts == 0:
                    burst_taps = STAGE1_INITIAL_BURST_TAPS if use_adb_tap else 1
                    for burst_idx in range(burst_taps):
                        tap_ok = (
                            tap_fn(force_center=True)
                            if (use_adb_tap or not self._tap_disabled)
                            else False
                        )
                        click_attempts += 1
                        miss_since_last_tap = 0
                        if not tap_ok:
                            self._find_and_click_any(
                                target_locators,
                                cache_key=target_cache_key,
                                perf_label=stage_perf_label,
                            )
                            miss_since_last_slow_target_check += 1
                        if (
                            burst_idx + 1 < burst_taps
                            and STAGE1_INITIAL_BURST_GAP_SECONDS > 0
                        ):
                            time.sleep(STAGE1_INITIAL_BURST_GAP_SECONDS)
                    if speculative_success_after_initial_burst:
                        print(f">>> {stage_name}: speculative advance after initial burst.")
                        return True, click_attempts
                elif self._tap_disabled and not use_adb_tap:
                    # When tap path is disabled, avoid spending time on failing actions.
                    if self._find_and_click_any(
                        target_locators,
                        cache_key=target_cache_key,
                        perf_label=stage_perf_label,
                    ):
                        miss_since_last_slow_target_check = 0
                    else:
                        miss_since_last_slow_target_check += 1
                # If backend is W3C, tap is expensive and unstable on this device:
                # reduce tap frequency and rely more on locator probes.
                elif self.tap_backend == "w3c_touch":
                    clicked_by_locator = self._find_and_click_any(
                        target_locators,
                        cache_key=target_cache_key,
                        perf_label=stage_perf_label,
                    )
                    if clicked_by_locator:
                        miss_since_last_tap = 0
                        miss_since_last_slow_target_check = 0
                    else:
                        miss_since_last_tap += 1
                        miss_since_last_slow_target_check += 1

                    should_tap = (
                        miss_since_last_tap >= W3C_STAGE1_TAP_EVERY_LOOPS
                        or click_attempts == 0
                    )
                    if should_tap:
                        tap_ok = (
                            tap_fn(force_center=False)
                            if (use_adb_tap or not self._tap_disabled)
                            else False
                        )
                        click_attempts += 1
                        miss_since_last_tap = 0
                        if not tap_ok:
                            self._find_and_click_any(
                                target_locators,
                                cache_key=target_cache_key,
                                perf_label=stage_perf_label,
                            )
                else:
                    tap_ok = (
                        tap_fn(force_center=False)
                        if (use_adb_tap or not self._tap_disabled)
                        else False
                    )
                    click_attempts += 1

                    # Sparse locator click as auxiliary path to avoid query overhead.
                    if (not tap_ok) or (click_attempts % STAGE1_AUX_LOCATOR_EVERY_TAPS == 0):
                        if self._find_and_click_any(
                            target_locators,
                            cache_key=target_cache_key,
                            perf_label=stage_perf_label,
                        ):
                            miss_since_last_slow_target_check = 0
                        else:
                            miss_since_last_slow_target_check += 1
            else:
                if self._find_and_click_any(
                    target_locators,
                    cache_key=target_cache_key,
                    perf_label=stage_perf_label,
                ):
                    miss_since_last_tap = 0
                    miss_since_last_slow_target_check = 0
                else:
                    miss_since_last_tap += 1
                    miss_since_last_slow_target_check += 1
                    if self.tap_backend == "w3c_touch":
                        # For W3C backend, avoid paying ~300ms tap cost on every miss.
                        if miss_since_last_tap >= W3C_NON_STAGE1_TAP_EVERY_MISSES:
                            if use_adb_tap or not self._tap_disabled:
                                tap_fn(force_center=False)
                            click_attempts += 1
                            miss_since_last_tap = 0
                    else:
                        if use_adb_tap or not self._tap_disabled:
                            tap_fn(force_center=False)
                        click_attempts += 1

            if (
                slow_target_locators
                and slow_target_every_misses > 0
                and miss_since_last_slow_target_check >= slow_target_every_misses
            ):
                if self._find_and_click_any(
                    slow_target_locators,
                    cache_key=slow_target_cache_key,
                    perf_label=stage_slow_perf_label,
                ):
                    miss_since_last_slow_target_check = 0
                else:
                    miss_since_last_slow_target_check = 0

            if (
                tap_first
                and STAGE1_POST_BURST_PURE_TAP_SECONDS > 0
                and (time.perf_counter() - stage_started) < STAGE1_POST_BURST_PURE_TAP_SECONDS
            ):
                continue

            if (
                terminal
                and terminal_assume_success_after_taps > 0
                and click_attempts >= terminal_assume_success_after_taps
            ):
                print(
                    f">>> {stage_name}: terminal fast success after {click_attempts} taps."
                )
                return True, click_attempts

            if success_on_target_disappear and not self._exists_any(
                target_locators,
                cache_key=target_cache_key,
                perf_label=stage_perf_label,
            ):
                print(f">>> {stage_name}: success by target disappearance.")
                return True, click_attempts

            if success_locators and self._wait_for_any(
                success_locators,
                timeout_seconds=success_wait_timeout_seconds,
                poll_seconds=success_wait_poll_seconds,
                cache_key=success_cache_key,
                perf_label=stage_perf_label,
            ):
                print(f">>> {stage_name}: success by next-stage locator.")
                return True, click_attempts

            # Optional terminal-stage disappearance check (can be disabled for speed).
            if terminal and terminal_check_target_disappear and not self._exists_any(
                target_locators,
                cache_key=target_cache_key,
                perf_label=stage_perf_label,
            ):
                print(f">>> {stage_name}: success by target disappearance.")
                return True, click_attempts

            if loop_sleep_seconds > 0:
                time.sleep(loop_sleep_seconds)

        if terminal and click_attempts > 0:
            print(f">>> {stage_name}: terminal best-effort complete (attempts={click_attempts}).")
            return True, click_attempts

        return False, click_attempts

    def _find_and_click_any(
        self,
        locators: Sequence[Tuple[str, str]],
        cache_key: Optional[str] = None,
        perf_label: str = "state_check",
    ) -> bool:
        assert self.driver is not None

        start = time.perf_counter()
        for idx, (by, value) in self._iter_locators_with_cache(locators, cache_key):
            elements = self.driver.find_elements(by, value)
            if not elements:
                continue
            try:
                elements[0].click()
                if cache_key is not None:
                    self._locator_last_hit[cache_key] = idx
                self.perf.samples[perf_label].append((time.perf_counter() - start) * 1000)
                return True
            except Exception:
                continue

        self.perf.samples[perf_label].append((time.perf_counter() - start) * 1000)
        return False

    def _exists_any(
        self,
        locators: Sequence[Tuple[str, str]],
        cache_key: Optional[str] = None,
        perf_label: str = "state_check",
    ) -> bool:
        assert self.driver is not None
        start = time.perf_counter()
        for idx, (by, value) in self._iter_locators_with_cache(locators, cache_key):
            if self.driver.find_elements(by, value):
                if cache_key is not None:
                    self._locator_last_hit[cache_key] = idx
                self.perf.samples[perf_label].append((time.perf_counter() - start) * 1000)
                return True
        self.perf.samples[perf_label].append((time.perf_counter() - start) * 1000)
        return False

    def _wait_for_any(
        self,
        locators: Sequence[Tuple[str, str]],
        timeout_seconds: float,
        poll_seconds: float,
        cache_key: Optional[str] = None,
        perf_label: str = "state_check",
    ) -> bool:
        if timeout_seconds <= 0:
            return self._exists_any(locators, cache_key=cache_key, perf_label=perf_label)
        deadline = time.perf_counter() + timeout_seconds
        while time.perf_counter() < deadline:
            if self._exists_any(locators, cache_key=cache_key, perf_label=perf_label):
                return True
            time.sleep(poll_seconds)
        return False

    def _iter_locators_with_cache(
        self,
        locators: Sequence[Tuple[str, str]],
        cache_key: Optional[str],
    ) -> Iterable[Tuple[int, Tuple[str, str]]]:
        if not locators:
            return ()
        if cache_key is None:
            return list(enumerate(locators))
        hit = self._locator_last_hit.get(cache_key)
        if hit is None or hit < 0 or hit >= len(locators):
            return list(enumerate(locators))
        ordered = [(hit, locators[hit])]
        for idx, locator in enumerate(locators):
            if idx == hit:
                continue
            ordered.append((idx, locator))
        return ordered

    def _tap_action_zone_once(self, force_center: bool = False) -> bool:
        if force_center:
            x, y = self.action_tap_points[0]
        else:
            x, y = self.action_tap_points[self.action_tap_index % len(self.action_tap_points)]
            self.action_tap_index += 1
        try:
            self.perf.measure("tap_action", lambda: self._tap_by_backend(x, y))
            return True
        except Exception as exc:
            # Do not crash the whole flow on a single tap backend glitch.
            self.perf.samples["tap_error_ms"].append(0.0)
            short_err = str(exc).splitlines()[0]
            print(f"!!! tap failed once: {type(exc).__name__}: {short_err}")
            if self.tap_backend == "clickGesture":
                if self._legacy_tap_supported:
                    self.tap_backend = "legacy_tap"
                    print(">>> Tap backend switched to legacy_tap after clickGesture failure.")
                else:
                    self.tap_backend = "w3c_touch"
                    print(">>> Tap backend switched to w3c_touch after clickGesture failure.")
            if self.tap_backend == "w3c_touch" and self._legacy_tap_supported:
                self.tap_backend = "legacy_tap"
                print(">>> Tap backend switched to legacy_tap after W3C failure.")
            elif self.tap_backend == "w3c_touch" and not self._legacy_tap_supported:
                self._tap_disabled = True
                print(">>> Tap path disabled after W3C failure; locator-only mode enabled.")
            return False

    def _tap_stage1_action_zone_once(self, force_center: bool = False) -> bool:
        if force_center:
            x, y = self.action_tap_points[0]
        else:
            x, y = self.action_tap_points[self.action_tap_index % len(self.action_tap_points)]
            self.action_tap_index += 1
        if self._adb_stage1_tap_enabled:
            try:
                self.perf.measure("tap_action_adb", lambda: self._tap_by_adb(x, y))
                return True
            except Exception as exc:
                self._adb_stage1_tap_enabled = False
                print(f"!!! adb tap disabled after failure: {type(exc).__name__}: {exc}")
        return self._tap_action_zone_once()

    def _tap_by_adb(self, x: int, y: int) -> None:
        if self._send_tap_via_persistent_adb_shell(x, y):
            return
        cmd = ["adb"]
        if self._adb_device_serial:
            cmd.extend(["-s", self._adb_device_serial])
        cmd.extend(["shell", "input", "tap", str(x), str(y)])
        subprocess.run(
            cmd,
            check=True,
            capture_output=True,
            text=True,
            timeout=ADB_TAP_TIMEOUT_SECONDS,
        )

    def _send_tap_via_persistent_adb_shell(self, x: int, y: int) -> bool:
        proc = self._adb_shell_proc
        if proc is None or proc.poll() is not None:
            return False
        if self._adb_shell_stdin is None:
            return False
        with self._adb_shell_lock:
            try:
                self._adb_shell_stdin.write(f"input tap {x} {y}\n")
                self._adb_shell_stdin.flush()
                return True
            except Exception:
                self._close_persistent_adb_shell()
                return False

    def _open_persistent_adb_shell(self) -> bool:
        if not ENABLE_PERSISTENT_ADB_SHELL:
            return False
        if not self._adb_device_serial:
            return False
        if self._adb_shell_proc is not None and self._adb_shell_proc.poll() is None:
            return True
        cmd = ["adb", "-s", self._adb_device_serial, "shell"]
        try:
            proc = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                text=True,
                bufsize=1,
            )
            if proc.stdin is None:
                proc.terminate()
                return False
            self._adb_shell_proc = proc
            self._adb_shell_stdin = proc.stdin
            return True
        except Exception:
            self._adb_shell_proc = None
            self._adb_shell_stdin = None
            return False

    def _close_persistent_adb_shell(self) -> None:
        with self._adb_shell_lock:
            if self._adb_shell_stdin is not None:
                try:
                    self._adb_shell_stdin.close()
                except Exception:
                    pass
            proc = self._adb_shell_proc
            if proc is not None:
                try:
                    if proc.poll() is None:
                        proc.terminate()
                except Exception:
                    pass
            self._adb_shell_proc = None
            self._adb_shell_stdin = None

    def _tap_by_backend(self, x: int, y: int) -> None:
        assert self.driver is not None
        if self.tap_backend == "clickGesture":
            self.driver.execute_script("mobile: clickGesture", {"x": x, "y": y})
            return
        if self.tap_backend == "legacy_tap":
            self._tap_by_legacy(x, y)
            return
        if self.tap_backend == "w3c_touch":
            self._tap_by_w3c(x, y)
            return
        raise RuntimeError(f"Unsupported tap backend: {self.tap_backend}")

    def _tap_by_legacy(self, x: int, y: int) -> None:
        assert self.driver is not None
        legacy_tap = getattr(self.driver, "tap", None)
        if not callable(legacy_tap):
            raise RuntimeError("driver.tap is not available")
        legacy_tap([(x, y)], 1)

    def _tap_by_w3c(self, x: int, y: int) -> None:
        assert self.driver is not None
        actions = ActionChains(self.driver)
        actions.w3c_actions = ActionBuilder(
            self.driver,
            mouse=PointerInput(interaction.POINTER_TOUCH, "finger1"),
        )
        actions.w3c_actions.pointer_action.move_to_location(x, y)
        actions.w3c_actions.pointer_action.pointer_down()
        actions.w3c_actions.pointer_action.pause(0.01)
        actions.w3c_actions.pointer_action.release()
        actions.perform()

    def _init_tap_backend(self) -> None:
        assert self.driver is not None
        self._legacy_tap_supported = callable(getattr(self.driver, "tap", None))
        try:
            # Probe support with invalid coords to avoid accidental real click.
            self.driver.execute_script("mobile: clickGesture", {"x": -1, "y": -1})
            self.tap_backend = "clickGesture"
            print(">>> Tap backend: mobile: clickGesture")
            return
        except Exception as exc:
            message = str(exc)
            if "Unknown mobile command" in message:
                if self._legacy_tap_supported:
                    self.tap_backend = "legacy_tap"
                    print(">>> Tap backend fallback: driver.tap (legacy touch)")
                else:
                    self.tap_backend = "w3c_touch"
                    print(">>> Tap backend fallback: W3C touch actions")
                return
            self.tap_backend = "clickGesture"
            print(">>> Tap backend: mobile: clickGesture (probe with expected non-fatal error)")

    def _init_adb_stage1_tap(self) -> None:
        if not ENABLE_ADB_STAGE1_TAP:
            self._adb_stage1_tap_enabled = False
            return
        serial = self._resolve_adb_device_serial()
        if not serial:
            self._adb_stage1_tap_enabled = False
            print(">>> stage1 tap path: appium (no adb device detected)")
            return
        self._adb_device_serial = serial
        try:
            result = subprocess.run(
                ["adb", "-s", serial, "get-state"],
                check=True,
                capture_output=True,
                text=True,
                timeout=1.5,
            )
            self._adb_stage1_tap_enabled = "device" in result.stdout.strip().lower()
            if self._adb_stage1_tap_enabled:
                if self._open_persistent_adb_shell():
                    print(f">>> stage1 tap path: adb shell tap channel ({serial})")
                else:
                    print(f">>> stage1 tap path: adb input tap ({serial})")
            else:
                print(">>> stage1 tap path: appium (adb device state unavailable)")
        except Exception as exc:
            self._adb_stage1_tap_enabled = False
            print(f">>> stage1 tap path: appium (adb check failed: {type(exc).__name__})")

    def _resolve_adb_device_serial(self) -> Optional[str]:
        if self.driver is not None:
            caps = getattr(self.driver, "capabilities", {}) or {}
            for key in ("appium:udid", "udid", "deviceUDID", "deviceName"):
                value = str(caps.get(key, "")).strip()
                if value and value != "Android" and value != "YOUR_DEVICE_ID":
                    return value
        preferred = DEVICE_NAME.strip() if DEVICE_NAME else ""
        if preferred and preferred != "YOUR_DEVICE_ID":
            return preferred
        try:
            result = subprocess.run(
                ["adb", "devices"],
                check=True,
                capture_output=True,
                text=True,
                timeout=1.5,
            )
        except Exception:
            return None

        devices: List[str] = []
        for raw in result.stdout.splitlines():
            line = raw.strip()
            if not line or line.startswith("List of devices attached"):
                continue
            if "\tdevice" not in line:
                continue
            serial = line.split("\t", 1)[0].strip()
            if serial:
                devices.append(serial)
        if not devices:
            return None
        return devices[0]

    def _apply_driver_settings(self, settings: dict) -> None:
        assert self.driver is not None
        try:
            self.driver.update_settings(settings)
        except Exception as exc:
            print(f"!!! update_settings failed: {exc}")
            # Compatibility fallback: some devices reject too-aggressive zero values.
            fallback = {
                "waitForIdleTimeout": max(1, int(settings.get("waitForIdleTimeout", 1))),
                "waitForSelectorTimeout": max(10, int(settings.get("waitForSelectorTimeout", 10))),
                "actionAcknowledgmentTimeout": max(
                    10, int(settings.get("actionAcknowledgmentTimeout", 10))
                ),
                "scrollAcknowledgmentTimeout": max(
                    10, int(settings.get("scrollAcknowledgmentTimeout", 10))
                ),
            }
            try:
                self.driver.update_settings(fallback)
                print(f">>> Applied fallback settings: {fallback}")
            except Exception as fallback_exc:
                print(f"!!! fallback update_settings failed: {fallback_exc}")

    def stop(self) -> None:
        self._close_persistent_adb_shell()
        if self.driver:
            self.driver.quit()


def _build_tap_points(bounds: Tuple[int, int, int, int]) -> List[Tuple[int, int]]:
    x1, y1, x2, y2 = bounds
    width = max(1, x2 - x1)
    height = max(1, y2 - y1)

    center_x = x1 + width // 2
    center_y = y1 + height // 2

    xs = [
        x1 + int(width * 0.40),
        x1 + int(width * 0.50),
        x1 + int(width * 0.60),
    ]
    ys = [
        y1 + int(height * 0.45),
        y1 + int(height * 0.55),
    ]

    points: List[Tuple[int, int]] = []
    points.append((center_x, center_y))
    points.append((center_x - int(width * 0.08), center_y))
    points.append((center_x + int(width * 0.08), center_y))
    for y in ys:
        for x in xs:
            points.append((x, y))

    # deduplicate while preserving order
    dedup: List[Tuple[int, int]] = []
    seen = set()
    for point in points:
        if point in seen:
            continue
        dedup.append(point)
        seen.add(point)
    return dedup


def _percentile(values: List[float], percentile: int) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = max(0, min(len(ordered) - 1, int(round((percentile / 100) * (len(ordered) - 1)))))
    return ordered[idx]


if __name__ == "__main__":
    bot = DamaiBot()
    try:
        bot.start()
    except KeyboardInterrupt:
        print("\n>>> User interrupted script.")
    except Exception as exc:
        print(f"\n!!! Unexpected error: {exc}")
    finally:
        # Keep the current page for manual observation. Switch to bot.stop() for clean exit.
        pass
