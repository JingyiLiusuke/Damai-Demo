package com.local.damaiassistant.automation

import android.util.Log
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ShizukuInputService : IShizukuInputService.Stub() {
    private val directInput = DirectInputInjector()

    @Volatile
    private var mode = InputMode.UNAVAILABLE

    override fun warmUp(): Boolean {
        mode = InputModePolicy.preferredMode(
            manufacturer = Build.MANUFACTURER,
            directAvailable = directInput.warmUp(),
        )
        return true
    }

    override fun tap(
        x: Int,
        y: Int,
        downUpDelayMillis: Int,
    ): Int {
        val attempt = InputFallbackChain(
            directInject = {
                if (mode == InputMode.SHELL_INPUT) return@InputFallbackChain false
                runCatching {
                    directInput.tap(x, y, downUpDelayMillis)
                }.getOrElse { error ->
                    Log.w(LOG_TAG, "Direct input injection failed", error)
                    false
                }
            },
            shellInput = { shellTap(x, y) },
        ).tap()
        mode = attempt.mode
        return attempt.mode.wireCode
    }

    override fun currentMode(): Int = mode.wireCode

    private fun shellTap(x: Int, y: Int): Boolean {
        val command = arrayOf("/system/bin/input", "tap", x.toString(), y.toString())
        return runCatching {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(false)
                .start()
            val completed = process.waitFor(TAP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroy()
                Log.w(LOG_TAG, "UserService tap timed out x=$x y=$y")
                return false
            }
            val stderr = process.errorStream.readFully()
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                Log.w(LOG_TAG, "UserService tap failed exit=$exitCode stderr=$stderr")
            }
            exitCode == 0
        }.getOrElse { error ->
            Log.w(LOG_TAG, "UserService tap failed", error)
            false
        }
    }

    private fun java.io.InputStream.readFully(): String {
        val output = ByteArrayOutputStream()
        copyTo(output)
        return output.toString(Charsets.UTF_8.name()).trim()
    }

    private companion object {
        const val LOG_TAG = "DamaiAssistant"
        const val TAP_TIMEOUT_MILLIS = 1_500L
    }
}
