package com.example.rbchat

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var density = 1f

    private var btn: TextView? = null
    private var btnP: WindowManager.LayoutParams? = null

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

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int): Int = (v * density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (btn == null) {
                wm = getSystemService(WINDOW_SERVICE) as WindowManager
                density = resources.displayMetrics.density
                createButton()
                createWindow()
                val saved = getSharedPreferences("layout", MODE_PRIVATE).getBoolean("saved", false)
                setEdit(!saved)
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
                    if (editMode) {
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

        wm.addView(v, p)
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

        // содержимое чата
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
        et.setOnTouchListener { _, e ->
            if (!editMode && e.action == MotionEvent.ACTION_DOWN) {
                setFocusable(true)
                et.post {
                    et.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            false
        }
        row.addView(et, LinearLayout.LayoutParams(0, WC, 1f))
        val send = Button(this)
        send.text = "Отпр."
        send.setOnClickListener {
            val t = et.text.toString().trim()
            if (t.isNotEmpty()) {
                addMsg("Я: " + t)
                et.setText("")
            }
            setFocusable(false)
        }
        row.addView(send, LinearLayout.LayoutParams(WC, WC))
        content.addView(row, LinearLayout.LayoutParams(MP, WC))
        root.addView(content, FrameLayout.LayoutParams(MP, MP))

        // плёнка для перетаскивания окна в режиме настройки
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

        // панель кнопок режима настройки
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

        // уголок для изменения размера окна
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

        addMsg("Здесь будет чат. Пока сообщения видны только на этом телефоне.")
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

    private fun setFocusable(on: Boolean) {
        val p = winP ?: return
        val w = win ?: return
        if (!winAttached) return
        p.flags = if (on) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        wm.updateViewLayout(w, p)
        if (!on) {
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
        wm.updateViewLayout(v, p)
    }

    private fun setEdit(on: Boolean) {
        editMode = on
        val vis = if (on) View.VISIBLE else View.GONE
        shield?.visibility = vis
        topBar?.visibility = vis
        handle?.visibility = vis
        styleButton()
        if (on) openWindow()
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
        try {
            if (::wm.isInitialized) {
                btn?.let { wm.removeView(it) }
                if (winAttached) win?.let { wm.removeView(it) }
            }
        } catch (e: Throwable) {
        }
        btn = null
        winAttached = false
        super.onDestroy()
    }
}
