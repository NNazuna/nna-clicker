package com.nnazuna.nnaclicker

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Pill at the top of the screen while an auto tap runs, with its own Stop button. */
internal class RunPill(svc: RecorderService) : ServiceOverlay(svc) {

    // ---------- "auto tap running" pill ----------

    /** Small pill at the top of the screen while an auto tap runs, with its own Stop button. */
    private var runPill: View? = null
    private var runPillPulse: android.animation.Animator? = null

    @SuppressLint("ClickableViewAccessibility")
    fun showRunPill(rec: Recording) {
        removeRunPill()
        val title = if (rec.id == AutoTap.MULTI_TAP_ID) {
            "${rec.gestures.size} targets"
        } else {
            "Auto tap"
        }
        val pill = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(6f), dp(6f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(0xF01C2220.toInt())
                setStroke(dp(1f), 0x665FD3BC)
            }
            elevation = dp(6f).toFloat()
        }
        val dot = View(svc).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(COLOR_PLAY) }
        }
        pill.addView(dot, LinearLayout.LayoutParams(dp(8f), dp(8f)).apply { rightMargin = dp(8f) })
        // Gentle pulse so it reads as "live" without being distracting.
        runPillPulse = android.animation.ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = 700
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
        pill.addView(TextView(svc).apply {
            text = "RUNNING · ${title.uppercase()}"
            setTextColor(0xFFF1F4F2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Ui.font(svc, 3)
            letterSpacing = 0.05f
        })
        pill.addView(TextView(svc).apply {
            text = "Stop"
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Ui.font(svc, 2)
            setPadding(dp(14f), 0, dp(14f), 0)
            contentDescription = "Stop auto tap"
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(COLOR_REC) },
                null,
            )
            setOnClickListener { svc.stopAll() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30f)).apply { leftMargin = dp(12f) })

        val size = screenSize()
        val params = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        pill.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        params.x = (size.x - pill.measuredWidth) / 2
        params.y = dp(36f)
        // Drag it out of the way if it covers something.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        pill.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (e.rawX - downX)).toInt().coerceIn(0, maxOf(0, size.x - pill.width))
                    params.y = (startY + (e.rawY - downY)).toInt().coerceIn(0, maxOf(0, size.y - pill.height))
                    runCatching { wm.updateViewLayout(pill, params) }
                }
            }
            true
        }
        runPill = pill
        runCatching { wm.addView(pill, params) }
    }

    fun removeRunPill() {
        runPillPulse?.cancel()
        runPillPulse = null
        runPill?.let { runCatching { wm.removeView(it) } }
        runPill = null
    }
}
