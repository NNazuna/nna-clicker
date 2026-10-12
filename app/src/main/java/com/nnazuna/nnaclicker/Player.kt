package com.nnazuna.nnaclicker

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper

/**
 * Memutar ulang sebuah [Recording]: jeda antar gerakan sama seperti saat direkam
 * (dibagi [speed]), diulang [loops] kali (0 = tanpa batas) dengan jeda [intervalMs] antar ulangan.
 */
class Player(
    private val svc: AccessibilityService,
    private val rec: Recording,
    private val loops: Int,
    private val speed: Float,
    private val intervalMs: Long,
    private val startDelayMs: Long,
    private val jitterPx: Float,
    private val jitterPct: Float,
    private val onLoop: (Int) -> Unit,
    private val onFinish: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val rnd = java.util.Random()

    private fun rand(range: Float): Float = ((rnd.nextDouble() * 2 - 1) * range).toFloat()
    private var loop = 0
    private var idx = 0
    var running = false
        private set

    fun start() {
        if (rec.gestures.isEmpty()) { onFinish(); return }
        running = true
        loop = 1
        idx = 0
        onLoop(loop)
        handler.postDelayed({ step() }, startDelayMs)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        onFinish()
    }

    private fun step() {
        if (!running) return
        if (idx >= rec.gestures.size) {
            if (loops == 0 || loop < loops) {
                loop++
                idx = 0
                onLoop(loop)
                handler.postDelayed({ step() }, intervalMs)
            } else {
                stop()
            }
            return
        }
        val g = rec.gestures[idx].let { if (jitterPx > 0) it.shifted(rand(jitterPx), rand(jitterPx)) else it }
        GestureRunner.run(svc, g, speed, handler, { running }) { next() }
    }

    private fun next() {
        if (!running) return
        val cur = rec.gestures[idx]
        idx++
        val wait = if (idx < rec.gestures.size) {
            var gap = (rec.gestures[idx].at - (cur.at + cur.duration)).toFloat()
            if (jitterPct > 0) gap *= 1 + rand(jitterPct) / 100f
            (gap / speed).toLong().coerceAtLeast(0L)
        } else 0L
        handler.postDelayed({ step() }, wait)
    }
}
