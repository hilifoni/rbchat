package com.example.rbchat

import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    private var info: TextView? = null
    private var status: TextView? = null
    private var meText: TextView? = null
    private var meAvatar: ImageView? = null
    private var resultsBox: LinearLayout? = null
    private var pending: String? = null
    private var askedOverlay = false
    private var askedUsage = false
    private var askedNotif = false
    private val avatarViews = HashMap<Long, ImageView>()
    private val bitmaps = HashMap<Long, Bitmap>()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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

            // мой профиль
            val meRow = LinearLayout(this)
            meRow.orientation = LinearLayout.HORIZONTAL
            meRow.gravity = Gravity.CENTER_VERTICAL
            meRow.setPadding(0, dp(8), 0, dp(8))
            val av = ImageView(this)
            meRow.addView(av, LinearLayout.LayoutParams(dp(56), dp(56)))
            val mt = TextView(this)
            mt.textSize = 14f
            mt.setPadding(dp(10), 0, 0, 0)
            meRow.addView(mt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            layout.addView(meRow)
            meAvatar = av
            meText = mt

            // поиск игроков
            val searchTitle = TextView(this)
            searchTitle.text = "Поиск игроков Роблокса"
            searchTitle.textSize = 16f
            searchTitle.setPadding(0, dp(8), 0, 0)
            layout.addView(searchTitle)

            val et = EditText(this)
            et.hint = "Ник игрока"
            et.setSingleLine(true)
            et.imeOptions = EditorInfo.IME_ACTION_SEARCH
            et.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    doSearch(et.text.toString())
                    true
                } else {
                    false
                }
            }
            layout.addView(et)

            val findBtn = Button(this)
            findBtn.text = "Найти"
            findBtn.setOnClickListener { doSearch(et.text.toString()) }
            layout.addView(findBtn)

            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            layout.addView(box)
            resultsBox = box

            val st = TextView(this)
            st.textSize = 13f
            st.setPadding(0, dp(12), 0, dp(8))
            layout.addView(st)
            status = st

            val inf = TextView(this)
            inf.textSize = 13f
            inf.setPadding(0, 0, 0, dp(8))
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
            loadMe()
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

    // ---------- профиль и поиск ----------

    private fun loadMe() {
        val p = getSharedPreferences("profile", MODE_PRIVATE)
        val dn = p.getString("displayName", null)
        if (dn == null) {
            meText?.text = "Ник не выбран. Найди себя ниже и нажми на свой ник."
            meAvatar?.setImageDrawable(null)
            return
        }
        meText?.text = "Мой ник: " + dn + " (@" + p.getString("name", "") + ")\nНик пока не подтверждён"
        val f = File(filesDir, "avatar.png")
        if (f.exists()) {
            meAvatar?.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
        } else {
            meAvatar?.setImageDrawable(null)
        }
    }

    private fun chooseMe(u: RbUser) {
        try {
            val p = getSharedPreferences("profile", MODE_PRIVATE).edit()
            p.putLong("id", u.id)
            p.putString("name", u.name)
            p.putString("displayName", u.displayName)
            p.apply()
            val f = File(filesDir, "avatar.png")
            if (f.exists()) f.delete()
            loadMe()
            info?.text = "Выбран ник " + u.displayName + ". Загружаю аватарку..."
            val cached: Bitmap? = bitmaps[u.id]
            Thread {
                try {
                    var bmp: Bitmap? = cached
                    if (bmp == null) {
                        var url: String? = null
                        var tries = 0
                        while (url == null && tries < 4) {
                            url = RobloxApi.avatarUrls(listOf(u.id))[u.id]
                            if (url == null) {
                                tries++
                                Thread.sleep(1000)
                            }
                        }
                        if (url != null) bmp = RobloxApi.loadBitmap(url)
                    }
                    val finalBmp: Bitmap? = bmp
                    if (finalBmp != null) {
                        FileOutputStream(f).use { finalBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        runOnUiThread {
                            loadMe()
                            info?.text = "Готово: ник и аватарка сохранены"
                        }
                    } else {
                        runOnUiThread {
                            info?.text = "Роблокс пока не отдал аватарку. Нажми на свой ник ещё раз."
                        }
                    }
                } catch (e: Throwable) {
                    runOnUiThread {
                        info?.text = "Аватарка не загрузилась: " + (e.message ?: e.javaClass.simpleName)
                    }
                }
            }.start()
        } catch (e: Throwable) {
            info?.text = "ОШИБКА: " + android.util.Log.getStackTraceString(e).take(800)
        }
    }

    private fun doSearch(q: String) {
        resultsBox?.removeAllViews()
        avatarViews.clear()
        bitmaps.clear()
        if (q.isBlank()) {
            info?.text = "Введи ник"
            return
        }
        info?.text = "Ищу..."
        Thread {
            try {
                val users = RobloxApi.search(q)
                runOnUiThread { showResults(users) }
                try {
                    val urls = RobloxApi.avatarUrls(users.map { it.id })
                    for (u in users) {
                        val url = urls[u.id]
                        if (url != null) {
                            val bmp = try {
                                RobloxApi.loadBitmap(url)
                            } catch (e: Exception) {
                                null
                            }
                            if (bmp != null) {
                                runOnUiThread { setAvatar(u.id, bmp) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        info?.text = "Аватарки не загрузились: " + (e.message ?: e.javaClass.simpleName)
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    info?.text = "Не удалось найти: " + (e.message ?: e.javaClass.simpleName) +
                        "\nЕсли Роблокс у тебя работает только с VPN, включи его."
                }
            }
        }.start()
    }

    private fun showResults(users: List<RbUser>) {
        val box = resultsBox ?: return
        box.removeAllViews()
        if (users.isEmpty()) {
            info?.text = "Никого не нашли"
            return
        }
        info?.text = "Нажми на свой ник, чтобы выбрать его"
        for (u in users) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(4), dp(8), dp(4), dp(8))
            val iv = ImageView(this)
            row.addView(iv, LinearLayout.LayoutParams(dp(56), dp(56)))
            val tv = TextView(this)
            tv.text = u.displayName + "\n@" + u.name + " · " + u.id
            tv.textSize = 14f
            tv.setPadding(dp(10), 0, 0, 0)
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener { chooseMe(u) }
            box.addView(row)
            avatarViews[u.id] = iv
        }
    }

    private fun setAvatar(id: Long, bmp: Bitmap) {
        bitmaps[id] = bmp
        avatarViews[id]?.setImageBitmap(bmp)
    }

    // ---------- разрешения и запуск ----------

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
