package com.nnazuna.nnaclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.annotation.TargetApi

/**
 * Menjalankan satu [RecGesture] lewat dispatchGesture.
 *
 * - Gerakan banyak jari / pendek: dikirim sekaligus.
 * - Gerakan satu jari yang panjang (tahan lalu geser, drag joystick, dll): dipecah jadi potongan
 *   ~SEG_MS yang disambung (continueStroke, Android 8+). Jari tetap "menempel" di layar dan
 *   kecepatan geser asli ikut terjaga, bukan dirata-rata.
 */
object GestureRunner {
    private const val SEG_MS = 100L
    private const val MAX_MS = 59_000L

    fun run(
        svc: AccessibilityService,
        g: RecGesture,
        speed: Float,
        handler: Handler,
        alive: () -> Boolean,
        onDone: (Boolean) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && g.strokes.size == 1 && g.duration > 250) {
            runSegmented(svc, g.strokes[0].points, speed, handler, alive, onDone)
            return
        }
        val desc = try {
            buildWhole(g, speed)
        } catch (e: IllegalArgumentException) {
            onDone(false); return
        }
        val ok = svc.dispatchGesture(desc, callback(onDone), handler)
        if (!ok) onDone(false)
    }

    private fun callback(onDone: (Boolean) -> Unit) = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
        override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
    }

    private fun scaled(t: Long, speed: Float): Long = (t / speed).toLong()

    private fun buildWhole(g: RecGesture, speed: Float): GestureDescription {
        val b = GestureDescription.Builder()
        val maxStrokes = GestureDescription.getMaxStrokeCount()
        for (s in g.strokes.take(maxStrokes)) {
            val pts = s.points.filter { it.t <= MAX_MS }.ifEmpty { s.points.take(1) }
            val p0 = pts.first()
            val path = Path().apply {
                moveTo(p0.x, p0.y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            }
            val start = scaled(p0.t, speed)
            val dur = scaled(pts.last().t - p0.t, speed).coerceAtLeast(1L)
            b.addStroke(GestureDescription.StrokeDescription(path, start, dur))
        }
        return b.build()
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun runSegmented(
        svc: AccessibilityService,
        pts: List<TouchPoint>,
        speed: Float,
        handler: Handler,
        alive: () -> Boolean,
        onDone: (Boolean) -> Unit,
    ) {
        // Potong titik menjadi segmen-segmen; titik batas dipakai bersama agar sambungan mulus.
        val segs = mutableListOf<IntRange>()
        var s = 0
        for (i in 1 until pts.size) {
            if (pts[i].t - pts[s].t >= SEG_MS || i == pts.size - 1) {
                segs.add(s..i)
                s = i
            }
        }
        if (segs.isEmpty()) segs.add(0..0)

        var prev: GestureDescription.StrokeDescription? = null

        fun dispatch(sd: GestureDescription.StrokeDescription, next: (Boolean) -> Unit) {
            val ok = try {
                svc.dispatchGesture(
                    GestureDescription.Builder().addStroke(sd).build(),
                    callback(next),
                    handler,
                )
            } catch (e: IllegalArgumentException) {
                false
            }
            if (!ok) next(false)
        }

        fun step(k: Int) {
            val r = segs[k]
            val last = k == segs.size - 1
            val p0 = pts[r.first]

            // Dihentikan di tengah jalan: angkat jari di posisi terakhir lalu selesai.
            if (!alive()) {
                val p = prev
                if (p == null) { onDone(false); return }
                val lift = Path().apply { moveTo(p0.x, p0.y) }
                dispatch(p.continueStroke(lift, 0, 1, false)) { onDone(false) }
                return
            }

            val path = Path().apply {
                moveTo(p0.x, p0.y)
                for (i in r.first + 1..r.last) lineTo(pts[i].x, pts[i].y)
            }
            val dur = scaled(pts[r.last].t - p0.t, speed).coerceAtLeast(1L)
            val sd = prev?.continueStroke(path, 0, dur, !last)
                ?: GestureDescription.StrokeDescription(path, 0, dur, !last)
            prev = sd
            dispatch(sd) { ok ->
                when {
                    !ok -> onDone(false)
                    last -> onDone(true)
                    else -> step(k + 1)
                }
            }
        }
        step(0)
    }
}
