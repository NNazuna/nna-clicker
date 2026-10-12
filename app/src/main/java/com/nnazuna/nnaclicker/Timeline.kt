package com.nnazuna.nnaclicker

import android.view.MotionEvent

/** Satu MotionEvent yang akan disuntikkan: [t] ms sejak awal gerakan, pointer aktif urut id. */
class Frame(
    val t: Long,
    val action: Int,
    val ids: IntArray,
    val xs: FloatArray,
    val ys: FloatArray,
)

/** Mengubah [RecGesture] menjadi urutan MotionEvent (DOWN / POINTER_DOWN / MOVE / POINTER_UP / UP). */
object Timeline {
    /** Tap yang terlalu singkat sering tidak terbaca game, jadi minimal ditahan segini. */
    private const val MIN_PRESS_MS = 20L

    private class Ev(val t: Long, val kind: Int, val s: Int, val x: Float, val y: Float)

    fun frames(g: RecGesture): List<Frame> {
        val evs = ArrayList<Ev>()
        g.strokes.forEachIndexed { i, s ->
            val p = s.points
            val upT = maxOf(p.last().t, p.first().t + MIN_PRESS_MS)
            evs.add(Ev(p.first().t, 0, i, p.first().x, p.first().y))
            for (k in 1 until p.size) evs.add(Ev(p[k].t, 1, i, p[k].x, p[k].y))
            evs.add(Ev(upT, 2, i, p.last().x, p.last().y))
        }
        // Urut waktu; pada waktu yang sama: turun -> gerak -> angkat.
        evs.sortWith(compareBy<Ev>({ it.t }, { it.kind }))

        val px = HashMap<Int, Float>()
        val py = HashMap<Int, Float>()
        val active = sortedSetOf<Int>()
        val out = ArrayList<Frame>()

        fun snapshot(t: Long, action: Int): Frame {
            val ids = active.toIntArray()
            return Frame(
                t, action, ids,
                FloatArray(ids.size) { px[ids[it]] ?: 0f },
                FloatArray(ids.size) { py[ids[it]] ?: 0f },
            )
        }

        var i = 0
        while (i < evs.size) {
            val t = evs[i].t
            var j = i
            while (j < evs.size && evs[j].t == t) j++
            val group = evs.subList(i, j)

            for (e in group) if (e.kind == 0) {
                px[e.s] = e.x; py[e.s] = e.y
                active.add(e.s)
                val idx = active.indexOf(e.s)
                val act = if (active.size == 1) MotionEvent.ACTION_DOWN
                else MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                out.add(snapshot(t, act))
            }

            var moved = false
            for (e in group) if (e.kind == 1 && e.s in active) {
                px[e.s] = e.x; py[e.s] = e.y
                moved = true
            }
            if (moved) out.add(snapshot(t, MotionEvent.ACTION_MOVE))

            for (e in group) if (e.kind == 2 && e.s in active) {
                val idx = active.indexOf(e.s)
                val act = if (active.size == 1) MotionEvent.ACTION_UP
                else MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                out.add(snapshot(t, act))
                active.remove(e.s)
            }
            i = j
        }
        return out
    }
}
