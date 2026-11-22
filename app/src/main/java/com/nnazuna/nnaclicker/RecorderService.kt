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

class RecorderService : AccessibilityService() {

    companion object {
        var instance: RecorderService? = null
            private set

        /** Dipanggil saat daftar rekaman / status berubah (supaya MainActivity bisa refresh). */
        var onChanged: (() -> Unit)? = null
    }

    enum class State { IDLE, RECORDING, PLAYING }

    private val COLOR_REC = 0xFFF2545F.toInt()
    private val COLOR_PLAY = 0xFF5FD3BC.toInt()

    var state = State.IDLE
        private set

    private lateinit var wm: WindowManager
    private lateinit var storage: Storage
    private val handler = Handler(Looper.getMainLooper())

    // Panel mengambang
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private lateinit var btnRecord: ImageView
    private lateinit var btnPlay: ImageView
    private lateinit var btnPause: ImageView
    private lateinit var btnSnap: ImageView
    private lateinit var btnApp: ImageView
    private lateinit var btnClose: ImageView
    private lateinit var statusBox: LinearLayout
    private lateinit var statusDot: View
    private lateinit var status: TextView
    private var pulse: ObjectAnimator? = null
    private lateinit var handle: ImageView
    /** Panel shrunk to a small bubble. */
    private var minimized = false
    /** Recording list opened from the panel. */
    private var menuView: View? = null
    /** Full-screen one-tap picker for the quick auto tap flow. */
    private var picker: View? = null
    /** Full-screen multi-target picker. */
    private var multiPicker: FrameLayout? = null
    private val multiTargets = mutableListOf<MultiTarget>()
    /** Non-touchable numbered markers kept visible while multi-target playback runs. */
    private var multiGhost: View? = null

    private class MultiTarget(var x: Float, var y: Float, val view: View)
    /** Lapisan gelap saat memutar (tidak menangkap sentuhan) + menjaga layar tetap menyala. */
    private var dimView: View? = null
    private var playStartedAt = 0L
    /** Playback position shown on the panel. */
    private var playLoopNow = 1
    private var playStepNow = -1
    private var playTotal = 0
    private var paused = false
    /** Options and recording of the current playback. */
    private var playLoops = 1
    private var playMaxMin = 0
    private var playingRec: Recording? = null

    // Perekaman
    private var capture: CaptureView? = null
    private var captureParams: WindowManager.LayoutParams? = null
    private var recordStart = 0L
    private val recorded = mutableListOf<RecGesture>()
    /** Image steps marked with the camera button while recording (cropped later in the editor). */
    private val recordedImages = mutableListOf<ImageStep>()
    private var snapping = false
    /** Mode rekam/putar yang sedang berjalan memakai Shizuku. */
    private var shizukuActive = false

    // Pemutaran (mode aksesibilitas)
    private var player: Player? = null

    private val ticker = object : Runnable {
        override fun run() {
            when (state) {
                State.RECORDING -> {
                    val sec = (SystemClock.uptimeMillis() - recordStart) / 1000
                    val count = if (shizukuActive) {
                        try { ShizukuBridge.backend?.recordCount() ?: 0 } catch (e: Exception) { 0 }
                    } else recorded.size
                    status.text = String.format(Locale.US, "%02d:%02d   %d", sec / 60, sec % 60, count)
                    handler.postDelayed(this, 500)
                }
                State.PLAYING -> if (shizukuActive) {
                    val loop = try { ShizukuBridge.backend?.playLoop() ?: -1 } catch (e: Exception) { -1 }
                    if (loop < 0) {
                        val err = try { ShizukuBridge.backend?.lastError() ?: "" } catch (e: Exception) { "" }
                        finishPlaying(err.ifEmpty { null })
                    } else {
                        val stepNow = try { ShizukuBridge.backend?.playStep() ?: -1 } catch (e: Exception) { -1 }
                        playLoopNow = loop
                        playStepNow = stepNow
                        status.text = playText()
                        handler.postDelayed(this, 300)
                    }
                }
                State.IDLE -> {}
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        storage = Storage(this)
        L.load(this)
        ShizukuBridge.init(this)
        if (storage.useShizuku) ShizukuBridge.connect()
        showPanel()
        onChanged?.invoke()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Volume Bawah = berhenti merekam / memutar, tanpa perlu menyentuh layar. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (multiPicker != null && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) removeMultiPicker(restorePanel = true)
            return true
        }
        if (picker != null && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) removePicker(restorePanel = true)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && state != State.IDLE) {
            if (event.action == KeyEvent.ACTION_DOWN) handler.post { stopAll() }
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance == null) return
        stopAll()
        removePicker(restorePanel = false)
        removeMultiPicker(restorePanel = false)
        removeMultiGhost()
        removeCapture()
        hidePanel()
        instance = null
        onChanged?.invoke()
    }

    // ---------- util ----------

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    private fun rotation(): Int {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
    }

