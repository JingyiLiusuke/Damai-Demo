package com.local.damaiassistant

import android.app.Application
import android.graphics.Bitmap
import com.local.damaiassistant.config.AutomationConfig
import com.local.damaiassistant.domain.RuntimeSnapshot
import com.local.damaiassistant.domain.Stage
import com.local.damaiassistant.logging.RunLogEntry
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

interface AutomationControl {
    fun arm(config: AutomationConfig): Result<Unit>

    fun stop()

    fun captureCalibration(stage: Stage, callback: (Result<Bitmap>) -> Unit)

    fun captureDebug(callback: (Result<File>) -> Unit)

    fun exportLog(callback: (Result<File>) -> Unit)

    fun recentLogs(): List<RunLogEntry>

    fun snapshot(): RuntimeSnapshot
}

class DamaiAssistantApp : Application() {
    private val control = AtomicReference<AutomationControl?>()
    private val latestSnapshot = AtomicReference(RuntimeSnapshot())
    private val latestForegroundPackage = AtomicReference<String?>()
    private val snapshotListeners =
        CopyOnWriteArraySet<(RuntimeSnapshot) -> Unit>()

    fun automationControl(): AutomationControl? = control.get()

    fun snapshot(): RuntimeSnapshot = latestSnapshot.get()

    fun foregroundPackage(): String? = latestForegroundPackage.get()

    fun register(control: AutomationControl) {
        this.control.set(control)
        publishSnapshot(control.snapshot())
    }

    fun unregister(control: AutomationControl) {
        this.control.compareAndSet(control, null)
    }

    fun publishSnapshot(snapshot: RuntimeSnapshot) {
        latestSnapshot.set(snapshot)
        snapshotListeners.forEach { listener -> listener(snapshot) }
    }

    fun updateForegroundPackage(packageName: String?) {
        latestForegroundPackage.set(packageName)
    }

    fun addSnapshotListener(listener: (RuntimeSnapshot) -> Unit): AutoCloseable {
        snapshotListeners += listener
        listener(latestSnapshot.get())
        return AutoCloseable { snapshotListeners -= listener }
    }
}
