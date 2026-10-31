package com.example.rbchat

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var density = 1f

    private var btn: TextView? = null
    private var btnP: WindowManager.LayoutParams? = null
    private var btnAttached = false

    private var win: FrameLayout? = null
    private var winP: WindowManager.LayoutParams? = null
    private var winAttached = false

    private var editMode = false
    private var chatList: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var input: EditText? = null
    private var shield: View? = null
    private var topBar: LinearLayout? = null
    private var handle: View? = null

    private var typing = false
    private var savedY = 0
    private var savedH = 0

    private val handler = Handler(Looper.getMainLooper())
    private var lastQuery = 0L
    private var fgPkg: String? = null
    private var robloxVisible = false
    private var usageOk = false
    private var lastToastTs = 0L

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private val ROBLOX = "com.roblox.client"
    private val VERSION = "v8"
    private var lastDiag = ""
    private val grayAvatars = ArrayList<ImageView>()

    private val poll = object : Runnable {
        override fun run() {
            try {
                usageOk = hasUsageAccess()
                if (usageOk) updateForeground()
                applyVisibility()
                updateDiag()
                refreshAvatars()
            } catch (e: Throwable) {
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startAsForeground()
            if (btn == null) {
                wm = getSystemService(WINDOW_SERVICE) as WindowManager
                density = resources.displayMetrics.density
                createButton()
                createWindow()
                usageOk = hasUsageAccess()
                lastQuery = System.currentTimeMillis() - 6L * 60L * 60L * 1000L
                lastToastTs = System.currentTimeMillis()
                val saved = getSharedPreferences("layout", MODE_PRIVATE).getBoolean("saved", false)
                if (!saved) setEdit(true)
                handler.post(poll)
            }
            if (intent != null && intent.action == "EDIT") {
                setEdit(true)
            }
        } catch (e: Throwable) {
            Toast.makeText(
                applicationContext,
                "ОШИБКА: " + e.javaClass.simpleName + " " + e.message,
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotif(text: String): android.app.Notification {
        return NotificationCompat.Builder(this, "rbchat")
            .setContentTitle("RbChat " + VERSION)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("rbchat", "RbChat", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = buildNotif("Чат появляется поверх Роблокса")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun updateDiag() {
        val d = "доступ к использованию: " + (if (usageOk) "ДА" else "НЕТ") +
            ", Роблокс на экране: " + (if (robloxVisible) "ДА" else "НЕТ") +
            ", последнее приложение: " + (fgPkg ?: "неизвестно")
        if (d != lastDiag) {
            lastDiag = d
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, buildNotif(d))
        }
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun updateForeground() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastQuery - 15000L, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                fgPkg = e.packageName
                if (e.packageName == ROBLOX) robloxVisible = true
            } else if (e.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                if (e.packageName == ROBLOX) robloxVisible = false
                if (e.packageName == fgPkg) fgPkg = null
            }
        }
        lastQuery = now
    }

    private fun applyVisibility() {
        val ownApp = fgPkg == packageName
        if (editMode) {
            if (!usageOk) return
            if (robloxVisible) {
                showButton()
                openWindow()
            } else if (!ownApp) {
                hideButton()
            }
            return
        }
        if (!usageOk) {
            showButton()
            return
        }
        if (robloxVisible) {
            showButton()
        } else if (!ownApp) {
            hideButton()
        }
    }

    private fun showButton() {
        val v = btn ?: return
        val p = btnP ?: return
        if (!btnAttached) {
            wm.addView(v, p)
            btnAttached = true
        }
    }

    private fun hideButton() {
        closeWindow()
        val v = btn ?: return
        if (btnAttached) {
            wm.removeView(v)
            btnAttached = false
            chatList?.removeAllViews()
            grayAvatars.clear()
            addWelcome()
        }
    }

    private fun createButton() {
        val prefs = getSharedPreferences("layout", MODE_PRIVATE)
        val size = prefs.getInt("btnSize", dp(48))
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = prefs.getInt("btnX", dp(16))
        p.y = prefs.getInt("btnY", dp(16))

        val v = TextView(this)
        v.text = "Чат"
        v.setTextColor(Color.WHITE)
        v.textSize = 12f
        v.gravity = Gravity.CENTER

        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = p.x
                    sy = p.y
                    tx = e.rawX
                    ty = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (editMode && btnAttached) {
                        p.x = max(0, sx + (e.rawX - tx).toInt())
                        p.y = max(0, sy + (e.rawY - ty).toInt())
                        wm.updateViewLayout(v, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!editMode) toggleWindow()
                    true
                }
                else -> false
            }
        }

        btn = v
        btnP = p
        styleButton()
    }

    private fun styleButton() {
        val v = btn ?: return
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#0A84C6"))
        if (editMode) bg.setStroke(dp(3), Color.parseColor("#FF9800"))
        v.background = bg
    }

    private fun createWindow() {
        val prefs = getSharedPreferences("layout", MODE_PRIVATE)
        val p = WindowManager.LayoutParams(
            prefs.getInt("winW", dp(320)),
            prefs.getInt("winH", dp(200)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = prefs.getInt("winX", dp(16))
        p.y = prefs.getInt("winY", dp(72))
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        winP = p

        val root = FrameLayout(this)
        val rootBg = GradientDrawable()
        rootBg.setColor(Color.parseColor("#CC101820"))
        rootBg.cornerRadius = dp(10).toFloat()
        root.background = rootBg
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) setFocusable(false)
            false
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30 && typing) {
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                if (ime > 0) {
                    root.post {
                        if (winAttached && typing) {
                            p.height = max(dp(60), p.height - ime)
                            wm.updateViewLayout(root, p)
                        }
                    }
                }
            }
            insets
        }

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL

        val sv = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(8), dp(8), dp(8), dp(8))
        sv.addView(list, ViewGroup.LayoutParams(MP, WC))
        content.addView(sv, LinearLayout.LayoutParams(MP, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val et = EditText(this)
        et.hint = "Сообщение"
        et.setTextColor(Color.WHITE)
        et.setHintTextColor(Color.GRAY)
        et.textSize = 14f
        et.setSingleLine(true)
        et.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrent()
                true
            } else {
                false
            }
        }
        et.setOnTouchListener { _, e ->
            if (!editMode && e.action == MotionEvent.ACTION_DOWN) {
                setFocusable(true)
                val showKb = Runnable {
                    if (typing) {
                        et.requestFocus()
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                et.postDelayed(showKb, 80)
                et.postDelayed(showKb, 350)
            }
            false
        }
        row.addView(et, LinearLayout.LayoutParams(0, WC, 1f))
        val send = Button(this)
        send.text = "Отпр."
        send.setOnClickListener { sendCurrent() }
        row.addView(send, LinearLayout.LayoutParams(WC, WC))
        content.addView(row, LinearLayout.LayoutParams(MP, WC))
        root.addView(content, FrameLayout.LayoutParams(MP, MP))

        val sh = View(this)
        sh.setBackgroundColor(Color.parseColor("#33FF9800"))
        sh.visibility = View.GONE
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        sh.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = p.x
                    sy = p.y
                    tx = e.rawX
                    ty = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = max(0, sx + (e.rawX - tx).toInt())
                    p.y = max(0, sy + (e.rawY - ty).toInt())
                    if (winAttached) wm.updateViewLayout(root, p)
                    true
                }
                else -> true
            }
        }
        root.addView(sh, FrameLayout.LayoutParams(MP, MP))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(Color.parseColor("#CC000000"))
        bar.visibility = View.GONE

        val done = Button(this)
        done.text = "Готово"
        done.textSize = 11f
        done.setOnClickListener { finishEdit() }
        bar.addView(done, LinearLayout.LayoutParams(0, WC, 1f))

        val plus = Button(this)
        plus.text = "Кнопка +"
        plus.textSize = 11f
        plus.setOnClickListener { resizeButton(dp(6)) }
        bar.addView(plus, LinearLayout.LayoutParams(0, WC, 1f))

        val minus = Button(this)
        minus.text = "Кнопка −"
        minus.textSize = 11f
        minus.setOnClickListener { resizeButton(-dp(6)) }
        bar.addView(minus, LinearLayout.LayoutParams(0, WC, 1f))

        root.addView(bar, FrameLayout.LayoutParams(MP, WC, Gravity.TOP))

        val h = View(this)
        h.setBackgroundColor(Color.parseColor("#FF9800"))
        h.visibility = View.GONE
        var sw = 0
        var shh = 0
        var hx = 0f
        var hy = 0f
        h.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sw = p.width
                    shh = p.height
                    hx = e.rawX
                    hy = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.width = max(dp(160), sw + (e.rawX - hx).toInt())
                    p.height = max(dp(110), shh + (e.rawY - hy).toInt())
                    if (winAttached) wm.updateViewLayout(root, p)
                    true
                }
                else -> true
            }
        }
        root.addView(h, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.END))

        win = root
        chatList = list
        scroll = sv
        input = et
        shield = sh
        topBar = bar
        handle = h

        addWelcome()
    }

    private fun addWelcome() {
        addMsg("RbChat " + VERSION + ". Здесь будет чат, сообщения пока видны только на этом телефоне.")
    }

    private fun refreshAvatars() {
        if (grayAvatars.isEmpty()) return
        val f = java.io.File(filesDir, "avatar.png")
        if (!f.exists()) return
        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: return
        for (v in grayAvatars) v.setImageBitmap(bmp)
        grayAvatars.clear()
    }

    private fun sendCurrent() {
        val et = input ?: return
        val t = et.text.toString().trim()
        if (t.isNotEmpty()) {
            addChat(t)
            et.setText("")
        }
        setFocusable(false)
    }

    private fun addMsg(text: String) {
        val list = chatList ?: return
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.textSize = 13f
        list.addView(tv)
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addChat(text: String) {
        val list = chatList ?: return
        val prefs = getSharedPreferences("profile", MODE_PRIVATE)
        val name = prefs.getString("displayName", null) ?: "Я"

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(3), 0, dp(3))

        val iv = ImageView(this)
        val f = java.io.File(filesDir, "avatar.png")
        var bmp: android.graphics.Bitmap? = null
        if (f.exists()) bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        if (bmp != null) {
            iv.setImageBitmap(bmp)
        } else {
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(Color.parseColor("#9E9E9E"))
            iv.setImageDrawable(g)
            grayAvatars.add(iv)
        }
        row.addView(iv, LinearLayout.LayoutParams(dp(28), dp(28)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(6), 0, 0, 0)
        val n = TextView(this)
        n.text = name
        n.setTextColor(Color.parseColor("#7CC4FF"))
        n.textSize = 11f
        val m = TextView(this)
        m.text = text
        m.setTextColor(Color.WHITE)
        m.textSize = 13f
        col.addView(n)
        col.addView(m)
        row.addView(col, LinearLayout.LayoutParams(0, WC, 1f))

        list.addView(row)
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setFocusable(on: Boolean) {
        val p = winP ?: return
        val w = win ?: return
        if (!winAttached) return

        if (on && !typing && !editMode) {
            savedY = p.y
            savedH = p.height
            p.y = 0
            val maxH = (resources.displayMetrics.heightPixels * 0.24f).toInt()
            p.height = max(dp(90), min(p.height, maxH))
            typing = true
        } else if (!on && typing) {
            p.y = savedY
            p.height = savedH
            typing = false
        }

        p.flags = if (on) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        wm.updateViewLayout(w, p)
        if (on) {
            scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
        } else {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(w.windowToken, 0)
        }
    }

    private fun openWindow() {
        val w = win ?: return
        val p = winP ?: return
        if (!winAttached) {
            wm.addView(w, p)
            winAttached = true
        }
    }

    private fun closeWindow() {
        val w = win ?: return
        if (winAttached) {
            setFocusable(false)
            wm.removeView(w)
            winAttached = false
        }
    }

    private fun toggleWindow() {
        if (winAttached) closeWindow() else openWindow()
    }

    private fun resizeButton(delta: Int) {
        val p = btnP ?: return
        val v = btn ?: return
        val s = max(dp(28), p.width + delta)
        p.width = s
        p.height = s
        if (btnAttached) wm.updateViewLayout(v, p)
    }

    private fun setEdit(on: Boolean) {
        if (on) setFocusable(false)
        editMode = on
        val vis = if (on) View.VISIBLE else View.GONE
        shield?.visibility = vis
        topBar?.visibility = vis
        handle?.visibility = vis
        styleButton()
        if (on) {
            showButton()
            openWindow()
        }
    }

    private fun finishEdit() {
        val prefs = getSharedPreferences("layout", MODE_PRIVATE).edit()
        btnP?.let {
            prefs.putInt("btnX", it.x)
            prefs.putInt("btnY", it.y)
            prefs.putInt("btnSize", it.width)
        }
        winP?.let {
            prefs.putInt("winX", it.x)
            prefs.putInt("winY", it.y)
            prefs.putInt("winW", it.width)
            prefs.putInt("winH", it.height)
        }
        prefs.putBoolean("saved", true)
        prefs.apply()
        setEdit(false)
        closeWindow()
        Toast.makeText(applicationContext, "Расположение сохранено", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        try {
            if (::wm.isInitialized) {
                if (btnAttached) btn?.let { wm.removeView(it) }
                if (winAttached) win?.let { wm.removeView(it) }
            }
        } catch (e: Throwable) {
        }
        btnAttached = false
        winAttached = false
        btn = null
        super.onDestroy()
    }
}
