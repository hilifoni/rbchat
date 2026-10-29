package com.example.rbchat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val info = TextView(this).apply {
            text = "RbChat: проверка оверлея"
            textSize = 18f
        }

        val permBtn = Button(this).apply {
            text = "1. Разрешить поверх других приложений"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        val showBtn = Button(this).apply {
            text = "2. Показать кнопку чата"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startService(Intent(this@MainActivity, OverlayService::class.java))
                    info.text = "Кнопка запущена. Открой Роблокс."
                } else {
                    info.text = "Сначала дай разрешение (кнопка 1)"
                }
            }
        }

        val hideBtn = Button(this).apply {
            text = "3. Убрать кнопку"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                info.text = "Кнопка убрана"
            }
        }

        layout.addView(info)
        layout.addView(permBtn)
        layout.addView(showBtn)
        layout.addView(hideBtn)
        setContentView(layout)
    }
}
