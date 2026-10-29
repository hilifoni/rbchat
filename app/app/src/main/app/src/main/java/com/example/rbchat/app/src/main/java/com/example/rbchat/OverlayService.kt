package com.example.rbchat

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {

    private var wm: WindowManager? = null
    private var bubble: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (bubble == null) showBubble()
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

    private fun showBubble() {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager

        val size = (56 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 300

        val view = TextView(this)
        view.text = "Чат"
        view.setTextColor(Color.WHITE)
        view.textSize = 14f
        view.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#0A84C6"))
        view.background = bg

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    manager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        Toast.makeText(applicationContext, "Здесь будет чат", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }

        manager.addView(view, params)
        bubble = view
    }

    override fun onDestroy() {
        try {
            bubble?.let { wm?.removeView(it) }
        } catch (e: Throwable) {
        }
        bubble = null
        super.onDestroy()
    }
}