    private fun toast(msg: String) = LToast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun useShizukuNow(): Boolean = storage.useShizuku && ShizukuBridge.isConnected

    // ---------- panel mengambang ----------

    val isPanelShown: Boolean get() = panel != null

    /** Entry point from the main screen: place numbered targets over any app. */
    fun startMultiTarget() = startMultiTap()

    @SuppressLint("ClickableViewAccessibility")
    fun showPanel() {
        if (panel != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(4f), dp(6f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(26f).toFloat()
                setColor(0xEB1C2220.toInt())
                setStroke(dp(1f), 0x33FFFFFF)
            }
        }

        fun iconButton(onClick: () -> Unit) = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER
            minimumWidth = dp(44f)
            minimumHeight = dp(44f)
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) },
            )
            setOnClickListener { onClick() }
            root.addView(this, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        }

        handle = ImageView(this).apply {
            setImageDrawable(IconDrawable(Icon.GRIP, 0x8CFFFFFF.toInt(), dp(22f)))
            scaleType = ImageView.ScaleType.CENTER
        }
        root.addView(handle, LinearLayout.LayoutParams(dp(30f), dp(44f)))

        btnRecord = iconButton { onRecordPressed() }
        btnPlay = iconButton { onPlayPressed() }
        btnPause = iconButton { togglePause() }
        btnSnap = iconButton { snap() }
        btnSnap.setImageDrawable(IconDrawable(Icon.CAMERA, 0xE6FFFFFF.toInt(), dp(22f)))

        // Status: titik merah berdenyut saat merekam + angka waktu/ulangan.
        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), 0, dp(10f), 0)
            visibility = View.GONE
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(COLOR_REC) }
        }
        statusBox.addView(statusDot, LinearLayout.LayoutParams(dp(8f), dp(8f)).apply { rightMargin = dp(8f) })
        status = TextView(this).apply {
            setTextColor(0xFFF1F4F2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Ui.font(this@RecorderService, 1)
            fontFeatureSettings = "tnum"
        }
        statusBox.addView(status)
        root.addView(statusBox)

        btnApp = iconButton { toggleMenu() }
        btnApp.setImageDrawable(IconDrawable(Icon.LIST, 0xCCFFFFFF.toInt(), dp(22f)))
        btnClose = iconButton { hidePanel() }
        btnClose.setImageDrawable(IconDrawable(Icon.CLOSE, 0x99FFFFFF.toInt(), dp(20f)))

        val params = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            x = dp(12f)
            y = dp(160f)
        }

        // Drag the grip to move the panel. Tap the bubble to expand; long-press the grip to shrink.
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var dragged = false
        val longPress = Runnable { if (!dragged) { setMinimized(!minimized) ; dragged = true } }
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    dragged = false
                    handler.postDelayed(longPress, 500)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragged && Math.hypot((e.rawX - touchX).toDouble(), (e.rawY - touchY).toDouble()) > dp(8f)) {
                        dragged = true
                        handler.removeCallbacks(longPress)
                        closeMenu()
                    }
                    if (dragged) {
                        params.x = startX + (e.rawX - touchX).toInt()
                        params.y = startY + (e.rawY - touchY).toInt()
                        panel?.let { wm.updateViewLayout(it, params) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (!dragged && e.actionMasked == MotionEvent.ACTION_UP && minimized) setMinimized(false)
                }
            }
            true
        }

        panel = root
        panelParams = params
        wm.addView(root, params)
        updatePanel()
        onChanged?.invoke()
        PanelTileService.refresh(this)
    }

    fun hidePanel() {
        closeMenu()
        stopAll()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        onChanged?.invoke()
        PanelTileService.refresh(this)
    }

    /** Panel harus selalu di atas lapisan perekam supaya tombolnya bisa ditekan. */
    private fun bringPanelToFront() {
        val p = panel ?: return
        val params = panelParams ?: return
        runCatching { wm.removeView(p) }
        wm.addView(p, params)
    }

    /** Area panel di layar (plus margin), untuk membuang sentuhan ke panel dari rekaman Shizuku. */
    private fun panelRect(): Rect? {
        val p = panel ?: return null
        val loc = IntArray(2)
        p.getLocationOnScreen(loc)
        val m = dp(8f)
        return Rect(loc[0] - m, loc[1] - m, loc[0] + p.width + m, loc[1] + p.height + m)
    }

    private fun setIcon(v: ImageView, kind: Icon, color: Int) {
        v.setImageDrawable(IconDrawable(kind, color, dp(24f)))
    }

    private fun updatePanel() {
        if (panel == null) return
        pulse?.cancel()
        statusDot.alpha = 1f
        if (minimized) {
            for (v in listOf(btnRecord, btnPlay, btnPause, btnSnap, btnApp, btnClose, statusBox)) v.visibility = View.GONE
            val color = when (state) {
                State.RECORDING -> COLOR_REC
                State.PLAYING -> COLOR_PLAY
                State.IDLE -> 0xCCFFFFFF.toInt()
            }
            handle.setImageDrawable(IconDrawable(Icon.RECORD, color, dp(26f)))
            handle.layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
            return
        }
        handle.setImageDrawable(IconDrawable(Icon.GRIP, 0x8CFFFFFF.toInt(), dp(22f)))
        handle.layoutParams = LinearLayout.LayoutParams(dp(30f), dp(44f))
        when (state) {
            State.IDLE -> {
                setIcon(btnRecord, Icon.RECORD, COLOR_REC); btnRecord.visibility = View.VISIBLE
                setIcon(btnPlay, Icon.PLAY, COLOR_PLAY); btnPlay.visibility = View.VISIBLE
                btnPause.visibility = View.GONE
                btnSnap.visibility = View.VISIBLE
                btnApp.visibility = View.VISIBLE
                btnClose.visibility = View.VISIBLE
                statusBox.visibility = View.GONE
            }
            State.RECORDING -> {
                setIcon(btnRecord, Icon.STOP, 0xFFFFFFFF.toInt()); btnRecord.visibility = View.VISIBLE
                btnPlay.visibility = View.GONE
                btnPause.visibility = View.GONE
                btnSnap.visibility = View.VISIBLE
                btnApp.visibility = View.GONE
                btnClose.visibility = View.GONE
                statusBox.visibility = View.VISIBLE
                (statusDot.background as GradientDrawable).setColor(COLOR_REC)
                pulse = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.25f).apply {
                    duration = 700
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
            State.PLAYING -> {
                btnRecord.visibility = View.GONE
                btnSnap.visibility = View.GONE
                setIcon(btnPlay, Icon.STOP, 0xFFFFFFFF.toInt()); btnPlay.visibility = View.VISIBLE
                setIcon(btnPause, if (paused) Icon.PLAY else Icon.PAUSE, if (paused) COLOR_PLAY else 0xFFFFFFFF.toInt())
                btnPause.visibility = View.VISIBLE
                btnApp.visibility = View.GONE
                btnClose.visibility = View.GONE
                statusBox.visibility = View.VISIBLE
                (statusDot.background as GradientDrawable).setColor(if (paused) 0xFFF2B33D.toInt() else COLOR_PLAY)
            }
        }
    }

    /** Pause or resume playback from the panel. A gesture that already started still finishes. */
    private fun togglePause() {
        if (state != State.PLAYING) return
        paused = !paused
        if (shizukuActive) {
            try { ShizukuBridge.backend?.setPaused(paused) } catch (_: Exception) {}
        } else {
            player?.paused = paused
        }
        // The auto stop timer doesn't count paused time.
        if (paused) {
            handler.removeCallbacks(limitStop)
            pausedAt = SystemClock.uptimeMillis()
        } else if (playMaxMin > 0) {
            limitLeft -= pausedAt - limitFrom
            limitFrom = SystemClock.uptimeMillis()
            handler.postDelayed(limitStop, limitLeft.coerceAtLeast(0))
        }
        status.text = playText()
        updatePanel()
    }

    private var pausedAt = 0L
    /** Auto stop: time left (ms) as of [limitFrom]. */
    private var limitLeft = 0L
    private var limitFrom = 0L

    private fun setMinimized(on: Boolean) {
        minimized = on
        closeMenu()
        updatePanel()
    }

    // ---------- recording list on the panel ----------

    private fun toggleMenu() {
        if (menuView != null) closeMenu() else openMenu()
    }

    private fun closeMenu() {
        menuView?.let { runCatching { wm.removeView(it) } }
        menuView = null
    }

    // ---------- quick auto tap ----------

    /** One tap in the target app, then repeat it until stopped. */
    private fun startQuickTap() {
        if (state != State.IDLE) {
            toast("Stop recording or playback first")
            return
        }
        if (picker != null) return
        closeMenu()
        panel?.visibility = View.INVISIBLE

        val frame = FrameLayout(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(12f), dp(10f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(4f).toFloat()
                setColor(0xFF1C2220.toInt())
            }
        }
        card.addView(TextView(this).apply {
            text = L.t("Tap the spot to repeat")
            setTextColor(0xFFF1F4F2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Ui.font(this@RecorderService, 2)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(TextView(this).apply {
            text = L.t("Cancel")
            setTextColor(COLOR_PLAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Ui.font(this@RecorderService, 2)
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

    private fun removePicker(restorePanel: Boolean) {
        picker?.let { runCatching { wm.removeView(it) } }
        picker = null
        if (restorePanel) panel?.visibility = View.VISIBLE
    }

    private fun beginQuickTap(x: Float, y: Float) {
        val every = storage.globalSettings().intervalMs.coerceIn(100L, 3_600_000L)
        val rec = Recording(UUID.randomUUID().toString(), "", System.currentTimeMillis(), mutableListOf())
        val label = FlowStep(0, FlowStep.LABEL, ref = FlowStep.newId(), name = "Repeat")
        val gesture = RecGesture(1, listOf(
            Stroke(listOf(TouchPoint(x, y, 0), TouchPoint(x, y, 60))),
        ))
        val jump = FlowStep(gesture.at + gesture.duration + every, FlowStep.GOTO, ref = label.ref)
        rec.flows.add(label)
        rec.gestures.add(gesture)
        rec.flows.add(jump)
        rec.name = "Auto tap · every ${FlowUi.sec(every)}"
        storage.save(rec)
        playFrom(rec.id, -1, 1500)
        toast("Auto tap starts in 1.5 s. Volume Down stops.")
    }

    // ---------- multi-target auto tap ----------

    /** Place numbered targets, then tap them in order, forever. */
    private fun startMultiTap() {
        if (state != State.IDLE) {
            toast("Stop recording or playback first")
            return
        }
        if (multiPicker != null) return
        closeMenu()
        panel?.visibility = View.INVISIBLE
        multiTargets.clear()

        val frame = FrameLayout(this)
        val size = screenSize()
        lateinit var countLabel: TextView
        lateinit var startButton: TextView
        lateinit var everyLabel: TextView
        lateinit var holdLabel: TextView
        var everyMs = storage.globalSettings().intervalMs.coerceIn(100L, 3_600_000L)
        var holdMs = 60L

        fun refreshTiming() {
            everyLabel.text = Ui.fmtSec(everyMs)
            holdLabel.text = "$holdMs ms"
        }

        fun refreshMulti() {
            val n = multiTargets.size
            countLabel.text = if (n == 0) L.t("Tap a spot") else L.t("{} targets", n)
            startButton.isEnabled = n > 0
            startButton.alpha = if (n > 0) 1f else 0.35f
            multiTargets.forEachIndexed { i, t -> (t.view as TextView).text = (i + 1).toString() }
        }

        fun addTarget(x: Float, y: Float) {
            val px = x.coerceIn(dp(20f).toFloat(), (size.x - dp(20f)).toFloat())
            val py = y.coerceIn(dp(20f).toFloat(), (size.y - dp(20f)).toFloat())
            val marker = TextView(this).apply {
                text = (multiTargets.size + 1).toString()
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Ui.font(this@RecorderService, 2)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(COLOR_PLAY)
                    setStroke(dp(2f), 0xFFFFFFFF.toInt())
                }
                elevation = dp(4f).toFloat()
            }
            val target = MultiTarget(px, py, marker)
            val lp = FrameLayout.LayoutParams(dp(38f), dp(38f)).apply {
                leftMargin = (px - dp(19f)).toInt()
                topMargin = (py - dp(19f)).toInt()
            }
            var startRawX = 0f
            var startRawY = 0f
            var startLeft = 0
            var startTop = 0
            var moved = false
            marker.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = e.rawX; startRawY = e.rawY
                        startLeft = lp.leftMargin; startTop = lp.topMargin
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!moved && Math.hypot((e.rawX - startRawX).toDouble(), (e.rawY - startRawY).toDouble()) > dp(6f)) {
                            moved = true
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
                        if (!moved) {
                            multiTargets.remove(target)
                            frame.removeView(marker)
                            refreshMulti()
                            toast("Target removed")
                        }
                    }
                }
                true
            }
            multiTargets.add(target)
            frame.addView(marker, lp)
            refreshMulti()
        }

        val panelW = minOf(dp(320f), size.x - dp(24f))
        val capsule = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(6f).toFloat()
                setColor(0xFF1C2220.toInt())
                setStroke(dp(1f), 0x33FFFFFF)
            }
            isClickable = true
        }
        val capLp = FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (size.x - panelW) / 2
            topMargin = dp(120f)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val handle = ImageView(this).apply {
            setImageDrawable(IconDrawable(Icon.GRIP, 0x8CFFFFFF.toInt(), dp(20f)))
            scaleType = ImageView.ScaleType.CENTER
        }
        row.addView(handle, LinearLayout.LayoutParams(dp(28f), dp(40f)))

        countLabel = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Ui.font(this@RecorderService, 1)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2f), 0, dp(4f), 0)
        }
        row.addView(countLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        fun iconButton(icon: Icon, description: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
            setImageDrawable(IconDrawable(icon, 0xFFF1F4F2.toInt(), dp(19f)))
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

        everyLabel = TextView(this).apply {
            setTextColor(COLOR_PLAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Ui.font(this@RecorderService, 2)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        holdLabel = TextView(this).apply {
            setTextColor(COLOR_PLAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Ui.font(this@RecorderService, 2)
            gravity = Gravity.CENTER
            maxLines = 1
        }

        fun stepButton(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            minWidth = dp(30f)
            minHeight = dp(30f)
            setTextColor(COLOR_PLAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Ui.font(this@RecorderService, 2)
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) },
            )
            setOnClickListener { onClick() }
        }
        fun timingRow(label: String, value: () -> TextView, stepDown: () -> Unit, stepUp: () -> Unit): LinearLayout {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2f), 0, 0)
            }
            r.addView(TextView(this).apply {
                text = label
                setTextColor(0x99FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Ui.font(this@RecorderService, 0)
            }, LinearLayout.LayoutParams(dp(42f), LinearLayout.LayoutParams.WRAP_CONTENT))
            r.addView(stepButton("−", stepDown))
            r.addView(value(), LinearLayout.LayoutParams(dp(76f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            })
            r.addView(stepButton("+", stepUp))
            return r
        }
        val timingBox = LinearLayout(this).apply {
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
        row.addView(iconButton(Icon.SETTINGS, "Timing") {
            timingBox.visibility = if (timingBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            refreshTiming()
        }, LinearLayout.LayoutParams(dp(36f), dp(40f)))

        startButton = TextView(this).apply {
            text = L.t("Start")
            gravity = Gravity.CENTER
            minHeight = dp(40f)
            setTextColor(0xFF0F1312.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Ui.font(this@RecorderService, 2)
            setPadding(dp(12f), 0, dp(12f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(4f).toFloat()
                setColor(COLOR_PLAY)
            }
            setOnClickListener {
                if (multiTargets.isEmpty()) {
                    toast("Add at least one target")
                } else {
                    val points = multiTargets.map { PointF(it.x, it.y) }
                    removeMultiPicker(restorePanel = true)
                    beginMultiTap(points, everyMs, holdMs)
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
        toast("Tap each target you want to repeat")
    }

    private fun removeMultiPicker(restorePanel: Boolean) {
        multiPicker?.let { runCatching { wm.removeView(it) } }
        multiPicker = null
        multiTargets.clear()
        if (restorePanel) panel?.visibility = View.VISIBLE
    }

    private fun showMultiGhost(points: List<PointF>) {
        removeMultiGhost()
        if (points.isEmpty()) return
        val frame = FrameLayout(this)
        val size = screenSize()
        points.forEachIndexed { i, p ->
            val marker = TextView(this).apply {
                text = (i + 1).toString()
                gravity = Gravity.CENTER
                setTextColor(COLOR_PLAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Ui.font(this@RecorderService, 2)
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
        bringPanelToFront()
    }

    private fun removeMultiGhost() {
        multiGhost?.let { runCatching { wm.removeView(it) } }
        multiGhost = null
    }

    private fun beginMultiTap(points: List<PointF>, every: Long, hold: Long) {
        val rec = Recording(UUID.randomUUID().toString(), "", System.currentTimeMillis(), mutableListOf())
        val label = FlowStep(0, FlowStep.LABEL, ref = FlowStep.newId(), name = "Repeat")
        rec.flows.add(label)
        var at = 1L
        for (point in points) {
            rec.gestures.add(RecGesture(at, listOf(
                Stroke(listOf(TouchPoint(point.x, point.y, 0), TouchPoint(point.x, point.y, hold))),
            )))
            at += every
        }
        rec.flows.add(FlowStep(at, FlowStep.GOTO, ref = label.ref))
        rec.name = "Multi tap · ${points.size} targets · every ${FlowUi.sec(every)}"
        storage.save(rec)
        playFrom(rec.id, -1, 1500)
        showMultiGhost(points)
        toast("Multi tap starts in 1.5 s. Volume Down stops.")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun openMenu() {
        val anchor = panel ?: return
        val anchorParams = panelParams ?: return
        val recs = storage.list()
        val selected = storage.selected()?.id

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(8f), dp(6f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(0xFF1C2220.toInt())
                setStroke(dp(1f), 0x33FFFFFF)
            }
        }
        fun row(label: String, color: Int, bold: Boolean, onClick: (() -> Unit)?) = TextView(this).apply {
            text = L.t(label)
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Ui.font(this@RecorderService, if (bold) 2 else 1)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            if (onClick != null) {
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
                    GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0xFFFFFFFF.toInt()) },
                )
                setOnClickListener { onClick() }
            }
        }

        box.addView(row("Recordings", 0x99FFFFFF.toInt(), false, null).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12f), dp(2f), dp(12f), dp(4f))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (recs.isEmpty()) list.addView(row("No recordings yet", 0x99FFFFFF.toInt(), false, null))
        for (r in recs) {
            val on = r.id == selected
            list.addView(row((if (on) "✓  " else "     ") + r.name, if (on) COLOR_PLAY else 0xFFF1F4F2.toInt(), on) {
                storage.selectedId = r.id
                closeMenu()
                toast("Selected: ${r.name}")
                onChanged?.invoke()
            })
        }
        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        val maxRows = 6
        box.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            if (recs.size > maxRows) dp(44f * maxRows) else LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        box.addView(View(this).apply { setBackgroundColor(0x33FFFFFF) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)).apply { setMargins(dp(8f), dp(4f), dp(8f), dp(4f)) })
        box.addView(row("Multi tap here", COLOR_PLAY, true) { closeMenu(); startMultiTap() })
        box.addView(row("Auto tap here", COLOR_PLAY, true) { closeMenu(); startQuickTap() })
        box.addView(row("Open NNA Clicker", 0xFFF1F4F2.toInt(), false) { closeMenu(); openApp() })
        box.addView(row("Shrink panel", 0xFFF1F4F2.toInt(), false) { setMinimized(true) })

        val size = screenSize()
        val w = minOf(dp(280f), size.x - dp(16f))
        val params = overlayParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        box.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size.y, View.MeasureSpec.AT_MOST),
        )
        params.x = anchorParams.x.coerceIn(0, maxOf(0, size.x - w))
        val below = anchorParams.y + anchor.height + dp(6f)
        params.y = if (below + box.measuredHeight <= size.y) below
        else maxOf(0, anchorParams.y - box.measuredHeight - dp(6f))
        box.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { closeMenu(); true } else false
        }
        menuView = box
        wm.addView(box, params)
    }

    /** Screen as a half-resolution frame, with the panel hidden. Used by the image step "Test" button. */
    fun captureFrame(cb: (ScreenFrame?) -> Unit) {
        closeMenu()
        panel?.visibility = View.INVISIBLE
        handler.postDelayed({
            val done = { f: ScreenFrame? -> handler.post { panel?.visibility = View.VISIBLE; cb(f) } }
            val backend = if (useShizukuNow()) ShizukuBridge.backend else null
            when {
                backend != null -> Thread {
                    val png = try { backend.snap() } catch (e: Exception) { null }
                    val f = png?.let { bytes ->
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { b ->
                            val px = IntArray(b.width * b.height)
                            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                            ImageMatcher.downscale(px, b.width, b.height).also { b.recycle() }
                        }
                    }
                    if (f == null && Build.VERSION.SDK_INT >= 30) A11yCapture.frame(this) { done(it) } else done(f)
                }.start()
                Build.VERSION.SDK_INT >= 30 -> A11yCapture.frame(this) { done(it) }
                else -> done(null)
            }
        }, 150)
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun onRecordPressed() {
        closeMenu()
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecording()
            State.PLAYING -> {}
        }
    }

    private fun onPlayPressed() {
        closeMenu()
        when (state) {
            State.IDLE -> startPlaying()
            State.PLAYING -> stopPlaying()
            State.RECORDING -> {}
        }
    }

    // ---------- merekam ----------

    private fun startRecording() {
        recorded.clear()
        recordedImages.clear()
        recordStart = SystemClock.uptimeMillis()
        shizukuActive = false

        val size = screenSize()
        if (useShizukuNow()) {
            val rot = rotation()
            val natW = if (rot % 2 == 1) size.y else size.x
            val natH = if (rot % 2 == 1) size.x else size.y
            val svc = ShizukuBridge.backend
            val ok = try { svc?.startRecord(natW, natH, rot) == true } catch (e: Exception) { false }
            if (ok) {
                shizukuActive = true
            } else {
                val err = try { svc?.lastError() } catch (e: Exception) { null }
                toast("Shizuku recording failed, using standard mode. ${err ?: ""}")
            }
        }

        // Mode Shizuku: hanya bingkai merah (tidak menangkap sentuhan).
        // Mode biasa: lapisan penangkap sentuhan.
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (shizukuActive) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val params = overlayParams(size.x, size.y, flags)
        val view = CaptureView(this, recordStart) { g -> onGestureCaptured(g) }
        capture = view
        captureParams = params
        wm.addView(view, params)
        bringPanelToFront()

        state = State.RECORDING
        updatePanel()
        handler.post(ticker)
    }

    private fun onGestureCaptured(g: RecGesture) {
        if (state != State.RECORDING || shizukuActive) return
        recorded.add(g)
        passThrough(g)
    }

    /**
     * Mode biasa: teruskan gerakan yang barusan direkam ke aplikasi di bawah —
     * matikan sentuhan lapisan perekam sebentar, kirim gerakan, lalu nyalakan lagi.
     */
    private fun passThrough(g: RecGesture) {
        setCaptureTouchable(false)
        handler.postDelayed({
            GestureRunner.run(this, g, 1f, handler, { true }) {
                handler.postDelayed({
                    if (state == State.RECORDING) setCaptureTouchable(true)
                }, 10)
            }
        }, 20)
    }

    private fun setCaptureTouchable(touchable: Boolean) {
        val v = capture ?: return
        val p = captureParams ?: return
        p.flags = if (touchable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun removeCapture() {
        capture?.let { runCatching { wm.removeView(it) } }
        capture = null
        captureParams = null
    }

    private fun stopRecording() {
        handler.removeCallbacks(ticker)
        removeCapture()
        state = State.IDLE
        updatePanel()

        val gestures: MutableList<RecGesture> = if (shizukuActive) {
            shizukuActive = false
            val r = try {
                ShizukuBridge.backend?.stopRecord()
            } catch (e: Exception) {
                null
            }
            val rect = panelRect()
            (r?.gestures ?: mutableListOf()).filterNot { g ->
                val p = g.strokes.first().points.first()
                rect != null && rect.contains(p.x.toInt(), p.y.toInt())
            }.toMutableList()
        } else {
            recorded.toMutableList()
        }
        recorded.clear()
        val images = recordedImages.toMutableList()
        recordedImages.clear()

        if (gestures.isEmpty() && images.isEmpty()) {
            toast("Nothing recorded")
            return
        }
        val name = L.t("Recording {}", SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date()))
        val rec = Recording(UUID.randomUUID().toString(), name, System.currentTimeMillis(), gestures, images)
        storage.save(rec)
        storage.selectedId = rec.id
        toast(if (images.isEmpty()) "Saved: $name" else "Saved: $name. Open the editor to choose the image areas.")
        onChanged?.invoke()
    }

    // ---------- memutar ----------

    /**
     * Play recording [id] starting at the step at time [at] (first loop only), after
     * at least [minDelayMs], so there is time to switch to the game.
     */
    fun playFrom(id: String, at: Long, minDelayMs: Long = 3000) {
        if (state != State.IDLE) return
        storage.selectedId = id
        showPanel()
        startPlaying(at, minDelayMs)
        onChanged?.invoke()
    }

    private var stopByUser = false

    private fun startPlaying(fromAt: Long = -1, minDelayMs: Long = 0) {
        removeMultiGhost()
        val rec = storage.selected()
        if (rec == null || Program(rec).isEmpty) {
            toast("No recording selected")
            return
        }
        rec.startAt = fromAt
        stopByUser = false
        paused = false
        playingRec = rec
        val cfg = storage.settingsFor(rec)
        playLoops = cfg.loops
        playMaxMin = cfg.maxMinutes
        val startDelay = maxOf(cfg.startDelayMs, minDelayMs)
        if (rec.images.any { !it.ready }) toast("Some image steps have no area yet and will be skipped")
        // Image matching corrects for the dim layer; keep it light enough to still see details.
        rec.dim = if (storage.dimScreen) dimFraction(rec) else 0f
        val loops = cfg.loops
        state = State.PLAYING
        updatePanel()
        status.text = L.t("Starting…")
        playTotal = Program(rec).steps.size
        playLoopNow = 1
        playStepNow = -1
        showDim()
        playStartedAt = SystemClock.uptimeMillis()
        if (playMaxMin > 0) {
            limitLeft = playMaxMin * 60_000L
            limitFrom = SystemClock.uptimeMillis()
            handler.postDelayed(limitStop, limitLeft)
        }

        if (useShizukuNow()) {
            val svc = ShizukuBridge.backend!!
            val ok = try {
                svc.play(
                    rec, loops, cfg.speed, cfg.intervalMs, startDelay,
                    cfg.jitterPx, cfg.jitterPct,
                )
            } catch (e: Exception) {
                false
            }
            if (ok) {
                shizukuActive = true
                handler.post(ticker)
                return
            }
            val err = try { svc.lastError() } catch (e: Exception) { "" }
            toast("Shizuku playback failed, using standard mode. $err")
        }

        shizukuActive = false
        player = Player(
            svc = this,
            rec = rec,
            loops = loops,
            speed = cfg.speed,
            intervalMs = cfg.intervalMs,
            startDelayMs = startDelay,
            jitterPx = cfg.jitterPx,
            jitterPct = cfg.jitterPct,
            onLoop = { n -> playLoopNow = n; status.text = playText() },
            onStep = { i -> playStepNow = i; status.text = playText() },
            onFinish = { err -> finishPlaying(err) },
        ).also { it.start() }
    }

    private fun stopPlaying(byUser: Boolean = true) {
        stopByUser = byUser
        if (shizukuActive) {
            try { ShizukuBridge.backend?.stopPlay() } catch (_: Exception) {}
            finishPlaying()
        } else {
            player?.stop()
        }
    }

    /** Batas waktu putar tercapai. */
    private val limitStop = Runnable {
        if (state == State.PLAYING) {
            stopPlaying(byUser = false)
            toast("Auto stopped after $playMaxMin min")
        }
    }

    /** Vibrate and play the notification sound, so you notice that playback ended while away. */
    private fun alert() {
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            val pattern = longArrayOf(0, 400, 200, 400, 200, 400)
            if (Build.VERSION.SDK_INT >= 26) vib?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
            else @Suppress("DEPRECATION") vib?.vibrate(pattern, -1)
        } catch (_: Exception) {}
        try {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            android.media.RingtoneManager.getRingtone(this, uri)?.play()
        } catch (_: Exception) {}
    }

    private fun playText(): String {
        if (paused) return L.t("Paused")
        val loopPart = if (playLoops == 0) L.t("Loop {}", playLoopNow) else L.t("Loop {}", "$playLoopNow/$playLoops")
        return if (playStepNow >= 0 && playTotal > 0) "$loopPart · ${playStepNow + 1}/$playTotal" else loopPart
    }

    private fun showDim() {
        if (dimView != null) return
        val size = screenSize()
        val params = overlayParams(
            size.x, size.y,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        val v = View(this)
        if (storage.dimScreen) {
            val a = (dimFraction(storage.selected()) * 255).toInt().coerceIn(0, 255)
            v.setBackgroundColor(a shl 24)
            params.screenBrightness = 0.01f
        } else {
            v.setBackgroundColor(0)
        }
        try {
            wm.addView(v, params)
            dimView = v
            bringPanelToFront()
        } catch (_: Exception) {}
    }

    private fun dimFraction(rec: Recording?): Float {
        val level = storage.dimLevel / 100f
        return if (rec?.hasImages == true) minOf(level, 0.6f) else level
    }

    // ---------- screenshots ----------

    /**
     * Camera button. While recording: mark "wait for this screen" at this moment.
     * Otherwise: save a screenshot to use for an image step later.
     */
    private fun snap() {
        if (snapping) return
        snapping = true
        val at = SystemClock.uptimeMillis() - recordStart
        val recording = state == State.RECORDING
        val size = screenSize()
        // Hide our own overlays so they don't end up in the template.
        panel?.visibility = View.INVISIBLE
        capture?.visibility = View.INVISIBLE
        handler.postDelayed({
            captureScreen { png ->
                handler.post {
                    panel?.visibility = View.VISIBLE
                    capture?.visibility = View.VISIBLE
                    snapping = false
                    if (png == null) {
                        toast(
                            if (Build.VERSION.SDK_INT < 30 && !useShizukuNow()) "Screenshots need Android 11 or later, or Shizuku"
                            else "Couldn't take a screenshot. Try again."
                        )
                        return@post
                    }
                    val name = storage.saveSnap(png)
                    if (recording && state == State.RECORDING) {
                        recordedImages.add(ImageStep(at, name, screenW = size.x, screenH = size.y))
                        toast("Image step added")
                    } else {
                        toast("Screenshot saved. Use it in the editor with Add image step.")
                    }
                }
            }
        }, 150)
    }

    /** PNG of the screen through Shizuku when connected, otherwise the accessibility screenshot. */
    private fun captureScreen(cb: (ByteArray?) -> Unit) {
        val backend = if (useShizukuNow()) ShizukuBridge.backend else null
        if (backend != null) {
            Thread {
                val png = try { backend.snap() } catch (e: Exception) { null }
                if (png != null || Build.VERSION.SDK_INT < 30) cb(png) else a11yPng(cb)
            }.start()
        } else if (Build.VERSION.SDK_INT >= 30) {
            a11yPng(cb)
        } else cb(null)
    }

    private fun a11yPng(cb: (ByteArray?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        A11yCapture.bitmap(this) { bmp ->
            if (bmp == null) { cb(null); return@bitmap }
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            cb(out.toByteArray())
        }
    }

    private fun hideDim() {
        dimView?.let { runCatching { wm.removeView(it) } }
        dimView = null
    }

    private fun finishPlaying(error: String? = null) {
        if (state != State.PLAYING) return
        if (!stopByUser && storage.alertDone) alert()
        hideDim()
        removeMultiGhost()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(limitStop)
        player = null
        shizukuActive = false
        paused = false
        state = State.IDLE
        updatePanel()
        onChanged?.invoke()
        val rec = playingRec
        playingRec = null
        if (error != null) {
            toast(error)
            if (rec != null && !stopByUser) logFailure(rec, error)
        }
    }

    /**
     * Save the screen right after playback stopped with an error, so you can see what was
     * on screen instead of the expected image, and fix the step from the run log.
     */
    private fun logFailure(rec: Recording, error: String) {
        val (stepNo, closest, need) = RunLogEntry.parse(error)
        val stepAt = Program(rec).steps.getOrNull(stepNo - 1)?.at ?: -1L
        val editorNo = if (stepAt >= 0) rec.steps().indexOfFirst { it.at == stepAt } + 1 else stepNo
        val entry = RunLogEntry(System.currentTimeMillis(), rec.id, rec.name, editorNo, stepAt, closest, need, error)
        // Let the dim layer go away first, and keep the panel out of the picture.
        handler.postDelayed({
            panel?.visibility = View.INVISIBLE
            handler.postDelayed({
                captureScreen { png ->
                    handler.post {
                        panel?.visibility = View.VISIBLE
                        Thread { runCatching { storage.addRunLog(entry, png) } }.start()
                        onChanged?.invoke()
                    }
                }
            }, 150)
        }, 250)
    }

    /** Dipanggil dari tombol volume dan MainActivity. */
    fun stopAll() {
        removeMultiGhost()
        when (state) {
            State.RECORDING -> stopRecording()
            State.PLAYING -> stopPlaying()
            State.IDLE -> {}
        }
    }
}
