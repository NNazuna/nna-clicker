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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
            root.addView(k.text("Drag to select what to look for", 14f, 0, p.muted).apply {
                setPadding(k.dp(8f), k.dp(4f), 0, k.dp(8f))
            })
            val initial = if (step.w > 0 && step.h > 0) Rect(step.x, step.y, step.x + step.w, step.y + step.h)
            else Rect(bmp.width * 3 / 8, bmp.height * 3 / 8, bmp.width * 5 / 8, bmp.height / 2)
            val cv = CropView(this, bmp, initial, p.play)
            crop = cv
            val maxH = resources.displayMetrics.heightPixels * 55 / 100
            root.addView(cv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH).apply {
                bottomMargin = k.dp(4f)
            })
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
        val snaps = storage.snaps()
        if (snaps.isEmpty()) {
            AlertDialog.Builder(this, Ui.dialogTheme(p))
                .setMessage("No screenshots yet. Press the camera button on the floating panel while the game is open.")
                .setPositiveButton("OK", null).show()
            return
        }
        val fmt = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
        val labels = snaps.map { "Screenshot · " + fmt.format(Date(it.lastModified())) }.toTypedArray()
        AlertDialog.Builder(this, Ui.dialogTheme(p))
            .setTitle("Screenshot")
            .setItems(labels) { _, which ->
                saveOptionsOnly()
                step.snap = snaps[which].name
                step.w = 0; step.h = 0
                storage.save(rec)
                recreate()
            }
            .show()
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

/** Screenshot with a draggable selection rectangle (bitmap coordinates). */
@SuppressLint("ViewConstructor")
class CropView(ctx: Context, private val bmp: Bitmap, initial: Rect, private val accent: Int) : View(ctx) {
    private val sel = RectF(initial)
    private val dst = RectF()
    private var scale = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        color = accent
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val touchSlop = 28 * resources.displayMetrics.density

    private enum class Mode { NONE, MOVE, NEW, TL, TR, BL, BR }
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var startBX = 0f
    private var startBY = 0f

    fun selection(): Rect = Rect(
        sel.left.toInt().coerceIn(0, bmp.width), sel.top.toInt().coerceIn(0, bmp.height),
        sel.right.toInt().coerceIn(0, bmp.width), sel.bottom.toInt().coerceIn(0, bmp.height),
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = min(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
    }

    private fun toView(x: Float, y: Float) = Pair(dst.left + x * scale, dst.top + y * scale)
    private fun toBmp(x: Float, y: Float) = Pair(
        ((x - dst.left) / scale).coerceIn(0f, bmp.width.toFloat()),
        ((y - dst.top) / scale).coerceIn(0f, bmp.height.toFloat()),
    )

    override fun onDraw(c: Canvas) {
        c.drawBitmap(bmp, null, dst, paint)
        val (l, t) = toView(sel.left, sel.top)
        val (r, b) = toView(sel.right, sel.bottom)
        c.drawRect(dst.left, dst.top, dst.right, t, shade)
        c.drawRect(dst.left, b, dst.right, dst.bottom, shade)
        c.drawRect(dst.left, t, l, b, shade)
        c.drawRect(r, t, dst.right, b, shade)
        c.drawRect(l, t, r, b, frame)
        val hr = 6 * resources.displayMetrics.density
        for ((x, y) in listOf(l to t, r to t, l to b, r to b)) c.drawCircle(x, y, hr, handle)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val (l, t) = toView(sel.left, sel.top)
                val (r, b) = toView(sel.right, sel.bottom)
                fun near(x: Float, y: Float) = abs(e.x - x) < touchSlop && abs(e.y - y) < touchSlop
                mode = when {
                    near(l, t) -> Mode.TL
                    near(r, t) -> Mode.TR
                    near(l, b) -> Mode.BL
                    near(r, b) -> Mode.BR
                    e.x in l..r && e.y in t..b -> Mode.MOVE
                    else -> Mode.NEW
                }
                val (bx, by) = toBmp(e.x, e.y)
                startBX = bx; startBY = by
                lastX = bx; lastY = by
                if (mode == Mode.NEW) sel.set(bx, by, bx, by)
            }
            MotionEvent.ACTION_MOVE -> {
                val (bx, by) = toBmp(e.x, e.y)
                when (mode) {
                    Mode.MOVE -> {
                        val dx = (bx - lastX).coerceIn(-sel.left, bmp.width - sel.right)
                        val dy = (by - lastY).coerceIn(-sel.top, bmp.height - sel.bottom)
                        sel.offset(dx, dy)
                    }
                    Mode.NEW -> sel.set(min(startBX, bx), min(startBY, by), max(startBX, bx), max(startBY, by))
                    Mode.TL -> { sel.left = min(bx, sel.right - 8); sel.top = min(by, sel.bottom - 8) }
                    Mode.TR -> { sel.right = max(bx, sel.left + 8); sel.top = min(by, sel.bottom - 8) }
                    Mode.BL -> { sel.left = min(bx, sel.right - 8); sel.bottom = max(by, sel.top + 8) }
                    Mode.BR -> { sel.right = max(bx, sel.left + 8); sel.bottom = max(by, sel.top + 8) }
                    Mode.NONE -> {}
                }
                lastX = bx; lastY = by
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                mode = Mode.NONE
            }
        }
        return true
    }
}
