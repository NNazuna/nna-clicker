package com.nnazuna.nnaclicker

/**
 * Builds the looping recordings behind quick auto tap and multi-target auto tap.
 * Pure functions with no Android dependencies, so they are covered by unit tests.
 */
object AutoTap {
    /** Fixed ids: each run overwrites the same recording instead of adding a new one. */
    const val QUICK_TAP_ID = "auto-tap-quick"
    const val MULTI_TAP_ID = "auto-tap-multi"

    const val MIN_EVERY_MS = 100L
    const val MAX_EVERY_MS = 3_600_000L

    /** Label → tap at (x, y) → wait [everyMs] → go to label. */
    fun quickTap(x: Float, y: Float, everyMs: Long, now: Long, holdMs: Long = 60L): Recording {
        val every = everyMs.coerceIn(MIN_EVERY_MS, MAX_EVERY_MS)
        return loop(QUICK_TAP_ID, listOf(MultiPoint(x, y)), every, holdMs, now, waitAfterHold = true).apply {
            name = "Auto tap · every ${FlowUi.sec(every)}"
        }
    }

    /**
     * Label → tap each point in order → go to label. Each next tap starts [everyMs]
     * plus the previous point's own delay after the previous tap started.
     */
    fun multiTap(points: List<MultiPoint>, everyMs: Long, holdMs: Long, now: Long, name: String? = null): Recording {
        val every = everyMs.coerceIn(MIN_EVERY_MS, MAX_EVERY_MS)
        return loop(MULTI_TAP_ID, points, every, holdMs, now).apply {
            this.name = name ?: "Multi tap · ${points.size} targets · every ${FlowUi.sec(every)}"
        }
    }

    /** [waitAfterHold]: quick tap counts its wait from the end of the touch, multi-target from its start. */
    private fun loop(
        id: String, points: List<MultiPoint>, every: Long, hold: Long, now: Long, waitAfterHold: Boolean = false,
    ): Recording {
        val rec = Recording(id, "", now, mutableListOf())
        val label = FlowStep(0, FlowStep.LABEL, ref = FlowStep.newId(), name = "Repeat")
        rec.flows.add(label)
        var at = 1L
        for (p in points) {
            rec.gestures.add(RecGesture(at, listOf(
                Stroke(listOf(TouchPoint(p.x, p.y, 0), TouchPoint(p.x, p.y, hold))),
            )))
            at += (if (waitAfterHold) hold else 0L) + every + p.delayMs
        }
        rec.flows.add(FlowStep(at, FlowStep.GOTO, ref = label.ref))
        return rec
    }

    /** "Setup N" with N one past the highest number in use, so names never repeat after a delete. */
    fun nextSetupName(existing: List<String>): String {
        val pattern = Regex("""^Setup (\d+)$""")
        val used = existing.mapNotNull { pattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return "Setup ${(used.maxOrNull() ?: 0) + 1}"
    }
}
