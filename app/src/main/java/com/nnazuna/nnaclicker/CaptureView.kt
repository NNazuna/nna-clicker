package com.nnazuna.nnaclicker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Lapisan transparan selebar layar yang menangkap sentuhan saat merekam.
 * Setiap gerakan yang selesai dikirim ke [onGesture] (koordinat layar asli),
 * lalu service meneruskannya ke aplikasi di bawahnya.
 */
@SuppressLint("ViewConstructor")
class CaptureView(
    context: Context,
    private val recordStartUptime: Long,
    private val onGesture: (RecGesture) -> Unit,
) : View(context) {

    private val active = LinkedHashMap<Int, MutableList<TouchPoint>>()
    private val finished = mutableListOf<MutableList<TouchPoint>>()
    private var downTime = 0L
    private var ox = 0f
    private var oy = 0f

    private val density = resources.displayMetrics.density
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 229, 72, 77)
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
    }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 229, 72, 77)
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 229, 72, 77)
    }

    private fun add(id: Int, x: Float, y: Float, time: Long) {
        val list = active.getOrPut(id) { mutableListOf() }
        val p = TouchPoint((x + ox).coerceAtLeast(0f), (y + oy).coerceAtLeast(0f), time - downTime)
        val last = list.lastOrNull()
        // Buang titik duplikat di waktu yang sama.
        if (last != null && last.t == p.t && last.x == p.x && last.y == p.y) return
        list.add(p)
    }

    private fun addAll(e: MotionEvent) {
        for (h in 0 until e.historySize) {
            val ht = e.getHistoricalEventTime(h)
            for (i in 0 until e.pointerCount) {
                add(e.getPointerId(i), e.getHistoricalX(i, h), e.getHistoricalY(i, h), ht)
            }
        }
        for (i in 0 until e.pointerCount) add(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Selisih koordinat view -> koordinat layar (biasanya 0).
        ox = e.rawX - e.x
        oy = e.rawY - e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active.clear()
                finished.clear()
                downTime = e.eventTime
                val i = e.actionIndex
                add(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                add(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> addAll(e)
            MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                add(id, e.getX(i), e.getY(i), e.eventTime)
                active.remove(id)?.let { finished.add(it) }
            }
            MotionEvent.ACTION_UP -> {
                val i = e.actionIndex
                add(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
                finished.addAll(active.values)
                active.clear()
                val strokes = finished.filter { it.isNotEmpty() }.map { Stroke(it.toList()) }
                finished.clear()
                if (strokes.isNotEmpty()) {
                    onGesture(RecGesture(downTime - recordStartUptime, strokes))
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                active.clear()
                finished.clear()
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = border.strokeWidth / 2
        canvas.drawRect(half, half, width - half, height - half, border)
        for (pts in active.values) {
            for (i in 1 until pts.size) {
                canvas.drawLine(pts[i - 1].x - ox, pts[i - 1].y - oy, pts[i].x - ox, pts[i].y - oy, trail)
            }
            pts.lastOrNull()?.let { canvas.drawCircle(it.x - ox, it.y - oy, 18 * density, dot) }
        }
    }
}
