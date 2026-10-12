package com.nnazuna.nnaclicker

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Memutar ulang sebuah [Recording] lewat aksesibilitas: jeda antar langkah sama seperti saat direkam
 * (dibagi [speed]), diulang [loops] kali (0 = tanpa batas) dengan jeda [intervalMs] antar ulangan.
 * Langkah gambar memakai screenshot aksesibilitas (Android 11+).
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
    /** Called once when playback ends; [error] is set when it stopped because of a problem. */
    private val onFinish: (error: String?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val rnd = java.util.Random()
    private val steps: List<Step> = rec.steps().filter { it !is ImageStep || it.ready }

    private fun rand(range: Float): Float = ((rnd.nextDouble() * 2 - 1) * range).toFloat()
    private var loop = 0
    private var idx = 0
    private var error: String? = null
    var running = false
        private set

    fun start() {
        if (steps.isEmpty()) { onFinish(null); return }
        if (steps.any { it is ImageStep } && Build.VERSION.SDK_INT < 30) {
            onFinish("Image steps need Android 11 or later, or Shizuku")
            return
        }
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
        onFinish(error)
    }

    private fun step() {
        if (!running) return
        if (idx >= steps.size) {
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
        when (val st = steps[idx]) {
            is RecGesture -> {
                val g = if (jitterPx > 0) st.shifted(rand(jitterPx), rand(jitterPx)) else st
                GestureRunner.run(svc, g, speed, handler, { running }) { next() }
            }
            is ImageStep -> waitImage(st, SystemClock.uptimeMillis() + st.timeoutMs)
        }
    }

    /** Poll the screen until the image step is satisfied or times out. */
    private fun waitImage(st: ImageStep, deadline: Long) {
        if (!running) return
        A11yCapture.frame(svc) { frame ->
            val m = frame?.let { ImageMatcher.find(it, st, rec.dim) }
            handler.post {
                if (!running) return@post
                val done = when (st.action) {
                    ImageStep.ACTION_GONE -> frame != null && m == null
                    else -> m != null
                }
                when {
                    done && st.action == ImageStep.ACTION_TAP && m != null -> {
                        val j = minOf(jitterPx, st.w / 4f)
                        GestureRunner.run(svc, RecGesture.tap(m.x + rand(j), m.y + rand(j)), 1f, handler, { running }) { next() }
                    }
                    done -> next()
                    SystemClock.uptimeMillis() >= deadline -> {
                        if (st.onTimeout == ImageStep.ON_TIMEOUT_STOP) {
                            error = "Image not found at step ${idx + 1}. Playback stopped."
                            stop()
                        } else next()
                    }
                    // Accessibility screenshots are rate-limited, so poll at about 3 per second.
                    else -> handler.postDelayed({ waitImage(st, deadline) }, 340)
                }
            }
        }
    }

    private fun next() {
        if (!running) return
        val cur = steps[idx]
        idx++
        val wait = if (idx < steps.size) {
            var gap = (steps[idx].at - (cur.at + cur.duration)).toFloat()
            if (jitterPct > 0) gap *= 1 + rand(jitterPct) / 100f
            (gap / speed).toLong().coerceAtLeast(0L)
        } else 0L
        handler.postDelayed({ step() }, wait)
    }
}
