package com.local.damaiassistant.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

class NodeDetector(
    private val maxNodes: Int = 500,
    private val maxDepth: Int = 30,
) {
    init {
        require(maxNodes > 0) { "Maximum node count must be positive" }
        require(maxDepth >= 0) { "Maximum depth must be nonnegative" }
    }

    fun byViewId(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        require(id.isNotBlank()) { "View ID must not be blank" }
        firstMatching(root.findAccessibilityNodeInfosByViewId(id)) {
            it.viewIdResourceName == id
        }?.let { return it }
        return depthFirst(root) { it.viewIdResourceName == id }
    }

    fun byExactText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        require(text.isNotBlank()) { "Text must not be blank" }
        firstMatching(root.findAccessibilityNodeInfosByText(text)) {
            it.text?.toString() == text
        }?.let { return it }
        return depthFirst(root) { it.text?.toString() == text }
    }

    fun byDescription(
        root: AccessibilityNodeInfo,
        description: String,
    ): AccessibilityNodeInfo? {
        require(description.isNotBlank()) { "Description must not be blank" }
        return depthFirst(root) { it.contentDescription?.toString() == description }
    }

    @Suppress("DEPRECATION")
    fun clickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        while (current != null) {
            if (current.isClickable) return current
            val parent = current.parent
            current.recycle()
            current = parent
        }
        return null
    }

    fun clickableInside(
        root: AccessibilityNodeInfo,
        bounds: Rect,
    ): AccessibilityNodeInfo? {
        require(!bounds.isEmpty) { "Search bounds must not be empty" }
        val nodeBounds = Rect()
        return depthFirst(root) { node ->
            if (!node.isClickable || !node.isVisibleToUser) {
                false
            } else {
                node.getBoundsInScreen(nodeBounds)
                !nodeBounds.isEmpty &&
                    bounds.contains(nodeBounds.centerX(), nodeBounds.centerY())
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun firstMatching(
        nodes: List<AccessibilityNodeInfo>,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        var match: AccessibilityNodeInfo? = null
        nodes.forEach { node ->
            if (match == null && predicate(node)) {
                match = node
            } else {
                node.recycle()
            }
        }
        return match
    }

    @Suppress("DEPRECATION")
    private fun depthFirst(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<NodeAtDepth>()
        stack.addLast(NodeAtDepth(AccessibilityNodeInfo.obtain(root), 0))
        var visited = 0

        while (stack.isNotEmpty() && visited < maxNodes) {
            val current = stack.removeLast()
            val node = current.node
            visited += 1
            if (predicate(node)) {
                recycleStack(stack)
                return node
            }

            if (current.depth < maxDepth) {
                for (index in node.childCount - 1 downTo 0) {
                    node.getChild(index)?.let { child ->
                        stack.addLast(NodeAtDepth(child, current.depth + 1))
                    }
                }
            }
            node.recycle()
        }

        recycleStack(stack)
        return null
    }

    @Suppress("DEPRECATION")
    private fun recycleStack(stack: ArrayDeque<NodeAtDepth>) {
        while (stack.isNotEmpty()) {
            stack.removeLast().node.recycle()
        }
    }

    private data class NodeAtDepth(
        val node: AccessibilityNodeInfo,
        val depth: Int,
    )
}
