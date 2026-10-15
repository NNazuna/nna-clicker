package com.nnazuna.nnaclicker

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
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
    /** Lapisan gelap saat memutar (tidak menangkap sentuhan) + menjaga layar tetap menyala. */
    private var dimView: View? = null
    private var playStartedAt = 0L
    /** Playback position shown on the panel. */
    private var playLoopNow = 1
    private var playStepNow = -1
    private var playTotal = 0

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
                        if (err.isNotEmpty()) toast(err)
                        finishPlaying()
                    } else {
                        val loops = storage.loops
                        val stepNow = try { ShizukuBridge.backend?.playStep() ?: -1 } catch (e: Exception) { -1 }
                        playLoopNow = loop
                        playStepNow = stepNow
                        status.text = playText(loops)
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
        ShizukuBridge.init(this)
        if (storage.useShizuku) ShizukuBridge.connect()
        showPanel()
        onChanged?.invoke()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Volume Bawah = berhenti merekam / memutar, tanpa perlu menyentuh layar. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun useShizukuNow(): Boolean = storage.useShizuku && ShizukuBridge.isConnected

    // ---------- panel mengambang ----------

    val isPanelShown: Boolean get() = panel != null

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
            for (v in listOf(btnRecord, btnPlay, btnSnap, btnApp, btnClose, statusBox)) v.visibility = View.GONE
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
                btnSnap.visibility = View.VISIBLE
                btnApp.visibility = View.VISIBLE
                btnClose.visibility = View.VISIBLE
                statusBox.visibility = View.GONE
            }
            State.RECORDING -> {
                setIcon(btnRecord, Icon.STOP, 0xFFFFFFFF.toInt()); btnRecord.visibility = View.VISIBLE
                btnPlay.visibility = View.GONE
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
                btnApp.visibility = View.GONE
                btnClose.visibility = View.GONE
                statusBox.visibility = View.VISIBLE
                (statusDot.background as GradientDrawable).setColor(COLOR_PLAY)
            }
        }
    }

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
                setColor(0xF21C2220.toInt())
                setStroke(dp(1f), 0x33FFFFFF)
            }
        }
        fun row(label: String, color: Int, bold: Boolean, onClick: (() -> Unit)?) = TextView(this).apply {
            text = label
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
        val name = "Recording " + SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date())
        val rec = Recording(UUID.randomUUID().toString(), name, System.currentTimeMillis(), gestures, images)
        storage.save(rec)
        storage.selectedId = rec.id
        toast(if (images.isEmpty()) "Saved: $name" else "Saved: $name. Open the editor to choose the image areas.")
        onChanged?.invoke()
    }

    // ---------- memutar ----------

    private fun startPlaying() {
        val rec = storage.selected()
        if (rec == null || Program(rec).isEmpty) {
            toast("No recording selected")
            return
        }
        if (rec.images.any { !it.ready }) toast("Some image steps have no area yet and will be skipped")
        // Image matching corrects for the dim layer; keep it light enough to still see details.
        rec.dim = if (storage.dimScreen) dimFraction(rec) else 0f
        val loops = storage.loops
        state = State.PLAYING
        updatePanel()
        status.text = "Starting…"
        playTotal = Program(rec).steps.size
        playLoopNow = 1
        playStepNow = -1
        showDim()
        playStartedAt = SystemClock.uptimeMillis()
        val maxMin = storage.maxMinutes
        if (maxMin > 0) handler.postDelayed(limitStop, maxMin * 60_000L)

        if (useShizukuNow()) {
            val svc = ShizukuBridge.backend!!
            val ok = try {
                svc.play(
                    rec, loops, storage.speed, storage.intervalMs, storage.startDelayMs,
                    storage.jitterPx, storage.jitterPct,
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
            speed = storage.speed,
            intervalMs = storage.intervalMs,
            startDelayMs = storage.startDelayMs,
            jitterPx = storage.jitterPx,
            jitterPct = storage.jitterPct,
            onLoop = { n -> playLoopNow = n; status.text = playText(loops) },
            onStep = { i -> playStepNow = i; status.text = playText(loops) },
            onFinish = { err ->
                if (err != null) toast(err)
                finishPlaying()
            },
        ).also { it.start() }
    }

    private fun stopPlaying() {
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
            stopPlaying()
            toast("Auto stopped after ${storage.maxMinutes} min")
        }
    }

    private fun playText(loops: Int): String {
        val loopPart = if (loops == 0) "Loop $playLoopNow" else "Loop $playLoopNow/$loops"
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

    private fun finishPlaying() {
        if (state != State.PLAYING) return
        hideDim()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(limitStop)
        player = null
        shizukuActive = false
        state = State.IDLE
        updatePanel()
        onChanged?.invoke()
    }

    /** Dipanggil dari tombol volume dan MainActivity. */
    fun stopAll() {
        when (state) {
            State.RECORDING -> stopRecording()
            State.PLAYING -> stopPlaying()
            State.IDLE -> {}
        }
    }
}
