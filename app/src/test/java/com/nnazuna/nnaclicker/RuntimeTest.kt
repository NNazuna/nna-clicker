package com.nnazuna.nnaclicker

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTest {
    @Test
    fun `single tap becomes down then up`() {
        val gesture = RecGesture(0, listOf(Stroke(listOf(TouchPoint(10f, 20f, 0)))))

        val frames = Timeline.frames(gesture)

        assertEquals(2, frames.size)
        assertEquals(MotionEvent.ACTION_DOWN, frames[0].action)
        assertEquals(MotionEvent.ACTION_UP, frames[1].action)
        assertEquals(20L, frames[1].t)
    }

    @Test
    fun `two fingers produce pointer down and pointer up actions`() {
        val gesture = RecGesture(
            0,
            listOf(
                Stroke(listOf(TouchPoint(10f, 10f, 0))),
                Stroke(listOf(TouchPoint(20f, 20f, 0))),
            ),
        )

        val actions = Timeline.frames(gesture).map { it.action }
        val pointerDown = MotionEvent.ACTION_POINTER_DOWN or
            (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pointerUp = MotionEvent.ACTION_POINTER_UP or
            (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        assertTrue(actions.contains(pointerDown))
        assertTrue(actions.contains(pointerUp))
        assertEquals(MotionEvent.ACTION_DOWN, actions.first())
        assertEquals(MotionEvent.ACTION_UP, actions.last())
    }

    @Test
    fun `evdev portrait coordinates map to screen pixels`() {
        val builder = EvdevGestureBuilder(
            minX = 0,
            maxX = 1000,
            minY = 0,
            maxY = 2000,
            natW = 1080,
            natH = 2400,
            rotation = 0,
            startUs = 0,
        )

        feedTap(builder, x = 500, y = 1000)

        val points = builder.gestures.single().strokes.single().points
        assertEquals(539.46f, points[0].x, 0.1f)
        assertEquals(1199.40f, points[0].y, 0.1f)
    }

    @Test
    fun `evdev landscape coordinates follow the rotation`() {
        val builder = EvdevGestureBuilder(
            minX = 0,
            maxX = 1000,
            minY = 0,
            maxY = 2000,
            natW = 1080,
            natH = 2400,
            rotation = 1,
            startUs = 0,
        )

        feedTap(builder, x = 500, y = 1000)

        val points = builder.gestures.single().strokes.single().points
        assertEquals(1199.40f, points[0].x, 0.1f)
        assertEquals(540.54f, points[0].y, 0.1f)
    }

    private fun feedTap(builder: EvdevGestureBuilder, x: Int, y: Int) {
        builder.onEvent(1_000_000, EvdevGestureBuilder.EV_ABS, EvdevGestureBuilder.ABS_MT_TRACKING_ID, 1)
        builder.onEvent(1_000_000, EvdevGestureBuilder.EV_ABS, EvdevGestureBuilder.ABS_MT_POSITION_X, x)
        builder.onEvent(1_000_000, EvdevGestureBuilder.EV_ABS, EvdevGestureBuilder.ABS_MT_POSITION_Y, y)
        builder.onEvent(1_000_000, EvdevGestureBuilder.EV_SYN, EvdevGestureBuilder.SYN_REPORT, 0)
        builder.onEvent(1_100_000, EvdevGestureBuilder.EV_ABS, EvdevGestureBuilder.ABS_MT_TRACKING_ID, -1)
        builder.onEvent(1_100_000, EvdevGestureBuilder.EV_SYN, EvdevGestureBuilder.SYN_REPORT, 0)
    }
}
