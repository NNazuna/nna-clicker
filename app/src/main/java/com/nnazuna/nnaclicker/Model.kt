package com.nnazuna.nnaclicker

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Satu titik sentuhan. [t] = milidetik sejak jari pertama menyentuh layar (awal gerakan). */
data class TouchPoint(val x: Float, val y: Float, val t: Long)

/** Jejak satu jari dari turun sampai diangkat. */
class Stroke(val points: List<TouchPoint>)

/** Satu langkah di garis waktu rekaman: gerakan sentuh atau langkah gambar. */
interface Step {
    /** Milidetik sejak awal rekaman. */
    var at: Long
    val duration: Long
}

/**
 * Satu gerakan utuh (dari jari pertama turun sampai semua jari terangkat).
 * [at] = milidetik sejak tombol Rekam ditekan.
 */
class RecGesture(override var at: Long, val strokes: List<Stroke>) : Step {
    override val duration: Long
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

    companion object {
        /** Tap singkat di (x, y), untuk aksi "Tap image". */
        fun tap(x: Float, y: Float, at: Long = 0) =
            RecGesture(at, listOf(Stroke(listOf(TouchPoint(x, y, 0), TouchPoint(x, y, 60)))))
    }
}

/**
 * Langkah gambar: cari potongan gambar ([tpl]) di layar, lalu tunggu / ketuk / tunggu sampai hilang.
 * Template disimpan di skala [ImageMatcher.SCALE] (setengah resolusi layar), format RGB.
 */
class ImageStep(
    override var at: Long,
    /** Nama file screenshot sumber (folder snaps), untuk crop ulang. */
    var snap: String?,
    /** Area template pada screenshot, dalam piksel layar penuh. */
    var x: Int = 0, var y: Int = 0, var w: Int = 0, var h: Int = 0,
    /** Ukuran layar saat screenshot diambil. */
    var screenW: Int = 0, var screenH: Int = 0,
    var tw: Int = 0, var th: Int = 0,
    var tpl: IntArray = IntArray(0),
    var action: Int = ACTION_WAIT,
    var anywhere: Boolean = false,
    var timeoutMs: Long = 15_000,
    var onTimeout: Int = ON_TIMEOUT_STOP,
    var threshold: Float = 0.82f,
) : Step {
    override val duration: Long get() = 0

    val ready: Boolean get() = tw > 0 && th > 0 && tpl.size == tw * th

    val title: String
        get() = when (action) {
            ACTION_TAP -> "Tap image"
            ACTION_GONE -> "Wait until image is gone"
            else -> "Wait for image"
        }

    fun copy(newAt: Long = at) = ImageStep(
        newAt, snap, x, y, w, h, screenW, screenH, tw, th, tpl.copyOf(),
        action, anywhere, timeoutMs, onTimeout, threshold,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("at", at)
        put("snap", snap ?: "")
        put("rect", JSONArray().put(x).put(y).put(w).put(h))
        put("screen", JSONArray().put(screenW).put(screenH))
        put("tw", tw); put("th", th)
        val bytes = ByteArray(tpl.size * 3)
        for (i in tpl.indices) {
            val c = tpl[i]
            bytes[i * 3] = (c shr 16).toByte(); bytes[i * 3 + 1] = (c shr 8).toByte(); bytes[i * 3 + 2] = c.toByte()
        }
        put("tpl", Base64.encodeToString(bytes, Base64.NO_WRAP))
        put("action", action); put("anywhere", anywhere)
        put("timeout", timeoutMs); put("onTimeout", onTimeout); put("threshold", threshold.toDouble())
    }

    companion object {
        const val ACTION_WAIT = 0
        const val ACTION_TAP = 1
        const val ACTION_GONE = 2
        const val ON_TIMEOUT_STOP = 0
        const val ON_TIMEOUT_SKIP = 1

        fun fromJson(o: JSONObject): ImageStep {
            val r = o.optJSONArray("rect")
            val sc = o.optJSONArray("screen")
            val tw = o.optInt("tw"); val th = o.optInt("th")
            val raw = runCatching { Base64.decode(o.optString("tpl", ""), Base64.NO_WRAP) }.getOrDefault(ByteArray(0))
            val tpl = if (raw.size == tw * th * 3) IntArray(tw * th) { i ->
                ((raw[i * 3].toInt() and 255) shl 16) or ((raw[i * 3 + 1].toInt() and 255) shl 8) or (raw[i * 3 + 2].toInt() and 255)
            } else IntArray(0)
            return ImageStep(
                at = o.optLong("at"),
                snap = o.optString("snap", "").ifEmpty { null },
                x = r?.optInt(0) ?: 0, y = r?.optInt(1) ?: 0, w = r?.optInt(2) ?: 0, h = r?.optInt(3) ?: 0,
                screenW = sc?.optInt(0) ?: 0, screenH = sc?.optInt(1) ?: 0,
                tw = if (tpl.isEmpty()) 0 else tw, th = if (tpl.isEmpty()) 0 else th, tpl = tpl,
                action = o.optInt("action", ACTION_WAIT),
                anywhere = o.optBoolean("anywhere", false),
                timeoutMs = o.optLong("timeout", 15_000),
                onTimeout = o.optInt("onTimeout", ON_TIMEOUT_STOP),
                threshold = o.optDouble("threshold", 0.82).toFloat(),
            )
        }
    }
}

class Recording(
    val id: String,
    var name: String,
    val created: Long,
    val gestures: MutableList<RecGesture>,
    val images: MutableList<ImageStep> = mutableListOf(),
) {
    /** Lapisan gelap saat diputar (0..1), dipakai untuk mengoreksi warna saat mencocokkan gambar. Tidak disimpan. */
    var dim: Float = 0f

    /** Semua langkah urut waktu; pada waktu yang sama, langkah gambar lebih dulu. */
    fun steps(): List<Step> =
        (images + gestures).sortedWith(compareBy<Step>({ it.at }, { if (it is ImageStep) 0 else 1 }))

    val hasImages: Boolean get() = images.any { it.ready }

    /** Panjang rekaman dari langkah pertama sampai langkah terakhir selesai. */
    val length: Long
        get() {
            val s = steps()
            if (s.isEmpty()) return 0
            return s.maxOf { it.at + it.duration } - s.first().at
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("created", created)
        if (dim > 0f) put("dim", dim.toDouble())
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
        if (images.isNotEmpty()) put("images", JSONArray().apply { images.forEach { put(it.toJson()) } })
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
            val imgs = mutableListOf<ImageStep>()
            o.optJSONArray("images")?.let { ia ->
                for (i in 0 until ia.length()) runCatching { imgs.add(ImageStep.fromJson(ia.getJSONObject(i))) }
            }
            return Recording(
                o.optString("id", java.util.UUID.randomUUID().toString()),
                o.optString("name", "Recording"),
                o.optLong("created", 0L),
                gs,
                imgs,
            ).also { it.dim = o.optDouble("dim", 0.0).toFloat() }
        }
    }
}
