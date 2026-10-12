package com.nnazuna.nnaclicker

import org.json.JSONArray
import org.json.JSONObject

/** Satu titik sentuhan. [t] = milidetik sejak jari pertama menyentuh layar (awal gerakan). */
data class TouchPoint(val x: Float, val y: Float, val t: Long)

/** Jejak satu jari dari turun sampai diangkat. */
class Stroke(val points: List<TouchPoint>)

/**
 * Satu gerakan utuh (dari jari pertama turun sampai semua jari terangkat).
 * [at] = milidetik sejak tombol Rekam ditekan.
 */
class RecGesture(var at: Long, val strokes: List<Stroke>) {
    val duration: Long
        get() = (strokes.maxOfOrNull { it.points.last().t } ?: 1L).coerceAtLeast(1L)

    val isTap: Boolean
        get() = strokes.size == 1 && duration < 250 && run {
            val p = strokes[0].points
            val dx = p.last().x - p.first().x
            val dy = p.last().y - p.first().y
            dx * dx + dy * dy < 400f
        }

    /** Jenis gerakan untuk ditampilkan di editor. */
    val kind: String
        get() = when {
            strokes.size > 1 -> "${strokes.size}-finger"
            isTap -> "Tap"
            else -> {
                val p = strokes[0].points
                val dx = p.last().x - p.first().x
                val dy = p.last().y - p.first().y
                if (dx * dx + dy * dy < 400f) "Hold" else "Swipe"
            }
        }

    /** Salinan yang digeser [dx],[dy] piksel (untuk fitur acak posisi). */
    fun shifted(dx: Float, dy: Float): RecGesture =
        if (dx == 0f && dy == 0f) this
        else RecGesture(at, strokes.map { s ->
            Stroke(s.points.map { TouchPoint((it.x + dx).coerceAtLeast(0f), (it.y + dy).coerceAtLeast(0f), it.t) })
        })
}

class Recording(
    val id: String,
    var name: String,
    val created: Long,
    val gestures: MutableList<RecGesture>,
) {
    /** Panjang rekaman dari gerakan pertama sampai gerakan terakhir selesai. */
    val length: Long
        get() {
            if (gestures.isEmpty()) return 0
            val last = gestures.last()
            return last.at + last.duration - gestures.first().at
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("created", created)
        put("gestures", JSONArray().apply {
            for (g in gestures) {
                put(JSONObject().apply {
                    put("at", g.at)
                    put("strokes", JSONArray().apply {
                        for (s in g.strokes) {
                            // Disimpan rata: [x, y, t, x, y, t, ...] supaya file kecil
                            val arr = JSONArray()
                            for (p in s.points) {
                                arr.put(round1(p.x))
                                arr.put(round1(p.y))
                                arr.put(p.t)
                            }
                            put(arr)
                        }
                    })
                })
            }
        })
    }

    companion object {
        private fun round1(v: Float): Double = Math.round(v * 10.0) / 10.0

        fun fromJson(o: JSONObject): Recording {
            val gs = mutableListOf<RecGesture>()
            val ga = o.getJSONArray("gestures")
            for (i in 0 until ga.length()) {
                val go = ga.getJSONObject(i)
                val sa = go.getJSONArray("strokes")
                val strokes = mutableListOf<Stroke>()
                for (j in 0 until sa.length()) {
                    val arr = sa.getJSONArray(j)
                    val pts = mutableListOf<TouchPoint>()
                    var k = 0
                    while (k + 2 < arr.length()) {
                        pts.add(
                            TouchPoint(
                                arr.getDouble(k).toFloat(),
                                arr.getDouble(k + 1).toFloat(),
                                arr.getLong(k + 2),
                            )
                        )
                        k += 3
                    }
                    if (pts.isNotEmpty()) strokes.add(Stroke(pts))
                }
                if (strokes.isNotEmpty()) gs.add(RecGesture(go.getLong("at"), strokes))
            }
            return Recording(
                o.optString("id", java.util.UUID.randomUUID().toString()),
                o.optString("name", "Recording"),
                o.optLong("created", 0L),
                gs,
            )
        }
    }
}
