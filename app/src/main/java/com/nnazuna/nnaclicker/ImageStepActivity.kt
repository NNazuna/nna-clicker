package com.nnazuna.nnaclicker

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Choose the area of a screenshot to look for, and what to do when it's found. */
class ImageStepActivity : Activity() {

    companion object {
        const val EXTRA_REC = "rec"
        const val EXTRA_INDEX = "index"
    }

    private lateinit var storage: Storage
    private lateinit var k: Kit
    private val p get() = k.p
    private lateinit var rec: Recording
    private lateinit var step: ImageStep
    private var shot: Bitmap? = null
    private var crop: CropView? = null

    private lateinit var actionBox: LinearLayout
    private lateinit var searchBox: LinearLayout
    private lateinit var failBox: LinearLayout
    private lateinit var timeoutValue: TextView
    private lateinit var matchValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        k = Kit(this)
        Ui.styleWindow(this, p)
        rec = storage.get(intent.getStringExtra(EXTRA_REC)) ?: run { finish(); return }
        step = rec.images.getOrNull(intent.getIntExtra(EXTRA_INDEX, -1)) ?: run { finish(); return }
        shot = storage.snapFile(step.snap)?.let { BitmapFactory.decodeFile(it.absolutePath) }
        build()
    }

    override fun onDestroy() {
        super.onDestroy()
        shot?.recycle()
    }

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(12f), k.dp(16f), k.dp(12f), k.dp(40f))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(k.icon(Icon.BACK, p.ink, 26f).apply {
            minimumWidth = k.dp(44f); minimumHeight = k.dp(44f)
            background = k.ripple(null, k.dp(22f).toFloat())
            setOnClickListener { finish() }
        })
        top.addView(k.text("Image step", 22f, 2).apply { setPadding(k.dp(6f), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(k.textButton("Save", p.play) { save() })
        root.addView(top)

        val bmp = shot
        if (bmp != null) {
            root.addView(k.text("Drag a box around what to look for. Pinch or double-tap to zoom, move with two fingers.", 14f, 0, p.muted).apply {
                setPadding(k.dp(8f), k.dp(4f), k.dp(8f), k.dp(8f))
            })
            val initial = if (step.w > 0 && step.h > 0) Rect(step.x, step.y, step.x + step.w, step.y + step.h)
            else Rect(bmp.width * 3 / 8, bmp.height * 3 / 8, bmp.width * 5 / 8, bmp.height / 2)
            val maxH = resources.displayMetrics.heightPixels * 55 / 100
            val cv = CropView(this, bmp, initial, p.play, maxH)
            crop = cv
            root.addView(cv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = k.dp(6f)
            })

            // Zoom controls + live preview of the selection
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(k.dp(4f), 0, k.dp(4f), 0)
            }
            fun zbtn(label: String, onClick: () -> Unit) = k.text(label, 18f, 2, p.ink).apply {
                gravity = Gravity.CENTER
                minWidth = k.dp(48f); minHeight = k.dp(44f)
                background = k.ripple(k.rounded(p.surface, 12f), k.dp(12f).toFloat())
                setOnClickListener { onClick() }
            }
            bar.addView(zbtn("−") { cv.zoomBy(1 / 1.6f) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = k.dp(6f) })
            bar.addView(zbtn("+") { cv.zoomBy(1.6f) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = k.dp(6f) })
            bar.addView(k.textButton("Zoom to box", p.play) { cv.zoomToSelection() })
            bar.addView(k.textButton("Fit", p.play) { cv.resetZoom() })
            bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            val preview = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = k.rounded(p.surface, 10f, p.line)
                setPadding(k.dp(4f), k.dp(4f), k.dp(4f), k.dp(4f))
            }
            bar.addView(preview, LinearLayout.LayoutParams(k.dp(84f), k.dp(56f)))
            root.addView(bar)

            fun showPreview(r: Rect) {
                if (r.width() < 2 || r.height() < 2) return
                preview.setImageBitmap(Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height()))
            }
            cv.onChange = { showPreview(it) }
            showPreview(cv.selection())
        } else {
            root.addView(k.text(
                if (step.ready) "The original screenshot is gone. Pick a new one to change the area."
                else "Pick a screenshot to choose the area.",
                14.5f, 0, p.muted,
            ).apply { setPadding(k.dp(8f), k.dp(8f), k.dp(8f), k.dp(8f)) })
            if (step.ready) {
                root.addView(ImageView(this).apply {
                    setImageBitmap(templateBitmap(step))
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_START
                    setPadding(k.dp(8f), 0, 0, k.dp(8f))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, k.dp(96f)))
            }
        }
        root.addView(k.textButton("Use another screenshot", p.play) { pickScreenshot() }.apply {
            setPadding(k.dp(8f), k.dp(10f), k.dp(8f), k.dp(10f))
        })

        // ---- Options ----
        root.addView(k.sectionHeader("When playing"))
        val g = k.group()
        actionBox = segRow(g, "Action")
        g.addView(k.divider())
        searchBox = segRow(g, "Search")
        g.addView(k.divider())
        k.valueRow("Give up after") {
            Ui.numberDialog(this, p, "Give up after (seconds)", (step.timeoutMs / 1000).toString(), false) { v ->
                v.toLongOrNull()?.let { step.timeoutMs = (it.coerceIn(1, 3600)) * 1000; refresh() }
            }
        }.let { (row, v) -> timeoutValue = v; g.addView(row) }
        g.addView(k.divider())
        failBox = segRow(g, "If not found")
        g.addView(k.divider())
        k.valueRow("Match", "Lower finds more, but may find the wrong thing") {
            Ui.numberDialog(this, p, "Match (%)", (step.threshold * 100).toInt().toString(), false, "50–99, default 82") { v ->
                v.toIntOrNull()?.let { step.threshold = it.coerceIn(50, 99) / 100f; refresh() }
            }
        }.let { (row, v) -> matchValue = v; g.addView(row) }
        root.addView(g)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        })
        refresh()
    }

    /** A labeled row of segmented choices; returns the container for the choices. */
    private fun segRow(group: LinearLayout, label: String): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(16f), k.dp(12f), k.dp(16f), k.dp(12f))
        }
        col.addView(k.text(label, 16f, 1))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = k.rounded(p.bg, 10f)
            setPadding(k.dp(3f), k.dp(3f), k.dp(3f), k.dp(3f))
        }
        col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = k.dp(10f)
        })
        group.addView(col)
        return box
    }

    private fun fill(box: LinearLayout, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        box.removeAllViews()
        options.forEachIndexed { i, label ->
            val on = i == selected
            box.addView(k.text(label, 14.5f, if (on) 2 else 1, if (on) (if (p.dark) 0xFF0F1312.toInt() else Color.WHITE) else p.ink).apply {
                gravity = Gravity.CENTER
                setPadding(k.dp(4f), k.dp(9f), k.dp(4f), k.dp(9f))
                background = if (on) k.rounded(p.play, 8f) else k.ripple(null, k.dp(8f).toFloat())
                setOnClickListener { onPick(i); refresh() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun refresh() {
        fill(actionBox, listOf("Wait for it", "Tap it", "Wait until gone"), step.action) { step.action = it }
        fill(searchBox, listOf("Near this spot", "Anywhere"), if (step.anywhere) 1 else 0) { step.anywhere = it == 1 }
        fill(failBox, listOf("Stop playback", "Skip this step"), step.onTimeout) { step.onTimeout = it }
        timeoutValue.text = "${step.timeoutMs / 1000} s"
        matchValue.text = "${(step.threshold * 100).toInt()}%"
    }

    private fun pickScreenshot() {
        SnapPicker.show(this, k, storage, "Use screenshot") { file ->
            saveOptionsOnly()
            step.snap = file.name
            step.w = 0; step.h = 0
            storage.save(rec)
            recreate()
        }
    }

    /** Persist the option choices without touching the area. */
    private fun saveOptionsOnly() {
        storage.save(rec)
    }

    private fun save() {
        val bmp = shot
        val cv = crop
        if (bmp != null && cv != null) {
            val r = cv.selection()
            if (r.width() < 16 || r.height() < 16) {
                Toast.makeText(this, "Select a bigger area", Toast.LENGTH_SHORT).show()
                return
            }
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val t = ImageMatcher.cut(px, bmp.width, bmp.height, r.left, r.top, r.width(), r.height())
            step.x = r.left; step.y = r.top; step.w = r.width(); step.h = r.height()
            step.screenW = bmp.width; step.screenH = bmp.height
            step.tw = t.w; step.th = t.h; step.tpl = t.rgb
        } else if (!step.ready) {
            Toast.makeText(this, "Pick a screenshot first", Toast.LENGTH_SHORT).show()
            return
        }
        storage.save(rec)
        Toast.makeText(this, "Image step saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** Template preview bitmap (half resolution). */
fun templateBitmap(s: ImageStep): Bitmap? {
    if (!s.ready) return null
    val px = IntArray(s.tpl.size) { s.tpl[it] or (0xFF shl 24) }
    return Bitmap.createBitmap(px, s.tw, s.th, Bitmap.Config.ARGB_8888)
}

/**
 * Screenshot with a draggable selection rectangle (bitmap coordinates).
 * One finger edits the selection; two fingers pinch to zoom and move the picture;
 * double-tap zooms in on that spot (or back out).
 */
@SuppressLint("ViewConstructor")
class CropView(ctx: Context, private val bmp: Bitmap, initial: Rect, private val accent: Int, private val maxHeight: Int) : View(ctx) {
    private val sel = RectF(initial)
    private val density = resources.displayMetrics.density

    // view = offset + bitmap * scale
    private var fit = 1f
    private var zoom = 1f
    private var ox = 0f
    private var oy = 0f
    private val scale get() = fit * zoom

    /** Called whenever the selection changes. */
    var onChange: ((Rect) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = accent
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val touchSlop = 26 * density

    private enum class Mode { NONE, MOVE, NEW, TL, TR, BL, BR, GESTURE }
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var startBX = 0f
    private var startBY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val scaler = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            zoomAt(zoom * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val taps = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.5f) resetZoom() else zoomAt(4f, e.x, e.y)
            return true
        }
    })

    fun selection(): Rect = Rect(
        sel.left.toInt().coerceIn(0, bmp.width), sel.top.toInt().coerceIn(0, bmp.height),
        sel.right.toInt().coerceIn(0, bmp.width), sel.bottom.toInt().coerceIn(0, bmp.height),
    )

    fun zoomBy(f: Float) = zoomAt(zoom * f, width / 2f, height / 2f)

    /** Zoom so the selection fills most of the view. */
    fun zoomToSelection() {
        val z = (min(width / (sel.width() * fit), height / (sel.height() * fit)) * 0.5f).coerceIn(1f, 10f)
        zoom = z
        ox = width / 2f - sel.centerX() * scale
        oy = height / 2f - sel.centerY() * scale
        clamp()
        invalidate()
    }

    fun resetZoom() {
        zoom = 1f
        center()
        invalidate()
    }

    private fun center() {
        ox = (width - bmp.width * scale) / 2
        oy = (height - bmp.height * scale) / 2
    }

    private fun zoomAt(newZoom: Float, fx: Float, fy: Float) {
        val z = newZoom.coerceIn(1f, 10f)
        // keep the bitmap point under the focus fixed
        val bx = (fx - ox) / scale
        val by = (fy - oy) / scale
        zoom = z
        ox = fx - bx * scale
        oy = fy - by * scale
        clamp()
        invalidate()
    }

    /** Keep the picture covering the view (or centered when smaller). */
    private fun clamp() {
        val w = bmp.width * scale
        val h = bmp.height * scale
        ox = if (w <= width) (width - w) / 2 else ox.coerceIn(width - w, 0f)
        oy = if (h <= height) (height - h) / 2 else oy.coerceIn(height - h, 0f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = min(maxHeight, (w.toLong() * bmp.height / bmp.width).toInt())
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fit = min(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        center()
        clamp()
    }

    private fun vx(x: Float) = ox + x * scale
    private fun vy(y: Float) = oy + y * scale
    private fun bx(x: Float) = ((x - ox) / scale).coerceIn(0f, bmp.width.toFloat())
    private fun by(y: Float) = ((y - oy) / scale).coerceIn(0f, bmp.height.toFloat())

    private val dst = RectF()

    override fun onDraw(c: Canvas) {
        dst.set(ox, oy, ox + bmp.width * scale, oy + bmp.height * scale)
        c.save()
        c.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        c.drawBitmap(bmp, null, dst, paint)
        val l = vx(sel.left); val t = vy(sel.top); val r = vx(sel.right); val b = vy(sel.bottom)
        c.drawRect(dst.left, dst.top, dst.right, t, shade)
        c.drawRect(dst.left, b, dst.right, dst.bottom, shade)
        c.drawRect(dst.left, t, l, b, shade)
        c.drawRect(r, t, dst.right, b, shade)
        c.drawRect(l, t, r, b, frame)
        val hr = 5.5f * density
        for ((x, y) in listOf(l to t, r to t, l to b, r to b)) c.drawCircle(x, y, hr, handle)
        c.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        taps.onTouchEvent(e)
        scaler.onTouchEvent(e)

        // Two fingers: zoom and move the picture, never the selection.
        if (e.pointerCount >= 2) {
            val fx = (e.getX(0) + e.getX(1)) / 2
            val fy = (e.getY(0) + e.getY(1)) / 2
            if (mode != Mode.GESTURE) {
                mode = Mode.GESTURE
            } else {
                ox += fx - lastFocusX
                oy += fy - lastFocusY
                clamp()
                invalidate()
            }
            lastFocusX = fx; lastFocusY = fy
            return true
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val l = vx(sel.left); val t = vy(sel.top); val r = vx(sel.right); val b = vy(sel.bottom)
                fun near(x: Float, y: Float) = abs(e.x - x) < touchSlop && abs(e.y - y) < touchSlop
                mode = when {
                    near(l, t) -> Mode.TL
                    near(r, t) -> Mode.TR
                    near(l, b) -> Mode.BL
                    near(r, b) -> Mode.BR
                    e.x in l..r && e.y in t..b -> Mode.MOVE
                    else -> Mode.NEW
                }
                val x = bx(e.x); val y = by(e.y)
                startBX = x; startBY = y
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.GESTURE || mode == Mode.NONE) return true
                val x = bx(e.x); val y = by(e.y)
                val minSide = 16f
                when (mode) {
                    Mode.MOVE -> {
                        val dx = (x - lastX).coerceIn(-sel.left, bmp.width - sel.right)
                        val dy = (y - lastY).coerceIn(-sel.top, bmp.height - sel.bottom)
                        sel.offset(dx, dy)
                    }
                    Mode.NEW -> {
                        // Start a new box only after a real drag, so a tap doesn't wipe the selection.
                        if (abs(x - startBX) * scale > 8 * density || abs(y - startBY) * scale > 8 * density) {
                            sel.set(min(startBX, x), min(startBY, y), max(startBX, x), max(startBY, y))
                        }
                    }
                    Mode.TL -> { sel.left = min(x, sel.right - minSide); sel.top = min(y, sel.bottom - minSide) }
                    Mode.TR -> { sel.right = max(x, sel.left + minSide); sel.top = min(y, sel.bottom - minSide) }
                    Mode.BL -> { sel.left = min(x, sel.right - minSide); sel.bottom = max(y, sel.top + minSide) }
                    Mode.BR -> { sel.right = max(x, sel.left + minSide); sel.bottom = max(y, sel.top + minSide) }
                    else -> {}
                }
                lastX = x; lastY = y
                invalidate()
                onChange?.invoke(selection())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (mode != Mode.GESTURE) onChange?.invoke(selection())
                mode = Mode.NONE
            }
        }
        return true
    }
}
