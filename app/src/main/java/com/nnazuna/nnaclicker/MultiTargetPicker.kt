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

/** Multi-target auto tap: place numbered targets, then tap them in order, forever. */
internal class MultiTargetPicker(svc: RecorderService) : ServiceOverlay(svc) {
    val isOpen: Boolean get() = multiPicker != null
    /** Full-screen multi-target picker. */
    private var multiPicker: FrameLayout? = null
    private val multiTargets = mutableListOf<MultiTarget>()
    /** Non-touchable numbered markers kept visible while multi-target playback runs. */
    private var multiGhost: View? = null
    private class MultiTarget(var x: Float, var y: Float, var delayMs: Long, val view: View)

    // ---------- multi-target auto tap ----------

    /** Place numbered targets, then tap them in order, forever. */
    fun startMultiTap(initial: MultiSetup? = null) {
        if (svc.state != RecorderService.State.IDLE) {
            toast("Stop recording or playback first")
            return
        }
        if (multiPicker != null) return
        svc.closeMenu()
        svc.setupList.closeSetupList()
        panel?.visibility = View.INVISIBLE
        multiTargets.clear()
        var selectedTarget: MultiTarget? = null
        var savedId: String? = initial?.id
        var savedName: String? = initial?.name

        val frame = FrameLayout(svc)
        val size = screenSize()
        lateinit var countLabel: TextView
        lateinit var startButton: TextView
        lateinit var everyLabel: TextView
        lateinit var holdLabel: TextView
        lateinit var delayBox: LinearLayout
        lateinit var delayLabel: TextView
        lateinit var timingBox: LinearLayout
        var everyMs = initial?.everyMs ?: storage.globalSettings().intervalMs.coerceIn(100L, 3_600_000L)
        var holdMs = initial?.holdMs ?: 60L

        fun refreshTiming() {
            everyLabel.text = Ui.fmtSec(everyMs)
            holdLabel.text = "$holdMs ms"
        }

        fun refreshMulti() {
            val n = multiTargets.size
            val selected = selectedTarget?.takeIf { multiTargets.contains(it) }
            if (selected == null) {
                selectedTarget = null
                countLabel.text = if (n == 0) L.t("Tap a spot") else L.t("{} targets", n)
                delayBox.visibility = View.GONE
            } else {
                countLabel.text = L.t("Target {}/{}", multiTargets.indexOf(selected) + 1, n)
                timingBox.visibility = View.VISIBLE
                delayBox.visibility = View.VISIBLE
                delayLabel.text = "${selected.delayMs} ms"
            }
            startButton.isEnabled = n > 0
            startButton.alpha = if (n > 0) 1f else 0.35f
            multiTargets.forEachIndexed { i, t -> (t.view as TextView).text = (i + 1).toString() }
        }

        fun addTarget(x: Float, y: Float, delayMs: Long = 0L) {
            val px = x.coerceIn(dp(20f).toFloat(), (size.x - dp(20f)).toFloat())
            val py = y.coerceIn(dp(20f).toFloat(), (size.y - dp(20f)).toFloat())
            val marker = TextView(svc).apply {
                text = (multiTargets.size + 1).toString()
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Ui.font(svc, 2)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(COLOR_PLAY)
                    setStroke(dp(2f), 0xFFFFFFFF.toInt())
                }
                elevation = dp(4f).toFloat()
            }
            val target = MultiTarget(px, py, delayMs, marker)
            val lp = FrameLayout.LayoutParams(dp(38f), dp(38f)).apply {
                leftMargin = (px - dp(19f)).toInt()
                topMargin = (py - dp(19f)).toInt()
            }
            var startRawX = 0f
            var startRawY = 0f
            var startLeft = 0
            var startTop = 0
            var moved = false
            var longPressed = false
            val longPress = Runnable {
                if (!moved) {
                    longPressed = true
                    multiTargets.remove(target)
                    if (selectedTarget == target) selectedTarget = null
                    frame.removeView(marker)
                    refreshMulti()
                    toast("Target removed")
                }
            }
            marker.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = e.rawX; startRawY = e.rawY
                        startLeft = lp.leftMargin; startTop = lp.topMargin
                        moved = false
                        longPressed = false
                        handler.postDelayed(longPress, 500)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!moved && Math.hypot((e.rawX - startRawX).toDouble(), (e.rawY - startRawY).toDouble()) > dp(6f)) {
                            moved = true
                            handler.removeCallbacks(longPress)
                        }
                        if (moved) {
                            lp.leftMargin = (startLeft + (e.rawX - startRawX)).toInt()
                                .coerceIn(0, maxOf(0, size.x - dp(38f)))
                            lp.topMargin = (startTop + (e.rawY - startRawY)).toInt()
                                .coerceIn(0, maxOf(0, size.y - dp(38f)))
                            marker.layoutParams = lp
                            target.x = lp.leftMargin + dp(19f).toFloat()
                            target.y = lp.topMargin + dp(19f).toFloat()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPress)
                        if (!moved && !longPressed) {
                            selectedTarget = if (selectedTarget == target) null else target
                            refreshMulti()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
                }
                true
            }
            multiTargets.add(target)
            frame.addView(marker, lp)
            refreshMulti()
        }

        val panelW = minOf(dp(320f), size.x - dp(24f))
        val capsule = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(6f).toFloat()
                setColor(OverlayPalette.SURFACE)
                setStroke(dp(1f), 0x33FFFFFF)
            }
            isClickable = true
        }
        val capLp = FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (size.x - panelW) / 2
            topMargin = dp(120f)
        }
        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val handle = ImageView(svc).apply {
            setImageDrawable(IconDrawable(Icon.GRIP, 0x8CFFFFFF.toInt(), dp(20f)))
            scaleType = ImageView.ScaleType.CENTER
        }
        row.addView(handle, LinearLayout.LayoutParams(dp(28f), dp(40f)))

        countLabel = TextView(svc).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Ui.font(svc, 1)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2f), 0, dp(4f), 0)
        }
        row.addView(countLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        fun iconButton(icon: Icon, description: String, onClick: () -> Unit): ImageView = ImageView(svc).apply {
            setImageDrawable(IconDrawable(icon, OverlayPalette.INK, dp(19f)))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = description
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) },
            )
            setOnClickListener { onClick() }
        }
        row.addView(iconButton(Icon.BACK, "Undo") {
            if (multiTargets.isNotEmpty()) {
                val t = multiTargets.removeAt(multiTargets.lastIndex)
                frame.removeView(t.view)
                refreshMulti()
            }
        }, LinearLayout.LayoutParams(dp(36f), dp(40f)))
        row.addView(iconButton(Icon.TRASH, "Clear") {
            multiTargets.forEach { frame.removeView(it.view) }
            multiTargets.clear()
            refreshMulti()
        }, LinearLayout.LayoutParams(dp(36f), dp(40f)))

        everyLabel = TextView(svc).apply {
            setTextColor(COLOR_PLAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Ui.font(svc, 2)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        holdLabel = TextView(svc).apply {
            setTextColor(COLOR_PLAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Ui.font(svc, 2)
            gravity = Gravity.CENTER
            maxLines = 1
        }

        fun stepButton(label: String, onClick: () -> Unit) = TextView(svc).apply {
            text = label
            gravity = Gravity.CENTER
            minWidth = dp(30f)
            minHeight = dp(30f)
            setTextColor(COLOR_PLAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Ui.font(svc, 2)
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) },
            )
            setOnClickListener { onClick() }
        }
        fun timingRow(label: String, value: () -> TextView, stepDown: () -> Unit, stepUp: () -> Unit): LinearLayout {
            val r = LinearLayout(svc).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2f), 0, 0)
            }
            r.addView(TextView(svc).apply {
                text = label
                setTextColor(0x99FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Ui.font(svc, 0)
            }, LinearLayout.LayoutParams(dp(42f), LinearLayout.LayoutParams.WRAP_CONTENT))
            r.addView(stepButton("−", stepDown))
            r.addView(value(), LinearLayout.LayoutParams(dp(76f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            })
            r.addView(stepButton("+", stepUp))
            return r
        }
        timingBox = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4f), 0, 0)
        }
        val everyRow = timingRow("Every", { everyLabel }, {
            everyMs = (everyMs - 100L).coerceAtLeast(100L); refreshTiming()
        }, {
            everyMs = (everyMs + 100L).coerceAtMost(3_600_000L); refreshTiming()
        })
        val holdRow = timingRow("Hold", { holdLabel }, {
            holdMs = (holdMs - 20L).coerceAtLeast(20L); refreshTiming()
        }, {
            holdMs = (holdMs + 20L).coerceAtMost(2_000L); refreshTiming()
        })
        timingBox.addView(everyRow)
        timingBox.addView(holdRow)

        delayBox = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4f), 0, 0)
        }
        delayLabel = TextView(svc).apply {
            setTextColor(COLOR_PLAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Ui.font(svc, 2)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        delayBox.addView(timingRow("Delay", { delayLabel }, {
            selectedTarget?.let { it.delayMs = (it.delayMs - 50L).coerceAtLeast(0L); refreshMulti() }
        }, {
            selectedTarget?.let { it.delayMs = (it.delayMs + 50L).coerceAtMost(10_000L); refreshMulti() }
        }))
        timingBox.addView(delayBox)
        row.addView(iconButton(Icon.SETTINGS, "Timing") {
            timingBox.visibility = if (timingBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            refreshTiming()
        }, LinearLayout.LayoutParams(dp(36f), dp(40f)))

        startButton = TextView(svc).apply {
            text = L.t("Start")
            gravity = Gravity.CENTER
            minHeight = dp(40f)
            setTextColor(0xFF0F1312.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Ui.font(svc, 2)
            setPadding(dp(12f), 0, dp(12f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(4f).toFloat()
                setColor(COLOR_PLAY)
            }
            setOnClickListener {
                if (multiTargets.isEmpty()) {
                    toast("Add at least one target")
                } else {
                    val points = multiTargets.map { MultiPoint(it.x, it.y, it.delayMs) }.toMutableList()
                    val id = savedId ?: UUID.randomUUID().toString()
                    val name = savedName ?: storage.nextSetupName()
                    storage.saveSetup(MultiSetup(id, name, points, everyMs, holdMs, System.currentTimeMillis()))
                    savedId = id
                    savedName = name
                    removeMultiPicker(restorePanel = true)
                    beginMultiTap(points, everyMs, holdMs, name)
                }
            }
        }
        row.addView(startButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)).apply {
            leftMargin = dp(2f)
        })
        row.addView(iconButton(Icon.CLOSE, "Cancel") { removeMultiPicker(restorePanel = true) },
            LinearLayout.LayoutParams(dp(36f), dp(40f)))

        var dragX = 0f
        var dragY = 0f
        var dragLeft = 0
        var dragTop = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = e.rawX; dragY = e.rawY
                    dragLeft = capLp.leftMargin; dragTop = capLp.topMargin
                }
                MotionEvent.ACTION_MOVE -> {
                    capLp.leftMargin = (dragLeft + (e.rawX - dragX)).toInt()
                        .coerceIn(0, maxOf(0, size.x - panelW))
                    val maxTop = maxOf(0, size.y - maxOf(capsule.height, dp(56f)))
                    capLp.topMargin = (dragTop + (e.rawY - dragY)).toInt().coerceIn(0, maxTop)
                    capsule.layoutParams = capLp
                }
            }
            true
        }

        capsule.addView(row)
        capsule.addView(timingBox)
        frame.addView(capsule, capLp)
        refreshTiming()
        initial?.points?.forEach { addTarget(it.x, it.y, it.delayMs) }
        frame.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) addTarget(e.rawX, e.rawY)
            true
        }
        refreshMulti()
        multiPicker = frame
        wm.addView(frame, overlayParams(
            size.x, size.y,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ))
        toast("Tap to add · tap a marker for delay · hold to remove")
    }

    fun removeMultiPicker(restorePanel: Boolean) {
        multiPicker?.let { runCatching { wm.removeView(it) } }
        multiPicker = null
        multiTargets.clear()
        if (restorePanel) panel?.visibility = View.VISIBLE
    }

    private fun showMultiGhost(points: List<MultiPoint>) {
        removeMultiGhost()
        if (points.isEmpty()) return
        val frame = FrameLayout(svc)
        val size = screenSize()
        points.forEachIndexed { i, p ->
            val marker = TextView(svc).apply {
                text = (i + 1).toString()
                gravity = Gravity.CENTER
                setTextColor(COLOR_PLAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Ui.font(svc, 2)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xCC1C2220.toInt())
                    setStroke(dp(2f), COLOR_PLAY)
                }
            }
            frame.addView(marker, FrameLayout.LayoutParams(dp(30f), dp(30f)).apply {
                leftMargin = (p.x - dp(15f)).toInt().coerceIn(0, maxOf(0, size.x - dp(30f)))
                topMargin = (p.y - dp(15f)).toInt().coerceIn(0, maxOf(0, size.y - dp(30f)))
            })
        }
        multiGhost = frame
        wm.addView(frame, overlayParams(
            size.x, size.y,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ))
        svc.bringPanelToFront()
    }

    fun removeMultiGhost() {
        multiGhost?.let { runCatching { wm.removeView(it) } }
        multiGhost = null
    }

    private fun beginMultiTap(points: List<MultiPoint>, every: Long, hold: Long, name: String? = null) {
        val rec = AutoTap.multiTap(points, every, hold, System.currentTimeMillis(), name)
        storage.save(rec)
        svc.playFrom(rec.id, -1, 1500)
        showMultiGhost(points)
        toast("${rec.name} starts in 1.5 s. Volume Down stops.")
    }
}
