package com.nnazuna.nnaclicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private fun stroke(points: List<TouchPoint>) = Stroke(points)

    @Test
    fun `tap hold and swipe are classified`() {
        val tap = RecGesture(0, listOf(stroke(listOf(TouchPoint(10f, 10f, 0), TouchPoint(10f, 10f, 50)))))
        val hold = RecGesture(0, listOf(stroke(listOf(TouchPoint(10f, 10f, 0), TouchPoint(10f, 10f, 300)))))
        val swipe = RecGesture(0, listOf(stroke(listOf(TouchPoint(10f, 10f, 0), TouchPoint(100f, 100f, 50)))))

        assertTrue(tap.isTap)
        assertEquals("Tap", tap.kind)
        assertEquals("Hold", hold.kind)
        assertEquals("Swipe", swipe.kind)
    }

    @Test
    fun `shifted clamps points to the screen`() {
        val gesture = RecGesture(0, listOf(stroke(listOf(TouchPoint(1f, 2f, 0)))))

        val shifted = gesture.shifted(-10f, -10f)

        assertEquals(0f, shifted.strokes[0].points[0].x, 0f)
        assertEquals(0f, shifted.strokes[0].points[0].y, 0f)
    }

    @Test
    fun `play settings clamp unsafe values`() {
        val settings = PlaySettings(
            loops = -1,
            speed = 99f,
            intervalMs = -1,
            startDelayMs = -1,
            maxMinutes = 99_999,
            jitterPx = 999f,
            jitterPct = 999f,
        )

        assertEquals(0, settings.loops)
        assertEquals(10f, settings.speed, 0f)
        assertEquals(0L, settings.intervalMs)
        assertEquals(0L, settings.startDelayMs)
        assertEquals(24 * 60, settings.maxMinutes)
        assertEquals(200f, settings.jitterPx, 0f)
        assertEquals(90f, settings.jitterPct, 0f)
    }

    @Test
    fun `recording orders labels first at the same timestamp`() {
        val gesture = RecGesture(0, listOf(stroke(listOf(TouchPoint(10f, 10f, 100)))))
        val image = ImageStep(0, snap = null, tw = 1, th = 1, tpl = intArrayOf(0))
        val label = FlowStep(0, FlowStep.LABEL, ref = "start", name = "Start")

        val rec = Recording(
            id = "test",
            name = "Test",
            created = 0,
            gestures = mutableListOf(gesture),
            images = mutableListOf(image),
            flows = mutableListOf(label),
        )

        val steps = rec.steps()
        assertTrue(steps[0] is FlowStep)
        assertTrue(steps[1] is ImageStep)
        assertTrue(steps[2] is RecGesture)
        assertEquals(100L, rec.length)
    }

    @Test
    fun `labels are exposed by id`() {
        val rec = Recording(
            id = "test",
            name = "Test",
            created = 0,
            gestures = mutableListOf(),
            flows = mutableListOf(FlowStep(0, FlowStep.LABEL, ref = "start", name = "Start")),
        )

        assertEquals("Start", rec.labelName("start"))
        assertEquals(1, rec.labels().size)
    }
}
