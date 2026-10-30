package com.example.rbchat

import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private var info: TextView? = null
    private var status: TextView? = null
    private var pending: String? = null
    private var askedOverlay = false
    private var askedUsage = false
    private var askedNotif = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val layout = LinearLayout(this)
            layout.orientation = LinearLayout.VERTICAL
            layout.setPadding(32, 96, 32, 32)

            val title = TextView(this)
            title.text = "RbChat"
            title.textSize = 22f
            layout.addView(title)

            val st = TextView(this)
            st.textSize = 14f
            st.setPadding(0, 16, 0, 16)
            layout.addView(st)
            status = st

            val inf = TextView(this)
            inf.textSize = 13f
            inf.setPadding(0, 0, 0, 16)
            layout.addView(inf)
            info = inf

            val startBtn = Button(this)
            startBtn.text = "▶ Запустить чат"
            startBtn.setOnClickListener { startFlow("START") }
            layout.addView(startBtn)

            val editBtn = Button(this)
            editBtn.text = "Изменить расположение и размер"
            editBtn.setOnClickListener { startFlow("EDIT") }
            layout.addView(editBtn)

            val hideBtn = Button(this)
            hideBtn.text = "Убрать всё"
            hideBtn.setOnClickListener {
                try {
                    stopService(Intent(this, OverlayService::class.java))
                    inf.text = "Убрано"
                } catch (e: Throwable) {
                    inf.text = "ОШИБКА: " + android.util.Log.getStackTraceString(e).take(800)
                }
            }
            layout.addView(hideBtn)

            val scroll = ScrollView(this)
            scroll.addView(layout)
            setContentView(scroll)
            updateStatus()
        } catch (e: Throwable) {
            val t = TextView(this)
            t.text = "ОШИБКА ПРИ СТАРТЕ:\n" + android.util.Log.getStackTraceString(e).take(1500)
            t.textSize = 12f
            t.setPadding(32, 96, 32, 32)
            setContentView(t)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        proceed()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
        proceed()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasNotif(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun updateStatus() {
        val a = if (Settings.canDrawOverlays(this)) "✅" else "❌"
        val b = if (hasUsageAccess()) "✅" else "❌"
        val c = if (hasNotif()) "✅" else "❌"
        status?.text = a + " Поверх других приложений\n" +
            b + " Доступ к использованию (чат сам появляется в Роблоксе и пропадает)\n" +
            c + " Уведомления"
    }

    private fun startFlow(mode: String) {
        pending = mode
        askedOverlay = false
        askedUsage = false
        askedNotif = false
        proceed()
    }

    private fun proceed() {
        val mode = pending ?: return
        try {
            if (!Settings.canDrawOverlays(this)) {
                if (!askedOverlay) {
                    askedOverlay = true
                    info?.text = "Включи переключатель для RbChat и нажми «назад»"
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + packageName)
                        )
                    )
                } else {
                    info?.text = "Без разрешения «Поверх других приложений» чат не работает"
                    pending = null
                }
                return
            }
            if (!hasUsageAccess() && !askedUsage) {
                askedUsage = true
                info?.text = "Найди в списке RbChat, включи доступ и нажми «назад»"
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                return
            }
            if (!hasNotif() && !askedNotif) {
                askedNotif = true
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
                return
            }

            pending = null
            val i = Intent(this, OverlayService::class.java)
            if (mode == "EDIT") i.action = "EDIT"
            ContextCompat.startForegroundService(this, i)

            val li = packageManager.getLaunchIntentForPackage("com.roblox.client")
            if (li != null) {
                info?.text = "Запущено"
                startActivity(li)
            } else {
                info?.text = "Запущено, но Роблокс не найден на телефоне"
            }
        } catch (e: Throwable) {
            pending = null
            info?.text = "ОШИБКА: " + android.util.Log.getStackTraceString(e).take(800)
        }
    }
}
