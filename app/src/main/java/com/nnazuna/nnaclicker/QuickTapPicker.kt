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

/** Quick auto tap: one tap on the screen picks the spot, then it repeats. */
internal class QuickTapPicker(svc: RecorderService) : ServiceOverlay(svc) {
    val isOpen: Boolean get() = picker != null
    /** Full-screen one-tap picker for the quick auto tap flow. */
    private var picker: View? = null

    // ---------- quick auto tap ----------

    /** One tap in the target app, then repeat it until stopped. */
    fun startQuickTap() {
        if (svc.state != RecorderService.State.IDLE) {
            toast("Stop recording or playback first")
            return
        }
        if (picker != null) return
        svc.closeMenu()
        panel?.visibility = View.INVISIBLE

        val frame = FrameLayout(svc)
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(12f), dp(10f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(4f).toFloat()
                setColor(OverlayPalette.SURFACE)
            }
        }
        card.addView(TextView(svc).apply {
            text = L.t("Tap the spot to repeat")
            setTextColor(OverlayPalette.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Ui.font(svc, 2)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(TextView(svc).apply {
            text = L.t("Cancel")
            setTextColor(COLOR_PLAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Ui.font(svc, 2)
            setPadding(dp(14f), dp(6f), dp(8f), dp(6f))
            setOnClickListener { removePicker(restorePanel = true) }
        })
        card.isClickable = true
        frame.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP
            topMargin = dp(72f)
            leftMargin = dp(16f)
            rightMargin = dp(16f)
        })
        frame.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val x = e.rawX
                val y = e.rawY
                removePicker(restorePanel = true)
                beginQuickTap(x, y)
            }
            true
        }

        val size = screenSize()
        picker = frame
        wm.addView(frame, overlayParams(
            size.x, size.y,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ))
    }

    fun removePicker(restorePanel: Boolean) {
        picker?.let { runCatching { wm.removeView(it) } }
        picker = null
        if (restorePanel) panel?.visibility = View.VISIBLE
    }

    private fun beginQuickTap(x: Float, y: Float) {
        val every = storage.globalSettings().intervalMs
        val rec = AutoTap.quickTap(x, y, every, System.currentTimeMillis())
        storage.save(rec)
        svc.playFrom(rec.id, -1, 1500)
        toast("Auto tap starts in 1.5 s. Volume Down stops.")
    }
}
