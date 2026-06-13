package com.local.damaiassistant.debug

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.local.damaiassistant.config.AutomationConfig
import com.local.damaiassistant.logging.RunLogEntry
import com.local.damaiassistant.logging.RunLogger
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DebugCaptureManager(
    private val filesDir: File,
    private val cacheDir: File,
) {
    fun createBundle(
        bitmap: Bitmap,
        root: AccessibilityNodeInfo?,
        config: AutomationConfig,
        logs: List<RunLogEntry>,
        timestampMillis: Long = System.currentTimeMillis(),
    ): Result<File> = runCatching {
        val workspace = File(cacheDir, "debug-capture-$timestampMillis")
        var rootToRecycle = root
        try {
            check(workspace.mkdirs()) { "Unable to create debug capture workspace" }
            val rootForNodes = rootToRecycle
            rootToRecycle = null
            writeNodes(rootForNodes, File(workspace, NODES_FILE))
            writeScreen(bitmap, File(workspace, SCREEN_FILE))
            File(workspace, CONFIG_FILE).writeText(
                formatConfig(config),
                Charsets.UTF_8,
            )
            writeLogs(logs, File(workspace, LOG_FILE))

            val exportDirectory = exportDirectory()
            val output = File(exportDirectory, "debug-capture-$timestampMillis.zip")
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                BUNDLE_FILES.forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    File(workspace, name).inputStream().use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
            pruneExports(exportDirectory, output)
            output
        } finally {
            recycle(rootToRecycle)
            bitmap.recycle()
            workspace.deleteRecursively()
        }
    }

    fun exportLog(
        logs: List<RunLogEntry>,
        timestampMillis: Long = System.currentTimeMillis(),
    ): Result<File> = runCatching {
        val output = File(exportDirectory(), "run-log-$timestampMillis.txt")
        writeLogs(logs, output)
        pruneExports(output.parentFile ?: filesDir, output)
        output
    }

    @Suppress("DEPRECATION")
    private fun writeNodes(root: AccessibilityNodeInfo?, output: File) {
        var rootToRecycle = root
        try {
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                if (rootToRecycle == null) {
                    writer.appendLine("No active accessibility root")
                    return
                }
                val stack = ArrayDeque<NodeAtDepth>()
                stack.addLast(NodeAtDepth(rootToRecycle, 0))
                rootToRecycle = null
                var visited = 0
                val bounds = Rect()
                try {
                    while (stack.isNotEmpty() && visited < MAX_NODES) {
                        val current = stack.removeLast()
                        val node = current.node
                        try {
                            visited += 1
                            node.getBoundsInScreen(bounds)
                            writer.append(current.depth.toString())
                            writer.append(" | ")
                            writer.append(node.className?.toString().orEmpty())
                            writer.append(" | ")
                            writer.append(node.viewIdResourceName.orEmpty())
                            writer.append(" | ")
                            writer.append(sanitizeNodeText(node.text?.toString().orEmpty()))
                            writer.append(" | ")
                            writer.append(
                                sanitizeNodeText(
                                    node.contentDescription?.toString().orEmpty(),
                                ),
                            )
                            writer.append(" | ")
                            writer.append(node.isClickable.toString())
                            writer.append(" | ")
                            writer.append(bounds.toShortString())
                            writer.newLine()

                            if (current.depth < MAX_DEPTH) {
                                for (index in node.childCount - 1 downTo 0) {
                                    node.getChild(index)?.let { child ->
                                        stack.addLast(NodeAtDepth(child, current.depth + 1))
                                    }
                                }
                            }
                        } finally {
                            node.recycle()
                        }
                    }
                } finally {
                    while (stack.isNotEmpty()) {
                        stack.removeLast().node.recycle()
                    }
                }
            }
        } finally {
            recycle(rootToRecycle)
        }
    }

    private fun writeScreen(bitmap: Bitmap, output: File) {
        FileOutputStream(output).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Unable to encode debug screenshot"
            }
        }
    }

    private fun writeLogs(entries: List<RunLogEntry>, output: File) {
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            entries.forEach { entry ->
                writer.append(entry.wallMillis.toString())
                writer.append('\t')
                writer.append(entry.elapsedNanos.toString())
                writer.append('\t')
                writer.append(RunLogger.redact(entry.category))
                writer.append('\t')
                writer.append(RunLogger.redact(entry.message))
                writer.newLine()
            }
        }
    }

    private fun exportDirectory(): File = File(filesDir, EXPORT_DIRECTORY).also {
        check(it.exists() || it.mkdirs()) { "Unable to create export directory" }
    }

    private fun pruneExports(directory: File, keep: File) {
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it != keep }
            .sortedByDescending(File::lastModified)
            .drop(MAX_RETAINED_EXPORTS - 1)
            .forEach(File::delete)
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo?) {
        node?.recycle()
    }

    private data class NodeAtDepth(
        val node: AccessibilityNodeInfo,
        val depth: Int,
    )

    companion object {
        fun sanitizeNodeText(value: String): String =
            RunLogger.redact(value).take(MAX_TEXT_LENGTH)

        fun formatConfig(config: AutomationConfig): String = buildString {
            appendLine("targetEpochMillis=${config.targetEpochMillis}")
            appendLine("preTriggerOffsetMillis=${config.preTriggerOffsetMillis}")
            appendLine("stage1Rect=${config.stage1Rect}")
            appendLine("stage2Rect=${config.stage2Rect}")
            appendLine("stage3Rect=${config.stage3Rect}")
            appendLine("stage1Policy=${config.stage1}")
            appendLine("stage2Policy=${config.stage2}")
            appendLine("stage3Policy=${config.stage3}")
            appendLine(
                "screenshotMinIntervalMillis=${config.screenshotMinIntervalMillis}",
            )
            appendLine("maxScreenshotsPerStage=${config.maxScreenshotsPerStage}")
            appendLine("visualFallbackEnabled=${config.visualFallbackEnabled}")
        }

        private const val MAX_NODES = 500
        private const val MAX_DEPTH = 30
        private const val MAX_TEXT_LENGTH = 80
        private const val MAX_RETAINED_EXPORTS = 5
        private const val EXPORT_DIRECTORY = "exports"
        private const val NODES_FILE = "nodes.txt"
        private const val SCREEN_FILE = "screen.png"
        private const val CONFIG_FILE = "config.txt"
        private const val LOG_FILE = "run-log.txt"
        private val BUNDLE_FILES = listOf(
            NODES_FILE,
            SCREEN_FILE,
            CONFIG_FILE,
            LOG_FILE,
        )
    }
}
