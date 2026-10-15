package com.nnazuna.nnaclicker

/**
 * A recording turned into something runnable: the ordered steps, the delay before each
 * one, and the rules for where to go next (labels, jumps, image branches).
 * Shared by both playback engines so they behave the same.
 */
class Program(rec: Recording) {
    /** Steps that can run (image steps without an area are left out). */
    val steps: List<Step> = rec.steps().filter { it !is ImageStep || it.ready }

    /** Delay before step i when reached in order (not after a jump). */
    val gaps: LongArray = LongArray(steps.size) { i ->
        if (i == 0) 0 else (steps[i].at - (steps[i - 1].at + steps[i - 1].duration)).coerceAtLeast(0)
    }

    private val labelIndex: Map<String, Int> = steps.withIndex()
        .filter { (_, s) -> s is FlowStep && s.kind == FlowStep.LABEL }
        .associate { (i, s) -> (s as FlowStep).ref to i }

    /** Jumps taken by each GOTO step since it last fell through. */
    private val jumps = IntArray(steps.size)

    /** The last call to [next] was a jump (so the target runs without its delay). */
    var jumped = false
        private set

    val isEmpty: Boolean get() = steps.isEmpty()

    /** Start of a new outer loop: reset loop counters. */
    fun reset() {
        jumps.fill(0)
        jumped = false
    }

    private fun to(label: String?, fallback: Int): Int {
        val idx = label?.let { labelIndex[it] }
        jumped = idx != null
        return idx ?: fallback
    }

    /**
     * Index of the step to run after step [i]. [found] is the image result for image steps.
     * Returns [steps].size at the end of the recording and [STOP] to stop playback.
     */
    fun next(i: Int, found: Boolean = true): Int {
        jumped = false
        return when (val st = steps[i]) {
            is ImageStep -> when {
                found -> to(st.foundGoto, i + 1)
                st.onTimeout == ImageStep.ON_TIMEOUT_STOP -> STOP
                st.onTimeout == ImageStep.ON_TIMEOUT_GOTO -> to(st.failGoto, i + 1)
                else -> i + 1
            }
            is FlowStep -> when (st.kind) {
                FlowStep.STOP -> STOP
                FlowStep.GOTO -> {
                    if (st.count == 0 || jumps[i] < st.count) {
                        val t = to(st.ref, i + 1)
                        if (jumped) jumps[i]++
                        t
                    } else {
                        jumps[i] = 0
                        i + 1
                    }
                }
                else -> i + 1
            }
            else -> i + 1
        }
    }

    /** Random wait for a WAIT step, in ms. */
    fun waitMs(st: FlowStep, rnd: java.util.Random): Long =
        if (st.maxMs <= st.minMs) st.minMs else st.minMs + (rnd.nextDouble() * (st.maxMs - st.minMs)).toLong()

    companion object {
        const val STOP = -1

        /** Steps that take no time; a loop of only these gets a short pause so it can't spin the CPU. */
        fun isInstant(s: Step) = s is FlowStep && s.kind != FlowStep.WAIT
    }
}
