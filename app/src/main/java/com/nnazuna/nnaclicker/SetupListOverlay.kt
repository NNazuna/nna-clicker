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

/** Floating list of saved multi-target setups: open, rename, delete. */
internal class SetupListOverlay(svc: RecorderService) : ServiceOverlay(svc) {
    val isOpen: Boolean get() = setupList != null
    /** Saved setup list opened from the panel. */
    private var setupList: View? = null

    // ---------- saved multi-target setups ----------

    fun closeSetupList() {
        setupList?.let { runCatching { wm.removeView(it) } }
        setupList = null
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showSetupList() {
        if (storage.setups().isEmpty()) {
            toast("No saved setups yet. Press Start once to save one.")
            return
        }
        closeSetupList()
        val size = screenSize()
        val w = minOf(dp(340f), size.x - dp(24f))
        val ink = OverlayPalette.INK
        val muted = OverlayPalette.MUTED
        val surface = OverlayPalette.SURFACE
        val raised = OverlayPalette.RAISED

        val params = overlayParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            x = (size.x - w) / 2
            y = dp(96f)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(12f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(surface)
                setStroke(dp(1f), 0x33FFFFFF)
            }
            elevation = dp(8f).toFloat()
        }
        val list = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(svc).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }

        fun ripple(color: Int, radius: Float) = RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF),
            GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(color) },
            null,
        )
        fun text(s: String, sp: Float, color: Int, weight: Int) = TextView(svc).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = Ui.font(svc, weight)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        fun iconButton(icon: Icon, desc: String, tint: Int, bg: Int, onClick: () -> Unit) = ImageView(svc).apply {
            setImageDrawable(IconDrawable(icon, tint, dp(18f)))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = desc
            background = ripple(bg, 20f)
            setOnClickListener { onClick() }
        }
        fun pill(label: String, fg: Int, bg: Int, onClick: () -> Unit) = TextView(svc).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Ui.font(svc, 2)
            setPadding(dp(14f), 0, dp(14f), 0)
            background = ripple(bg, 18f)
            setOnClickListener { onClick() }
        }
        fun setFocusable(on: Boolean) {
            params.flags = if (on) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            if (setupList === box) runCatching { wm.updateViewLayout(box, params) }
        }

        lateinit var render: () -> Unit
        var editingId: String? = null
        var confirmId: String? = null

        render = render@{
            list.removeAllViews()
            val setups = storage.setups()
            if (setups.isEmpty()) {
                closeSetupList()
                toast("All setups deleted")
                return@render
            }
            setups.forEachIndexed { index, s ->
                val card = LinearLayout(svc).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10f), dp(10f), dp(8f), dp(10f))
                }
                val danger = confirmId == s.id
                card.background = ripple(if (danger) 0xFF3A2226.toInt() else raised, 10f)

                // Badge: target count, the thing people recognise a setup by.
                val badge = LinearLayout(svc).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10f).toFloat()
                        setColor(if (danger) COLOR_REC else COLOR_PLAY)
                    }
                    addView(text("${s.points.size}", 17f, OverlayPalette.ON_ACCENT, 3).apply { gravity = Gravity.CENTER })
                    addView(text(if (s.points.size == 1) "TAP" else "TAPS", 8f, 0xB30E1412.toInt(), 2).apply {
                        gravity = Gravity.CENTER
                        letterSpacing = 0.1f
                    })
                }
                card.addView(badge, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { rightMargin = dp(12f) })

                val info = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
                when {
                    editingId == s.id -> {
                        val input = android.widget.EditText(svc).apply {
                            setText(s.name)
                            setSelection(s.name.length)
                            setSingleLine()
                            setTextColor(ink)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            typeface = Ui.font(svc, 2)
                            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                            background = GradientDrawable().apply {
                                cornerRadius = dp(6f).toFloat()
                                setColor(surface)
                                setStroke(dp(1.5f), COLOR_PLAY)
                            }
                        }
                        fun commit() {
                            val name = input.text.toString().trim().take(40)
                            if (name.isNotEmpty() && name != s.name) {
                                s.name = name
                                storage.saveSetup(s)
                            }
                            editingId = null
                            setFocusable(false)
                            render()
                        }
                        input.setOnEditorActionListener { _, _, _ -> commit(); true }
                        info.addView(input, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                        ))
                        card.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        card.addView(iconButton(Icon.CHECK, "Save name", OverlayPalette.ON_ACCENT, COLOR_PLAY) { commit() },
                            LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { leftMargin = dp(8f) })
                        input.post {
                            input.requestFocus()
                            (svc.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                                .showSoftInput(input, 0)
                        }
                    }
                    danger -> {
                        info.addView(text("Delete \"${s.name}\"?", 14f, ink, 2))
                        info.addView(text("This can't be undone", 12f, 0xFFF2A0A6.toInt(), 0))
                        card.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        card.addView(pill("Keep", ink, 0x22FFFFFF) { confirmId = null; render() },
                            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36f)).apply { rightMargin = dp(6f) })
                        card.addView(pill("Delete", 0xFFFFFFFF.toInt(), COLOR_REC) {
                            storage.deleteSetup(s.id)
                            confirmId = null
                            render()
                        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36f)))
                    }
                    else -> {
                        info.addView(text(s.name, 15f, ink, 2))
                        info.addView(text("Every ${FlowUi.sec(s.everyMs)} · hold ${s.holdMs} ms", 12f, muted, 0)
                            .apply { setPadding(0, dp(2f), 0, 0) })
                        card.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        card.addView(iconButton(Icon.EDIT, "Rename ${s.name}", muted, 0x00000000) {
                            editingId = s.id; confirmId = null
                            setFocusable(true)
                            render()
                        }, LinearLayout.LayoutParams(dp(36f), dp(36f)))
                        card.addView(iconButton(Icon.TRASH, "Delete ${s.name}", muted, 0x00000000) {
                            confirmId = s.id; editingId = null
                            setFocusable(false)
                            render()
                        }, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { rightMargin = dp(4f) })
                        card.addView(iconButton(Icon.PLAY, "Open ${s.name}", OverlayPalette.ON_ACCENT, COLOR_PLAY) {
                            closeSetupList()
                            svc.multiTap.startMultiTap(s)
                        }, LinearLayout.LayoutParams(dp(40f), dp(40f)))
                        card.setOnClickListener { closeSetupList(); svc.multiTap.startMultiTap(s) }
                    }
                }
                list.addView(card, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { if (index > 0) topMargin = dp(8f) })
            }
        }

        // Header
        val header = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), 0, 0, dp(10f))
        }
        val titles = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("SAVED SETUPS", 18f, ink, 3).apply { letterSpacing = 0.04f })
        titles.addView(text("Tap to open · edit or delete with the icons", 12f, muted, 0))
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton(Icon.CLOSE, "Close", ink, 0x22FFFFFF) { closeSetupList() },
            LinearLayout.LayoutParams(dp(36f), dp(36f)))
        box.addView(header)
        box.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        // New setup shortcut
        box.addView(pill("+  New setup", COLOR_PLAY, 0x1F5FD3BC) { closeSetupList(); svc.multiTap.startMultiTap() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42f)).apply { topMargin = dp(10f) })

        render()
        // Keep the card within the screen; long lists scroll.
        scroll.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
        list.measure(
            View.MeasureSpec.makeMeasureSpec(w - dp(24f), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val maxList = size.y - dp(96f) - dp(200f)
        if (list.measuredHeight > maxList) scroll.layoutParams.height = maxList

        box.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && editingId == null) { closeSetupList(); true } else false
        }
        setupList = box
        wm.addView(box, params)
    }
}
