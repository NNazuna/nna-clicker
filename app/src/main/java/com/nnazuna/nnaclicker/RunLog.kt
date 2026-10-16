package com.nnazuna.nnaclicker

import org.json.JSONObject

/** One stop of playback because of a problem, with the screen at that moment. */
class RunLogEntry(
    val time: Long,
    val recId: String,
    val recName: String,
    /** Step number (1-based) in the editor, or 0 when unknown. */
    val stepNo: Int,
    /** Start time of the image step, to find it again after edits. */
    val stepAt: Long,
    /** Closest match in percent, or -1. */
    val closest: Int,
    /** Match needed in percent, or -1. */
    val need: Int,
    val message: String,
    var shot: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("time", time); put("rec", recId); put("name", recName); put("step", stepNo); put("at", stepAt)
        put("closest", closest); put("need", need); put("message", message)
        shot?.let { put("shot", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = RunLogEntry(
            o.optLong("time"), o.optString("rec"), o.optString("name"), o.optInt("step"), o.optLong("at", -1),
            o.optInt("closest", -1), o.optInt("need", -1), o.optString("message"),
            o.optString("shot", "").ifEmpty { null },
        )

        private val STEP = Regex("step (\\d+)")
        private val CLOSEST = Regex("closest (\\d+)%, needs (\\d+)%")

        /** Step number and match numbers read back from [Program.notFoundText]. */
        fun parse(err: String): Triple<Int, Int, Int> {
            val step = STEP.find(err)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val c = CLOSEST.find(err)
            return Triple(step, c?.groupValues?.get(1)?.toIntOrNull() ?: -1, c?.groupValues?.get(2)?.toIntOrNull() ?: -1)
        }
    }
}
