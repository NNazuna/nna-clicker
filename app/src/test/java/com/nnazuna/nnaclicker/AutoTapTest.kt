package com.nnazuna.nnaclicker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoTapTest {
    private fun tapAt(r: Recording) = r.gestures.map { it.at }

    @Test
    fun `quick tap loops one tap and waits after the touch`() {
        val r = AutoTap.quickTap(100f, 200f, everyMs = 1000, now = 5, holdMs = 60)
        assertEquals(AutoTap.QUICK_TAP_ID, r.id)
        assertEquals(listOf(1L), tapAt(r))
        val p = r.gestures[0].strokes[0].points
        assertEquals(100f, p.first().x)
        assertEquals(200f, p.last().y)
        assertEquals(60L, p.last().t)
        val label = r.flows.first { it.kind == FlowStep.LABEL }
        val jump = r.flows.first { it.kind == FlowStep.GOTO }
        assertEquals(label.ref, jump.ref)
        assertEquals(1L + 60 + 1000, jump.at)
        assertEquals("Auto tap · every 1 s", r.name)
    }

    @Test
    fun `quick tap interval is clamped`() {
        val r = AutoTap.quickTap(0f, 0f, everyMs = 1, now = 0)
        assertEquals(1L + 60 + AutoTap.MIN_EVERY_MS, r.flows.first { it.kind == FlowStep.GOTO }.at)
    }

    @Test
    fun `multi tap spaces targets by interval plus each delay`() {
        val points = listOf(MultiPoint(1f, 1f), MultiPoint(2f, 2f, delayMs = 250), MultiPoint(3f, 3f))
        val r = AutoTap.multiTap(points, everyMs = 500, holdMs = 80, now = 0)
        assertEquals(AutoTap.MULTI_TAP_ID, r.id)
        assertEquals(listOf(1L, 501L, 1251L), tapAt(r))
        assertEquals(1751L, r.flows.first { it.kind == FlowStep.GOTO }.at)
        assertEquals(80L, r.gestures[1].strokes[0].points.last().t)
        assertEquals("Multi tap · 3 targets · every 500 ms", r.name)
    }

    @Test
    fun `multi tap keeps a given name and reuses one id`() {
        val a = AutoTap.multiTap(listOf(MultiPoint(1f, 1f)), 1000, 60, 0, name = "Farm")
        val b = AutoTap.multiTap(listOf(MultiPoint(5f, 5f)), 1000, 60, 1)
        assertEquals("Farm", a.name)
        assertEquals(a.id, b.id)
    }

    @Test
    fun `next setup name skips past the highest number`() {
        assertEquals("Setup 1", AutoTap.nextSetupName(emptyList()))
        assertEquals("Setup 4", AutoTap.nextSetupName(listOf("Setup 1", "Setup 3", "Farm")))
        assertEquals("Setup 1", AutoTap.nextSetupName(listOf("My Setup 9", "Setup x")))
    }

    @Test
    fun `setup survives a json round trip`() {
        val s = MultiSetup("id1", "Farm", mutableListOf(MultiPoint(10f, 20f, 300), MultiPoint(30f, 40f)), 750, 90, 42)
        val back = MultiSetup.fromJson(JSONObject(s.toJson().toString()))!!
        assertEquals("Farm", back.name)
        assertEquals(750L, back.everyMs)
        assertEquals(90L, back.holdMs)
        assertEquals(42L, back.created)
        assertEquals(listOf(10f, 30f), back.points.map { it.x })
        assertEquals(listOf(300L, 0L), back.points.map { it.delayMs })
    }

    @Test
    fun `broken setup json is rejected or clamped`() {
        assertNull(MultiSetup.fromJson(JSONObject("{}")))
        val s = MultiSetup.fromJson(JSONObject(
            """{"id":"x","every":5,"hold":99999,"points":[{"x":1,"y":2,"delay":-5},"junk"]}""",
        ))!!
        assertEquals(100L, s.everyMs)
        assertEquals(2_000L, s.holdMs)
        assertEquals(1, s.points.size)
        assertEquals(0L, s.points[0].delayMs)
    }
}
