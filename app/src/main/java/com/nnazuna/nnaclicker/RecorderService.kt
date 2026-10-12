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
    private lateinit var btnApp: ImageView
    private lateinit var btnClose: ImageView
    private lateinit var statusBox: LinearLayout
    private lateinit var statusDot: View
    private lateinit var status: TextView
    private var pulse: ObjectAnimator? = null
    /** Lapisan gelap saat memutar (tidak menangkap sentuhan) + menjaga layar tetap menyala. */
    private var dimView: View? = null
    private var playStartedAt = 0L

    // Perekaman
    private var capture: CaptureView? = null
    private var captureParams: WindowManager.LayoutParams? = null
    private var recordStart = 0L
    private val recorded = mutableListOf<RecGesture>()
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
                        status.text = loopText(loop, loops)
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

        val handle = ImageView(this).apply {
            setImageDrawable(IconDrawable(Icon.GRIP, 0x8CFFFFFF.toInt(), dp(22f)))
            scaleType = ImageView.ScaleType.CENTER
        }
        root.addView(handle, LinearLayout.LayoutParams(dp(30f), dp(44f)))

        btnRecord = iconButton { onRecordPressed() }
        btnPlay = iconButton { onPlayPressed() }

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

        btnApp = iconButton { openApp() }
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

        // Geser panel dengan memegang pegangan titik-titik
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    panel?.let { wm.updateViewLayout(it, params) }
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
        when (state) {
            State.IDLE -> {
                setIcon(btnRecord, Icon.RECORD, COLOR_REC); btnRecord.visibility = View.VISIBLE
                setIcon(btnPlay, Icon.PLAY, COLOR_PLAY); btnPlay.visibility = View.VISIBLE
                btnApp.visibility = View.VISIBLE
                btnClose.visibility = View.VISIBLE
                statusBox.visibility = View.GONE
            }
            State.RECORDING -> {
                setIcon(btnRecord, Icon.STOP, 0xFFFFFFFF.toInt()); btnRecord.visibility = View.VISIBLE
                btnPlay.visibility = View.GONE
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
                setIcon(btnPlay, Icon.STOP, 0xFFFFFFFF.toInt()); btnPlay.visibility = View.VISIBLE
                btnApp.visibility = View.GONE
                btnClose.visibility = View.GONE
                statusBox.visibility = View.VISIBLE
                (statusDot.background as GradientDrawable).setColor(COLOR_PLAY)
            }
        }
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun onRecordPressed() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecording()
            State.PLAYING -> {}
        }
    }

    private fun onPlayPressed() {
        when (state) {
            State.IDLE -> startPlaying()
            State.PLAYING -> stopPlaying()
            State.RECORDING -> {}
        }
    }

    // ---------- merekam ----------

    private fun startRecording() {
        recorded.clear()
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

        if (gestures.isEmpty()) {
            toast("Nothing recorded")
            return
        }
        val name = "Recording " + SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date())
        val rec = Recording(UUID.randomUUID().toString(), name, System.currentTimeMillis(), gestures)
        storage.save(rec)
        storage.selectedId = rec.id
        toast("Saved: $name")
        onChanged?.invoke()
    }

    // ---------- memutar ----------

    private fun startPlaying() {
        val rec = storage.selected()
        if (rec == null || rec.gestures.isEmpty()) {
            toast("No recording selected")
            return
        }
        val loops = storage.loops
        state = State.PLAYING
        updatePanel()
        status.text = "Starting…"
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
            onLoop = { n -> status.text = loopText(n, loops) },
            onFinish = { finishPlaying() },
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

    private fun loopText(n: Int, loops: Int): String = if (loops == 0) "Loop $n" else "Loop $n/$loops"

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
            val a = (storage.dimLevel * 255 / 100).coerceIn(0, 255)
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
