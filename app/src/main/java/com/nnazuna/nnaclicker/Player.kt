package com.nnazuna.nnaclicker

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Plays a [Recording] through the accessibility service, step by step via [Program]:
 * delays as recorded (divided by [speed]), labels and jumps, image branches, waits, and stop.
 * The whole recording repeats [loops] times (0 = forever) with [intervalMs] between rounds.
 * Image steps use the accessibility screenshot (Android 11+).
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
    /** Called when a new step starts (index into the program's steps). */
    private val onStep: (Int) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private val rnd = java.util.Random()
    val program = Program(rec)
    private val steps get() = program.steps

    private fun rand(range: Float): Float = ((rnd.nextDouble() * 2 - 1) * range).toFloat()
    private var loop = 0
    private var idx = 0
    private var instant = 0
    private var error: String? = null
    var running = false
        private set
    /** While paused, the next step waits; a gesture that already started still finishes. */
    var paused = false
    private var bestScore = -1f

    fun start() {
        if (program.isEmpty) { onFinish(null); return }
        if (steps.any { it is ImageStep } && Build.VERSION.SDK_INT < 30) {
            onFinish("Image steps need Android 11 or later, or Shizuku")
            return
        }
        running = true
        loop = 1
        idx = program.start
        program.reset()
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
        if (paused) { handler.postDelayed({ step() }, 100); return }
        if (idx !in steps.indices) {
            if (loops == 0 || loop < loops) {
                loop++
                idx = 0
                program.reset()
                onLoop(loop)
                handler.postDelayed({ step() }, intervalMs)
            } else {
                stop()
            }
            return
        }
        onStep(idx)
        when (val st = steps[idx]) {
            is RecGesture -> {
                instant = 0
                val g = if (jitterPx > 0) st.shifted(rand(jitterPx), rand(jitterPx)) else st
                GestureRunner.run(svc, g, speed, handler, { running }) { done(true) }
            }
            is ImageStep -> {
                instant = 0
                bestScore = -1f
                waitImage(st, SystemClock.uptimeMillis() + st.timeoutMs)
            }
            is FlowStep -> when (st.kind) {
                FlowStep.WAIT -> {
                    instant = 0
                    handler.postDelayed({ done(true) }, (program.waitMs(st, rnd) / speed).toLong())
                }
                FlowStep.KEY -> {
                    instant = 0
                    svc.performGlobalAction(when (st.ref) {
                        FlowStep.KEY_HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                        FlowStep.KEY_RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
                        else -> AccessibilityService.GLOBAL_ACTION_BACK
                    })
                    done(true)
                }
                FlowStep.LAUNCH -> {
                    instant = 0
                    try {
                        svc.packageManager.getLaunchIntentForPackage(st.ref)?.let {
                            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            svc.startActivity(it)
                        }
                    } catch (_: Exception) {}
                    done(true)
                }
                else -> { instant++; done(true) }
            }
        }
    }

    /** Poll the screen until the image step is satisfied or times out. */
    private fun waitImage(st: ImageStep, deadline: Long) {
        if (!running) return
        if (paused) { handler.postDelayed({ waitImage(st, deadline + 200) }, 200); return }
        A11yCapture.frame(svc) { frame ->
            val d = ImageMatcher.Diag()
            val m = frame?.let { ImageMatcher.find(it, st, rec.dim, d) }
            handler.post {
                if (!running) return@post
                bestScore = maxOf(bestScore, d.best)
                val ok = when (st.action) {
                    ImageStep.ACTION_GONE -> frame != null && m == null
                    else -> m != null
                }
                when {
                    ok && st.action == ImageStep.ACTION_TAP && m != null -> {
                        val j = minOf(jitterPx, st.w / 4f)
                        GestureRunner.run(svc, RecGesture.tap(m.x + rand(j), m.y + rand(j)), 1f, handler, { running }) { done(true) }
                    }
                    ok -> done(true)
                    SystemClock.uptimeMillis() >= deadline -> done(false)
                    // Accessibility screenshots are rate-limited, so poll at about 3 per second.
                    else -> handler.postDelayed({ waitImage(st, deadline) }, 340)
                }
            }
        }
    }

    /** The current step finished; [found] is the image result for image steps. */
    private fun done(found: Boolean) {
        if (!running) return
        val cur = steps[idx]
        val n = program.next(idx, found)
        if (n == Program.STOP) {
            if (cur is ImageStep) error = Program.notFoundText(idx, cur, bestScore)
            stop()
            return
        }
        idx = n
        val wait = when {
            idx !in steps.indices -> 0L
            program.jumped -> 0L
            else -> {
                var gap = program.gaps[idx].toFloat()
                if (jitterPct > 0) gap *= 1 + rand(jitterPct) / 100f
                (gap / speed).toLong().coerceAtLeast(0L)
            }
        }
        // A loop made only of labels and jumps would spin; give it a breather.
        val pause = if (instant > 50) { instant = 0; 100L } else 0L
        handler.postDelayed({ step() }, wait + pause)
    }
}
