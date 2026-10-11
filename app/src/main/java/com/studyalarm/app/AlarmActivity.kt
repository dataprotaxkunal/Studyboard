package com.studyalarm.app

import android.app.Activity
import android.app.NotificationManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.*

@Suppress("DEPRECATION")
class AlarmActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        val nid = intent.getIntExtra("nid", 0)
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48)
        }
        lay.addView(TextView(this).apply { text = intent.getStringExtra("title"); textSize = 28f; gravity = Gravity.CENTER })
        lay.addView(TextView(this).apply { text = intent.getStringExtra("text"); textSize = 18f; gravity = Gravity.CENTER; setPadding(0, 24, 0, 64) })
        lay.addView(Button(this).apply {
            text = "STOP ALARM"; textSize = 22f
            setOnClickListener {
                getSystemService(NotificationManager::class.java).cancel(nid); finish()
            }
        })
        setContentView(lay)
    }
}
