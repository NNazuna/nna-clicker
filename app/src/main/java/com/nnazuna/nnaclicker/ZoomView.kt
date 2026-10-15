package com.nnazuna.nnaclicker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A screenshot you can zoom (pinch, double-tap, buttons) and move with two fingers.
 * Subclasses handle one-finger editing in bitmap coordinates and draw on top.
 */
@SuppressLint("ViewConstructor")
abstract class ZoomView(ctx: Context, protected val bmp: Bitmap, private val maxHeight: Int) : View(ctx) {
    protected val density = resources.displayMetrics.density

    // view = offset + bitmap * scale
    private var fit = 1f
    private var zoom = 1f
    private var ox = 0f
    private var oy = 0f
    protected val scale get() = fit * zoom

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var gesture = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAt(zoom * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.5f) resetZoom() else zoomAt(4f, e.x, e.y)
            return true
        }
    })

    fun zoomBy(f: Float) = zoomAt(zoom * f, width / 2f, height / 2f)

    /** Zoom so [r] (bitmap coordinates) fills about half of the view. */
    fun zoomTo(r: RectF) {
        val w = max(r.width(), 40f)
        val h = max(r.height(), 40f)
        zoom = (min(width / (w * fit), height / (h * fit)) * 0.5f).coerceIn(1f, 10f)
        ox = width / 2f - r.centerX() * scale
        oy = height / 2f - r.centerY() * scale
        clamp()
        invalidate()
    }

    fun resetZoom() {
        zoom = 1f
        clamp()
        invalidate()
    }

    private fun zoomAt(newZoom: Float, fx: Float, fy: Float) {
        val bx = (fx - ox) / scale
        val by = (fy - oy) / scale
        zoom = newZoom.coerceIn(1f, 10f)
        ox = fx - bx * scale
        oy = fy - by * scale
        clamp()
        invalidate()
    }

    private fun clamp() {
        val w = bmp.width * scale
        val h = bmp.height * scale
        ox = if (w <= width) (width - w) / 2 else ox.coerceIn(width - w, 0f)
        oy = if (h <= height) (height - h) / 2 else oy.coerceIn(height - h, 0f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, min(maxHeight, (w.toLong() * bmp.height / bmp.width).toInt()))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fit = min(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        clamp()
    }

    protected fun vx(x: Float) = ox + x * scale
    protected fun vy(y: Float) = oy + y * scale
    protected fun bx(x: Float) = ((x - ox) / scale).coerceIn(0f, bmp.width.toFloat())
    protected fun by(y: Float) = ((y - oy) / scale).coerceIn(0f, bmp.height.toFloat())

    override fun onDraw(c: Canvas) {
        dst.set(ox, oy, ox + bmp.width * scale, oy + bmp.height * scale)
        c.save()
        c.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        c.drawBitmap(bmp, null, dst, paint)
        drawOverlay(c, dst)
        c.restore()
    }

    protected abstract fun drawOverlay(c: Canvas, image: RectF)

    /** One-finger touch in bitmap coordinates. [action] is DOWN, MOVE, or UP. */
    protected abstract fun onEdit(action: Int, viewX: Float, viewY: Float)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        taps.onTouchEvent(e)
        scaler.onTouchEvent(e)

        if (e.pointerCount >= 2) {
            val fx = (e.getX(0) + e.getX(1)) / 2
            val fy = (e.getY(0) + e.getY(1)) / 2
            if (gesture) {
                ox += fx - lastFocusX
                oy += fy - lastFocusY
                clamp()
                invalidate()
            } else {
                gesture = true
                onEdit(MotionEvent.ACTION_CANCEL, e.x, e.y)
            }
            lastFocusX = fx; lastFocusY = fy
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { gesture = false; onEdit(MotionEvent.ACTION_DOWN, e.x, e.y) }
            MotionEvent.ACTION_MOVE -> if (!gesture) { onEdit(MotionEvent.ACTION_MOVE, e.x, e.y); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!gesture) onEdit(MotionEvent.ACTION_UP, e.x, e.y)
                gesture = false
            }
        }
        return true
    }
}

/** Screenshot with a draggable selection rectangle (bitmap coordinates). */
@SuppressLint("ViewConstructor")
class CropView(ctx: Context, bmp: Bitmap, initial: Rect, accent: Int, maxHeight: Int) : ZoomView(ctx, bmp, maxHeight) {
    private val sel = RectF(initial)

    /** Called whenever the selection changes. */
    var onChange: ((Rect) -> Unit)? = null

    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = accent
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val touchSlop = 26 * density

    private enum class Mode { NONE, MOVE, NEW, TL, TR, BL, BR }
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f

    fun selection(): Rect = Rect(
        sel.left.toInt().coerceIn(0, bmp.width), sel.top.toInt().coerceIn(0, bmp.height),
        sel.right.toInt().coerceIn(0, bmp.width), sel.bottom.toInt().coerceIn(0, bmp.height),
    )

    fun zoomToSelection() = zoomTo(sel)

    override fun drawOverlay(c: Canvas, image: RectF) {
        val l = vx(sel.left); val t = vy(sel.top); val r = vx(sel.right); val b = vy(sel.bottom)
        c.drawRect(image.left, image.top, image.right, t, shade)
        c.drawRect(image.left, b, image.right, image.bottom, shade)
        c.drawRect(image.left, t, l, b, shade)
        c.drawRect(r, t, image.right, b, shade)
        c.drawRect(l, t, r, b, frame)
        val hr = 5.5f * density
        for ((x, y) in listOf(l to t, r to t, l to b, r to b)) c.drawCircle(x, y, hr, handle)
    }

