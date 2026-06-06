package com.local.damaiassistant.ui

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.local.damaiassistant.R

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                setText(R.string.app_name)
            },
        )
    }
}
