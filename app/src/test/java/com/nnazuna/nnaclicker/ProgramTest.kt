package com.nnazuna.nnaclicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramTest {
    private fun gesture(at: Long, duration: Long = 100) =
        RecGesture(at, listOf(Stroke(listOf(TouchPoint(10f, 10f, 0), TouchPoint(10f, 10f, duration)))))

    private fun label(at: Long, id: String) = FlowStep(at, FlowStep.LABEL, ref = id, name = id)

    private fun recording(
        gestures: List<RecGesture> = emptyList(),
        images: List<ImageStep> = emptyList(),
        flows: List<FlowStep> = emptyList(),
    ) = Recording(
        id = "test",
        name = "Test",
        created = 0,
        gestures = gestures.toMutableList(),
        images = images.toMutableList(),
        flows = flows.toMutableList(),
    )

    @Test
    fun `image steps without a template are skipped`() {
        val program = Program(
            recording(
                gestures = listOf(gesture(0)),
                images = listOf(ImageStep(0, snap = null)),
            )
        )

        assertEquals(1, program.steps.size)
        assertTrue(program.steps.single() is RecGesture)
    }

    @Test
    fun `gaps include wait duration and clamp negative values`() {
        val program = Program(
            recording(
                gestures = listOf(gesture(1000, 200), gesture(2000, 100)),
                flows = listOf(FlowStep(1500, FlowStep.WAIT, minMs = 300, maxMs = 300)),
            )
        )

        assertEquals(listOf(0L, 300L, 200L), program.gaps.toList())
    }

    @Test
    fun `goto jumps the configured number of times then falls through`() {
        val program = Program(
            recording(
                gestures = listOf(gesture(30)),
                flows = listOf(
                    label(0, "loop"),
                    FlowStep(10, FlowStep.GOTO, ref = "loop", count = 2),
                ),
            )
        )

        assertEquals(0, program.next(1))
        assertTrue(program.jumped)

        assertEquals(0, program.next(1))
        assertTrue(program.jumped)

        assertEquals(2, program.next(1))
        assertFalse(program.jumped)
    }

    @Test
    fun `image step branches to the found and timeout labels`() {
        val image = ImageStep(
            0,
            snap = null,
            tw = 1,
            th = 1,
            tpl = intArrayOf(0),
            foundGoto = "done",
            onTimeout = ImageStep.ON_TIMEOUT_GOTO,
            failGoto = "fail",
        )
        val program = Program(
            recording(
                images = listOf(image),
                flows = listOf(label(10, "done"), label(20, "fail")),
            )
        )

        assertEquals(1, program.next(0, found = true))
        assertTrue(program.jumped)

        assertEquals(2, program.next(0, found = false))
        assertTrue(program.jumped)
    }

    @Test
    fun `image step can stop playback on timeout`() {
        val image = ImageStep(
            0,
            snap = null,
            tw = 1,
            th = 1,
            tpl = intArrayOf(0),
            onTimeout = ImageStep.ON_TIMEOUT_STOP,
        )
        val program = Program(recording(images = listOf(image)))

        assertEquals(Program.STOP, program.next(0, found = false))
    }

    @Test
    fun `startAt selects the first step at or after the timestamp`() {
        val rec = recording(gestures = listOf(gesture(0), gesture(100), gesture(200)))
        rec.startAt = 150

        assertEquals(2, Program(rec).start)
    }
}
