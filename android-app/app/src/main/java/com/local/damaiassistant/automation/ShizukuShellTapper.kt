package com.local.damaiassistant.automation

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.local.damaiassistant.BuildConfig
import com.local.damaiassistant.config.PixelPoint
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ShizukuShellTapper {
    data class Status(
        val serverRunning: Boolean,
        val authorized: Boolean,
        val bound: Boolean,
        val mode: InputMode,
    )

    const val REQUEST_CODE = 6201
    private const val LOG_TAG = "DamaiAssistant"
    private const val BIND_TIMEOUT_MILLIS = 2_000L
    private val service = AtomicReference<IShizukuInputService?>()
    private val bindLatch = AtomicReference<CountDownLatch?>()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuInputService::class.java.name),
    )
        .daemon(false)
        .debuggable(BuildConfig.DEBUG)
        .processNameSuffix("input")
        .tag("damai-input")
        .version(5)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service.set(IShizukuInputService.Stub.asInterface(binder))
            bindLatch.getAndSet(null)?.countDown()
            Log.i(LOG_TAG, "Shizuku input service connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service.set(null)
            Log.i(LOG_TAG, "Shizuku input service disconnected")
        }
    }

    fun isReady(): Boolean =
        isBinderAvailable() && hasPermission()

    fun isBinderAvailable(): Boolean = runCatching {
        Shizuku.pingBinder()
    }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun shouldShowRequestPermissionRationale(): Boolean = runCatching {
        Shizuku.shouldShowRequestPermissionRationale()
    }.getOrDefault(false)

    fun requestPermission() {
        Shizuku.requestPermission(REQUEST_CODE)
    }

    fun warmUp(): InputMode {
        if (!isReady()) return InputMode.UNAVAILABLE
        val input = boundService() ?: return InputMode.UNAVAILABLE
        return runCatching {
            input.warmUp()
            InputMode.fromWireCode(input.currentMode())
        }.getOrElse { error ->
            service.set(null)
            Log.w(LOG_TAG, "Shizuku input service warm-up failed", error)
            InputMode.UNAVAILABLE
        }
    }

    fun currentMode(): InputMode {
        val input = service.get() ?: return InputMode.UNAVAILABLE
        return runCatching {
            InputMode.fromWireCode(input.currentMode())
        }.getOrDefault(InputMode.UNAVAILABLE)
    }

    fun status(): Status = Status(
        serverRunning = isBinderAvailable(),
        authorized = hasPermission(),
        bound = service.get() != null,
        mode = currentMode(),
    )

    fun tap(point: PixelPoint): InputAttempt {
        if (!isReady()) {
            Log.i(LOG_TAG, "Shizuku tap skipped; binder or permission is not ready")
            return InputAttempt(false, InputMode.UNAVAILABLE)
        }
        val input = boundService()
            ?: return InputAttempt(false, InputMode.UNAVAILABLE)
        return runCatching {
            val mode = InputMode.fromWireCode(
                input.tap(point.x, point.y, DEFAULT_DOWN_UP_DELAY_MILLIS),
            )
            InputAttempt(mode != InputMode.UNAVAILABLE, mode)
        }.getOrElse { error ->
            service.set(null)
            Log.w(LOG_TAG, "Shizuku tap failed", error)
            InputAttempt(false, InputMode.UNAVAILABLE)
        }
    }

    private fun boundService(): IShizukuInputService? {
        service.get()?.let { return it }
        val latch = CountDownLatch(1)
        bindLatch.set(latch)
        return runCatching {
            Shizuku.bindUserService(userServiceArgs, connection)
            if (!latch.await(BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                Log.w(LOG_TAG, "Shizuku input service bind timed out")
                null
            } else {
                service.get()
            }
        }.getOrElse { error ->
            bindLatch.compareAndSet(latch, null)
            Log.w(LOG_TAG, "Shizuku input service bind failed", error)
            null
        }
    }

    private const val DEFAULT_DOWN_UP_DELAY_MILLIS = 8
}
