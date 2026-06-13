package com.local.damaiassistant.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.local.damaiassistant.R

class AutomationFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    Button(context).apply {
                        id = R.id.fixture_stage2
                        text = "确定票价"
                        contentDescription = "stage two confirmation"
                    },
                )
                addView(
                    FrameLayout(context).apply {
                        isClickable = true
                        isFocusable = true
                        addView(
                            TextView(context).apply {
                                text = "立即提交"
                                importantForAccessibility =
                                    View.IMPORTANT_FOR_ACCESSIBILITY_YES
                            },
                        )
                    },
                )
            },
        )
    }
}
