package com.local.damaiassistant.automation

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.damaiassistant.ui.AutomationFixtureActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodeDetectorInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: AutomationFixtureActivity
    private val detector = NodeDetector()

    @Before
    fun launchFixture() {
        val intent = Intent(
            instrumentation.context,
            AutomationFixtureActivity::class.java,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(intent) as AutomationFixtureActivity
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100L, 2_000L)
    }

    @After
    fun closeFixture() {
        activity.runOnUiThread { activity.finish() }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun findsStageTwoButtonByViewId() {
        withRoot { root ->
            val node = detector.byViewId(
                root,
                "com.local.damaiassistant:id/fixture_stage2",
            )
            try {
                assertNotNull(node)
                assertEquals("确定票价", node?.text?.toString())
            } finally {
                node?.recycle()
            }
        }
    }

    @Test
    fun findsExactTextAndClickableParent() {
        withRoot { root ->
            val textNode = detector.byExactText(root, "立即提交")
            assertNotNull(textNode)
            try {
                val clickable = detector.clickableNode(textNode!!)
                try {
                    assertNotNull(clickable)
                    assertEquals(true, clickable?.isClickable)
                } finally {
                    clickable?.recycle()
                }
            } finally {
                textNode?.recycle()
            }
        }
    }

    @Test
    fun findsDescriptionAndClickableNodeInsideBounds() {
        withRoot { root ->
            val described = detector.byDescription(root, "stage two confirmation")
            assertNotNull(described)
            try {
                val bounds = Rect()
                described?.getBoundsInScreen(bounds)
                val clickable = detector.clickableInside(root, bounds)
                try {
                    assertNotNull(clickable)
                } finally {
                    clickable?.recycle()
                }
            } finally {
                described?.recycle()
            }
        }
    }

    private fun withRoot(block: (AccessibilityNodeInfo) -> Unit) {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        assertNotNull(root)
        block(root!!)
    }
}
