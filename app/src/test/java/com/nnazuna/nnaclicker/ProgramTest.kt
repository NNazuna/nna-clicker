package com.nnazuna.nnaclicker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Unit tests for [Program], the playback flow shared by both input engines:
 * delays between steps, labels and Go to loops, and image-step branching.
 * Pure JVM, no device needed.
 */
class ProgramTest {

    private fun tap(at: Long) = RecGesture.tap(100f, 200f, at) // lasts 60 ms

    private fun label(at: Long, id: String) = FlowStep(at, FlowStep.LABEL, ref = id, name = id)

    private fun goto(at: Long, id: String, count: Int = 0) = FlowStep(at, FlowStep.GOTO, ref = id, count = count)

    private fun image(
        at: Long,
        ready: Boolean = true,
        onTimeout: Int = ImageStep.ON_TIMEOUT_STOP,
        foundGoto: String? = null,
        failGoto: String? = null,
    ) = ImageStep(
        at, snap = null,
        tw = if (ready) 2 else 0, th = if (ready) 2 else 0,
        tpl = if (ready) IntArray(4) else IntArray(0),
        onTimeout = onTimeout, foundGoto = foundGoto, failGoto = failGoto,
    )

    private fun program(
        gestures: List<RecGesture> = emptyList(),
        images: List<ImageStep> = emptyList(),
        flows: List<FlowStep> = emptyList(),
        startAt: Long = -1,
    ) = Program(
        Recording("id", "test", 0L, gestures.toMutableList(), images.toMutableList(), flows.toMutableList())
            .apply { this.startAt = startAt },
    )

    @Test
    fun gaps_areTimeBetweenEndOfOneStepAndStartOfNext() {
        val p = program(gestures = listOf(tap(0), tap(500), tap(520)))
        // 0 for the first step, 500 - 60 = 440, then overlapping steps never wait a negative time.
        assertArrayEquals(longArrayOf(0, 440, 0), p.gaps)
    }

    @Test
    fun imageStepsWithoutAnAreaAreLeftOut() {
        val p = program(gestures = listOf(tap(0)), images = listOf(image(100, ready = false), image(200)))
        assertEquals(2, p.steps.size)
        assertTrue(p.steps[1] is ImageStep)
    }

    @Test
    fun emptyRecordingIsEmpty() {
        assertTrue(program().isEmpty)
    }

    @Test
    fun atTheSameTime_labelComesBeforeGesture() {
        val p = program(gestures = listOf(tap(0)), flows = listOf(label(0, "L")))
        assertTrue(p.steps[0] is FlowStep)
        assertTrue(p.steps[1] is RecGesture)
    }

    @Test
    fun lastStepGoesToEndOfRecording() {
        val p = program(gestures = listOf(tap(0), tap(100)))
        assertEquals(1, p.next(0))
        assertEquals(p.steps.size, p.next(1))
    }

    @Test
    fun gotoWithCount_jumpsThatManyTimesThenFallsThrough() {
        // 0: label L, 1: tap, 2: go to L x2, 3: tap
        val p = program(gestures = listOf(tap(100), tap(300)), flows = listOf(label(0, "L"), goto(200, "L", count = 2)))
        assertEquals(0, p.next(2)); assertTrue(p.jumped)
        assertEquals(0, p.next(2)); assertTrue(p.jumped)
        assertEquals(3, p.next(2)); assertFalse(p.jumped)
        // The counter resets after falling through, so the loop works again on the next pass.
        assertEquals(0, p.next(2))
    }

    @Test
    fun gotoWithoutCount_alwaysJumps() {
        val p = program(gestures = listOf(tap(100)), flows = listOf(label(0, "L"), goto(200, "L")))
        repeat(50) { assertEquals(0, p.next(2)) }
    }

    @Test
    fun gotoToMissingLabel_continuesWithNextStep() {
        val p = program(gestures = listOf(tap(0), tap(200)), flows = listOf(goto(100, "missing", count = 3)))
        assertEquals(2, p.next(1))
        assertFalse(p.jumped)
    }

    @Test
    fun reset_restartsLoopCounters() {
        val p = program(gestures = listOf(tap(100), tap(300)), flows = listOf(label(0, "L"), goto(200, "L", count = 1)))
        assertEquals(0, p.next(2))
        p.reset()
        assertEquals(0, p.next(2)) // would fall through without the reset
        assertEquals(3, p.next(2))
    }

    @Test
    fun imageFound_followsFoundGotoOrContinues() {
        val p = program(
            flows = listOf(label(0, "L")),
            images = listOf(image(100, foundGoto = "L"), image(200)),
        )
        assertEquals(0, p.next(1, found = true)); assertTrue(p.jumped)
        assertEquals(3, p.next(2, found = true)); assertFalse(p.jumped)
    }

    @Test
    fun imageNotFound_stopsSkipsOrJumpsByOnTimeout() {
        val p = program(
            flows = listOf(label(0, "L")),
            images = listOf(
                image(100, onTimeout = ImageStep.ON_TIMEOUT_STOP),
                image(200, onTimeout = ImageStep.ON_TIMEOUT_SKIP),
                image(300, onTimeout = ImageStep.ON_TIMEOUT_GOTO, failGoto = "L"),
            ),
        )
        assertEquals(Program.STOP, p.next(1, found = false))
        assertEquals(3, p.next(2, found = false))
        assertEquals(0, p.next(3, found = false))
    }

    @Test
    fun stopStep_endsPlayback() {
        val p = program(gestures = listOf(tap(0), tap(200)), flows = listOf(FlowStep(100, FlowStep.STOP)))
        assertEquals(Program.STOP, p.next(1))
    }

    @Test
    fun start_isFirstStepAtOrAfterStartAt() {
        val steps = listOf(tap(0), tap(100), tap(200))
        assertEquals(0, program(gestures = steps).start)
        assertEquals(2, program(gestures = steps, startAt = 150).start)
        assertEquals(0, program(gestures = steps, startAt = 999).start) // nothing after: from the start
    }

    @Test
    fun waitMs_staysInRange() {
        val fixed = FlowStep(0, FlowStep.WAIT, minMs = 500, maxMs = 500)
        val program = program()
        assertEquals(500, program.waitMs(fixed, Random(1)))

        val random = FlowStep(0, FlowStep.WAIT, minMs = 1000, maxMs = 3000)
        val rnd = Random(42)
        repeat(1000) {
            val ms = program.waitMs(random, rnd)
            assertTrue("$ms out of range", ms in 1000..3000)
        }
    }

    @Test
    fun instantSteps_areLabelGotoAndStopOnly() {
        assertTrue(Program.isInstant(label(0, "L")))
        assertTrue(Program.isInstant(goto(0, "L")))
        assertTrue(Program.isInstant(FlowStep(0, FlowStep.STOP)))
        assertFalse(Program.isInstant(FlowStep(0, FlowStep.WAIT)))
        assertFalse(Program.isInstant(tap(0)))
    }

    @Test
    fun notFoundText_reportsClosestMatch() {
        val st = image(0).apply { threshold = 0.82f }
        assertEquals(
            "Image not found at step 3 (closest 64%, needs 82%). Playback stopped.",
            Program.notFoundText(2, st, 0.645f),
        )
        assertEquals("Image not found at step 1. Playback stopped.", Program.notFoundText(0, st, -1f))
    }
}
