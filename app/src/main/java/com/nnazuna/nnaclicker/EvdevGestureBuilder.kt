package com.nnazuna.nnaclicker

/**
 * Menyusun [RecGesture] dari event mentah touchscreen Linux (multi-touch protocol B):
 * ABS_MT_SLOT / ABS_MT_TRACKING_ID / ABS_MT_POSITION_X/Y, dikomit setiap SYN_REPORT.
 *
 * Koordinat mentah diubah ke koordinat layar sesuai rotasi saat merekam
 * (penting untuk game landscape seperti CoC dan Arknights).
 */
class EvdevGestureBuilder(
    private val minX: Int,
    private val maxX: Int,
    private val minY: Int,
    private val maxY: Int,
    /** Ukuran layar pada orientasi alami (portrait untuk HP). */
    private val natW: Int,
    private val natH: Int,
    /** Surface.ROTATION_0..3 */
    private val rotation: Int,
    private val startUs: Long,
) {
    companion object {
        const val EV_SYN = 0
        const val EV_ABS = 3
        const val SYN_REPORT = 0
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        private const val SLOTS = 16
    }

    private var slot = 0
    private val rawX = IntArray(SLOTS)
    private val rawY = IntArray(SLOTS)
    private val moved = BooleanArray(SLOTS)
    private val pendingDown = BooleanArray(SLOTS)
    private val pendingUp = BooleanArray(SLOTS)
    private val live = arrayOfNulls<MutableList<TouchPoint>>(SLOTS)

    private val doneStrokes = mutableListOf<Stroke>()
    private var gestureStartUs = -1L
    val gestures = mutableListOf<RecGesture>()

    fun onEvent(timeUs: Long, type: Int, code: Int, value: Int) {
        when (type) {
            EV_ABS -> when (code) {
                ABS_MT_SLOT -> slot = value.coerceIn(0, SLOTS - 1)
                ABS_MT_TRACKING_ID ->
                    if (value < 0) pendingUp[slot] = true else pendingDown[slot] = true
                ABS_MT_POSITION_X -> { rawX[slot] = value; moved[slot] = true }
                ABS_MT_POSITION_Y -> { rawY[slot] = value; moved[slot] = true }
            }
            EV_SYN -> if (code == SYN_REPORT) sync(timeUs)
        }
    }

    private fun point(s: Int, timeUs: Long): TouchPoint {
        val nx = (rawX[s] - minX) * natW / (maxX - minX + 1f)
        val ny = (rawY[s] - minY) * natH / (maxY - minY + 1f)
        val (x, y) = when (rotation) {
            1 -> Pair(ny, natW - nx)
            2 -> Pair(natW - nx, natH - ny)
            3 -> Pair(natH - ny, nx)
            else -> Pair(nx, ny)
        }
        return TouchPoint(x.coerceAtLeast(0f), y.coerceAtLeast(0f), (timeUs - gestureStartUs) / 1000)
    }

    private fun finishStroke(s: Int, timeUs: Long) {
        val list = live[s] ?: return
        val p = point(s, timeUs)
        if (list.last().x != p.x || list.last().y != p.y || list.last().t != p.t) list.add(p)
        doneStrokes.add(Stroke(list))
        live[s] = null
    }

    private fun sync(timeUs: Long) {
        for (s in 0 until SLOTS) {
            if (pendingDown[s]) {
                // Driver yang mengganti tracking id tanpa -1: tutup jejak lama dulu.
                if (live[s] != null) finishStroke(s, timeUs)
                if (gestureStartUs < 0) gestureStartUs = timeUs
                live[s] = mutableListOf(point(s, timeUs))
                pendingDown[s] = false
            } else if (moved[s]) {
                live[s]?.add(point(s, timeUs))
            }
            moved[s] = false
            if (pendingUp[s]) {
                finishStroke(s, timeUs)
                pendingUp[s] = false
            }
        }
        if (gestureStartUs >= 0 && live.all { it == null }) {
            if (doneStrokes.isNotEmpty()) {
                gestures.add(RecGesture((gestureStartUs - startUs) / 1000, doneStrokes.toList()))
            }
            doneStrokes.clear()
            gestureStartUs = -1
        }
    }
}
