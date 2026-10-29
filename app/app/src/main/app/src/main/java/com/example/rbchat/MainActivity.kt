package com.example.rbchat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("crash", MODE_PRIVATE)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            prefs.edit()
                .putString("log", android.util.Log.getStackTraceString(e))
                .commit()
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 96, 32, 32)

        val info = TextView(this)
        info.text = "RbChat: проверка оверлея"
        info.textSize = 18f
        layout.addView(info)

        val permBtn = Button(this)
        permBtn.text = "1. Разрешить поверх других приложений"
        permBtn.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
        }
        layout.addView(permBtn)

        val showBtn = Button(this)
        showBtn.text = "2. Показать кнопку чата"
        showBtn.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, OverlayService::class.java))
                info.text = "Кнопка запущена. Открой Роблокс."
            } else {
                info.text = "Сначала дай разрешение (кнопка 1)"
            }
        }
        layout.addView(showBtn)

        val hideBtn = Button(this)
        hideBtn.text = "3. Убрать кнопку"
        hideBtn.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            info.text = "Кнопка убрана"
        }
        layout.addView(hideBtn)

        val old = prefs.getString("log", null)
        if (old != null) {
            val logView = TextView(this)
            logView.text = "ПРОШЛЫЙ ВЫЛЕТ:\n" + old.take(1500)
            logView.textSize = 11f
            layout.addView(logView)
            prefs.edit().remove("log").commit()
        }

        val scroll = ScrollView(this)
        scroll.addView(layout)
        setContentView(scroll)
    }
}
