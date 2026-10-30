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
        super.onCreate(savedInstanceState)
        try {
            val layout = LinearLayout(this)
            layout.orientation = LinearLayout.VERTICAL
            layout.setPadding(32, 96, 32, 32)

            val info = TextView(this)
            info.text = "RbChat запущен"
            info.textSize = 18f
            layout.addView(info)

            val permBtn = Button(this)
            permBtn.text = "1. Разрешить поверх других приложений"
            permBtn.setOnClickListener {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + packageName)
                        )
                    )
                } catch (e: Throwable) {
                    info.text = "ОШИБКА: " + android.util.Log.getStackTraceString(e).take(800)
                }
            }
            layout.addView(permBtn)

            val showBtn = Button(this)
            showBtn.text = "2. Показать кнопку чата"
            showBtn.setOnClickListener {
                try {
                    if (Settings.canDrawOverlays(this)) {
                        startService(Intent(this, OverlayService::class.java))
                        info.text = "Запущено. Открой Роблокс."
                    } else {
                        info.text = "Сначала дай разрешение (кнопка 1)"
                    }
                } catch (e: Throwable) {
                    info.text = "ОШИБКА: " + android.util.Log.getStackTraceString(e).take(800)
                }
            }
            layout.addView(showBtn)

            val editBtn = Button(this)
            editBtn.text = "3. Изменить расположение и размер"
            editBtn.setOnClickListener {
                try {
                    if (Settings.canDrawOverlays(this)) {
                        val i = Intent(this, OverlayService::class.java)
                        i.action = "EDIT"
                        startService(i)
                        info.text = "Режим настройки включён. Открой Роблокс, расставь, нажми Готово."
                    } else {
                        info.text = "Сначала дай разрешение (кнопка 1)"
                    }
                } catch (e: Throwable) {
                    info.text = "ОШИБКА: " + android.util.Log.getStackTraceString(e).take(800)
                }
            }
            layout.addView(editBtn)

            val hideBtn = Button(this)
            hideBtn.text = "4. Убрать всё"
            hideBtn.setOnClickListener {
                try {
                    stopService(Intent(this, OverlayService::class.java))
                    info.text = "Убрано"
                } catch (e: Throwable) {
                    info.text = "ОШИБКА: " + android.util.Log.getStackTraceString(e).take(800)
                }
            }
            layout.addView(hideBtn)

            val scroll = ScrollView(this)
            scroll.addView(layout)
            setContentView(scroll)
        } catch (e: Throwable) {
            val t = TextView(this)
            t.text = "ОШИБКА ПРИ СТАРТЕ:\n" + android.util.Log.getStackTraceString(e).take(1500)
            t.textSize = 12f
            t.setPadding(32, 96, 32, 32)
            setContentView(t)
        }
    }
}
