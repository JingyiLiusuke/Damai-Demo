package com.local.damaiassistant.automation

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method

internal class DirectInputInjector {
    @Volatile
    private var resolved: ResolvedInputManager? = null

    fun warmUp(): Boolean = resolve() != null

    fun tap(
        x: Int,
        y: Int,
        downUpDelayMillis: Int,
    ): Boolean {
        val inputManager = resolve() ?: return false
        val downTime = SystemClock.uptimeMillis()
        val down = motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        val downSucceeded = try {
            inputManager.inject(down)
        } finally {
            down.recycle()
        }
        if (!downSucceeded) return false

        if (downUpDelayMillis > 0) {
            SystemClock.sleep(downUpDelayMillis.toLong())
        }
        val upTime = SystemClock.uptimeMillis()
        val up = motionEvent(downTime, upTime, MotionEvent.ACTION_UP, x, y)
        return try {
            inputManager.inject(up)
        } finally {
            up.recycle()
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun resolve(): ResolvedInputManager? {
        resolved?.let { return it }
        return runCatching {
            val inputManagerClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = inputManagerClass.getDeclaredMethod("getInstance").apply {
                isAccessible = true
            }
            val injectInputEvent = inputManagerClass.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            ).apply {
                isAccessible = true
            }
            val setDisplayId = Class.forName("android.view.InputEvent").getDeclaredMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType,
            ).apply {
                isAccessible = true
            }
            ResolvedInputManager(
                instance = requireNotNull(getInstance.invoke(null)),
                injectInputEvent = injectInputEvent,
                setDisplayId = setDisplayId,
            ).also { resolved = it }
        }.getOrNull()
    }

    private fun motionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Int,
        y: Int,
    ): MotionEvent {
        val source = InputDevice.SOURCE_TOUCHSCREEN
        val pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            x.toFloat(),
            y.toFloat(),
            pressure,
            DEFAULT_SIZE,
            DEFAULT_META_STATE,
            DEFAULT_PRECISION,
            DEFAULT_PRECISION,
            inputDeviceId(source),
            DEFAULT_EDGE_FLAGS,
        ).apply {
            this.source = source
            resolve()?.setDisplay(this, DEFAULT_DISPLAY_ID)
        }
    }

    private fun inputDeviceId(source: Int): Int {
        InputDevice.getDeviceIds().forEach { deviceId ->
            val device = InputDevice.getDevice(deviceId) ?: return@forEach
            if (device.supportsSource(source)) return deviceId
        }
        return DEFAULT_DEVICE_ID
    }

    private data class ResolvedInputManager(
        val instance: Any,
        val injectInputEvent: Method,
        val setDisplayId: Method,
    ) {
        fun setDisplay(event: InputEvent, displayId: Int) {
            setDisplayId.invoke(event, displayId)
        }

        fun inject(event: InputEvent): Boolean =
            injectInputEvent.invoke(
                instance,
                event,
                INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH,
            ) == true
    }

    private companion object {
        const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2
        const val DEFAULT_DISPLAY_ID = 0
        const val DEFAULT_DEVICE_ID = 0
        const val DEFAULT_SIZE = 1f
        const val DEFAULT_META_STATE = 0
        const val DEFAULT_PRECISION = 1f
        const val DEFAULT_EDGE_FLAGS = 0
    }
}