    override fun onEdit(action: Int, viewX: Float, viewY: Float) {
        val x = bx(viewX); val y = by(viewY)
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val l = vx(sel.left); val t = vy(sel.top); val r = vx(sel.right); val b = vy(sel.bottom)
                fun near(px: Float, py: Float) = abs(viewX - px) < touchSlop && abs(viewY - py) < touchSlop
                mode = when {
                    near(l, t) -> Mode.TL
                    near(r, t) -> Mode.TR
                    near(l, b) -> Mode.BL
                    near(r, b) -> Mode.BR
                    viewX in l..r && viewY in t..b -> Mode.MOVE
                    else -> Mode.NEW
                }
                startX = x; startY = y; lastX = x; lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val minSide = 16f
                when (mode) {
                    Mode.MOVE -> sel.offset(
                        (x - lastX).coerceIn(-sel.left, bmp.width - sel.right),
                        (y - lastY).coerceIn(-sel.top, bmp.height - sel.bottom),
                    )
                    // Start a new box only after a real drag, so a tap doesn't wipe the selection.
                    Mode.NEW -> if (abs(x - startX) * scale > 8 * density || abs(y - startY) * scale > 8 * density) {
                        sel.set(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                    }
                    Mode.TL -> { sel.left = min(x, sel.right - minSide); sel.top = min(y, sel.bottom - minSide) }
                    Mode.TR -> { sel.right = max(x, sel.left + minSide); sel.top = min(y, sel.bottom - minSide) }
                    Mode.BL -> { sel.left = min(x, sel.right - minSide); sel.bottom = max(y, sel.top + minSide) }
                    Mode.BR -> { sel.right = max(x, sel.left + minSide); sel.bottom = max(y, sel.top + minSide) }
                    Mode.NONE -> {}
                }
                lastX = x; lastY = y
                onChange?.invoke(selection())
            }
            MotionEvent.ACTION_UP -> { onChange?.invoke(selection()); mode = Mode.NONE }
            else -> mode = Mode.NONE
        }
    }
}

/**
 * Screenshot with one point (tap) or two points (swipe: start and end).
 * Drag a marker to move it; tap empty space to move the nearest marker there.
 */
@SuppressLint("ViewConstructor")
class PointView(ctx: Context, bmp: Bitmap, val points: MutableList<PointF>, private val accent: Int, maxHeight: Int) :
    ZoomView(ctx, bmp, maxHeight) {

    var onChange: (() -> Unit)? = null

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = accent
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (accent and 0x00FFFFFF) or 0x55000000 }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = accent
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private var active = -1
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    fun zoomToPoints() {
        if (points.isEmpty()) return
        val r = RectF(points[0].x, points[0].y, points[0].x, points[0].y)
        points.forEach { r.union(it.x, it.y) }
        r.inset(-60f, -60f)
        zoomTo(r)
    }

    override fun drawOverlay(c: Canvas, image: RectF) {
        if (points.size == 2) {
            val ax = vx(points[0].x); val ay = vy(points[0].y)
            val bx = vx(points[1].x); val by = vy(points[1].y)
            c.drawLine(ax, ay, bx, by, line)
            // arrow head at the end point
            val len = hypot(bx - ax, by - ay)
            if (len > 1f) {
                val ux = (bx - ax) / len; val uy = (by - ay) / len
                val h = 14 * density
                c.drawLine(bx, by, bx - ux * h - uy * h * 0.6f, by - uy * h + ux * h * 0.6f, line)
                c.drawLine(bx, by, bx - ux * h + uy * h * 0.6f, by - uy * h - ux * h * 0.6f, line)
            }
        }
        val r = 16 * density
        points.forEachIndexed { i, pt ->
            val x = vx(pt.x); val y = vy(pt.y)
            c.drawCircle(x, y, r, fill)
            c.drawCircle(x, y, r, ring)
            c.drawCircle(x, y, 2.5f * density, labelBg)
            if (points.size == 2) {
                val t = if (i == 0) "START" else "END"
                val ty = y - r - 8 * density
                val w = label.measureText(t) / 2 + 6 * density
                c.drawRoundRect(x - w, ty - 13 * density, x + w, ty + 4 * density, 6 * density, 6 * density, labelBg)
                c.drawText(t, x, ty, label)
            }
        }
    }

    override fun onEdit(action: Int, viewX: Float, viewY: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                active = points.indices.minByOrNull { hypot(vx(points[it].x) - viewX, vy(points[it].y) - viewY) } ?: -1
                downX = viewX; downY = viewY; moved = false
            }
            MotionEvent.ACTION_MOVE -> if (active >= 0) {
                if (!moved && hypot(viewX - downX, viewY - downY) > 6 * density) moved = true
                if (moved) {
                    points[active].set(bx(viewX), by(viewY))
                    onChange?.invoke()
                }
            }
            MotionEvent.ACTION_UP -> {
                // A tap on empty space moves the nearest marker there.
                if (active >= 0 && !moved) {
                    points[active].set(bx(downX), by(downY))
                    invalidate()
                    onChange?.invoke()
                }
                active = -1
            }
            else -> active = -1
        }
    }
}
