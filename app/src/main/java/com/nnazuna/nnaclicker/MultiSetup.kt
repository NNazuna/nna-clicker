package com.nnazuna.nnaclicker

import org.json.JSONArray
import org.json.JSONObject

/** One saved multi-target point. [delayMs] is the extra pause after this target. */
class MultiPoint(
    var x: Float,
    var y: Float,
    var delayMs: Long = 0L,
) {
    fun toJson() = JSONObject().apply {
        put("x", x.toDouble())
        put("y", y.toDouble())
        if (delayMs > 0) put("delay", delayMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = MultiPoint(
            o.optDouble("x", 0.0).toFloat(),
            o.optDouble("y", 0.0).toFloat(),
            o.optLong("delay", 0L).coerceIn(0L, 10_000L),
        )
    }
}

/** A named, reusable multi-target setup. */
class MultiSetup(
    val id: String,
    var name: String,
    val points: MutableList<MultiPoint>,
    var everyMs: Long,
    var holdMs: Long,
    val created: Long,
) {
    fun toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("every", everyMs)
        put("hold", holdMs)
        put("created", created)
        put("points", JSONArray().apply {
            points.forEach { put(it.toJson()) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): MultiSetup? = try {
            val id = o.optString("id")
            if (id.isEmpty()) null else {
                val arr = o.optJSONArray("points") ?: JSONArray()
                val points = mutableListOf<MultiPoint>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { points.add(MultiPoint.fromJson(it)) }
                }
                MultiSetup(
                    id = id,
                    name = o.optString("name", "Setup"),
                    points = points,
                    everyMs = o.optLong("every", 1000L).coerceIn(100L, 3_600_000L),
                    holdMs = o.optLong("hold", 60L).coerceIn(20L, 2_000L),
                    created = o.optLong("created", 0L),
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
