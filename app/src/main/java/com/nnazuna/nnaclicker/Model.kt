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
    /** Label id to jump to when found (null = continue with the next step). */
    var foundGoto: String? = null,
    /** Label id to jump to when not found, used when [onTimeout] is [ON_TIMEOUT_GOTO]. */
    var failGoto: String? = null,
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
        action, anywhere, timeoutMs, onTimeout, threshold, foundGoto, failGoto,
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
        foundGoto?.let { put("foundGoto", it) }
        failGoto?.let { put("failGoto", it) }
    }

    companion object {
        const val ACTION_WAIT = 0
        const val ACTION_TAP = 1
        const val ACTION_GONE = 2
        const val ON_TIMEOUT_STOP = 0
        const val ON_TIMEOUT_SKIP = 1
        const val ON_TIMEOUT_GOTO = 2

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
                foundGoto = o.optString("foundGoto", "").ifEmpty { null },
                failGoto = o.optString("failGoto", "").ifEmpty { null },
            )
        }
    }
}

/**
 * Control step: a named [LABEL], a [GOTO] that jumps to a label (optionally a limited number
 * of times, which makes a loop), a random [WAIT], [STOP] to end playback, a system [KEY]
 * (back, home, recent apps), or [LAUNCH] to open an app.
 */
class FlowStep(
    override var at: Long,
    var kind: Int,
    /** LABEL: its own id. GOTO: the target label id. KEY: [KEY_BACK], [KEY_HOME] or [KEY_RECENTS]. LAUNCH: package name. */
    var ref: String = "",
    /** LABEL: display name. LAUNCH: app name. */
    var name: String = "",
    /** GOTO: how many times to jump before continuing (0 = always). */
    var count: Int = 0,
    /** WAIT: random wait between [minMs] and [maxMs]. */
    var minMs: Long = 1000,
    var maxMs: Long = 1000,
) : Step {
    override val duration: Long get() = if (kind == WAIT) minMs else 0

    fun copy(newAt: Long = at) = FlowStep(newAt, kind, ref, name, count, minMs, maxMs)

    fun toJson(): JSONObject = JSONObject().apply {
        put("at", at); put("kind", kind)
        if (ref.isNotEmpty()) put("ref", ref)
        if (name.isNotEmpty()) put("name", name)
        if (count != 0) put("count", count)
        if (kind == WAIT) { put("min", minMs); put("max", maxMs) }
    }

    companion object {
        const val LABEL = 0
        const val GOTO = 1
        const val WAIT = 2
        const val STOP = 3
        const val KEY = 4
        const val LAUNCH = 5

        const val KEY_BACK = "back"
        const val KEY_HOME = "home"
        const val KEY_RECENTS = "recents"

        fun keyName(ref: String) = when (ref) {
            KEY_HOME -> "Home"
            KEY_RECENTS -> "Recent apps"
            else -> "Back"
        }

        fun newId(): String = java.util.UUID.randomUUID().toString().take(8)

        fun fromJson(o: JSONObject) = FlowStep(
            at = o.optLong("at"),
            kind = o.optInt("kind", LABEL),
            ref = o.optString("ref", ""),
            name = o.optString("name", ""),
            count = o.optInt("count", 0),
            minMs = o.optLong("min", 1000),
            maxMs = o.optLong("max", 1000),
        )
    }
}

/** Playback options. Global ones live in [Storage]; a recording can keep its own copy. */
class PlaySettings(
    loops: Int = 1,
    speed: Float = 1f,
    intervalMs: Long = 1000,
    startDelayMs: Long = 1000,
    maxMinutes: Int = 0,
    jitterPx: Float = 0f,
    jitterPct: Float = 0f,
) {
    /** 0 = repeat until stopped. */
    var loops = loops; set(v) { field = v.coerceAtLeast(0) }
    var speed = speed; set(v) { field = v.coerceIn(0.1f, 10f) }
    var intervalMs = intervalMs; set(v) { field = v.coerceAtLeast(0L) }
    var startDelayMs = startDelayMs; set(v) { field = v.coerceAtLeast(0L) }
    /** Auto stop after this many minutes (0 = off). */
    var maxMinutes = maxMinutes; set(v) { field = v.coerceIn(0, 24 * 60) }
    var jitterPx = jitterPx; set(v) { field = v.coerceIn(0f, 200f) }
    var jitterPct = jitterPct; set(v) { field = v.coerceIn(0f, 90f) }

    fun copy() = PlaySettings(loops, speed, intervalMs, startDelayMs, maxMinutes, jitterPx, jitterPct)

    fun toJson(): JSONObject = JSONObject().apply {
        put("loops", loops); put("speed", speed.toDouble()); put("interval", intervalMs)
        put("startDelay", startDelayMs); put("maxMinutes", maxMinutes)
        put("jitterPx", jitterPx.toDouble()); put("jitterPct", jitterPct.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject) = PlaySettings(
            o.optInt("loops", 1), o.optDouble("speed", 1.0).toFloat(), o.optLong("interval", 1000),
            o.optLong("startDelay", 1000), o.optInt("maxMinutes", 0),
            o.optDouble("jitterPx", 0.0).toFloat(), o.optDouble("jitterPct", 0.0).toFloat(),
        )
    }
}

class Recording(
    val id: String,
    var name: String,
    val created: Long,
    val gestures: MutableList<RecGesture>,
    val images: MutableList<ImageStep> = mutableListOf(),
    val flows: MutableList<FlowStep> = mutableListOf(),
) {
    /** Lapisan gelap saat diputar (0..1), dipakai untuk mengoreksi warna saat mencocokkan gambar. Tidak disimpan. */
    var dim: Float = 0f

    /** Play from the first step at or after this time (first loop only); -1 = from the start. Not saved. */
    var startAt: Long = -1

    /** Playback options for this recording only; null = use the global ones. */
    var settings: PlaySettings? = null

    /** Semua langkah urut waktu; pada waktu yang sama: label, langkah gambar/alur, lalu gerakan. */
    fun steps(): List<Step> =
        (flows + images + gestures).sortedWith(compareBy<Step>({ it.at }, {
            when {
                it is FlowStep && it.kind == FlowStep.LABEL -> 0
                it is RecGesture -> 2
                else -> 1
            }
        }))

    /** Labels by id. */
    fun labels(): List<FlowStep> = steps().filterIsInstance<FlowStep>().filter { it.kind == FlowStep.LABEL }

    fun labelName(id: String?): String? = flows.firstOrNull { it.kind == FlowStep.LABEL && it.ref == id }?.name

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
        if (startAt >= 0) put("startAt", startAt)
        settings?.let { put("settings", it.toJson()) }
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
        if (flows.isNotEmpty()) put("flow", JSONArray().apply { flows.forEach { put(it.toJson()) } })
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
            val fl = mutableListOf<FlowStep>()
            o.optJSONArray("flow")?.let { fa ->
                for (i in 0 until fa.length()) runCatching { fl.add(FlowStep.fromJson(fa.getJSONObject(i))) }
            }
            return Recording(
                o.optString("id", java.util.UUID.randomUUID().toString()),
                o.optString("name", "Recording"),
                o.optLong("created", 0L),
                gs,
                imgs,
                fl,
            ).also {
                it.dim = o.optDouble("dim", 0.0).toFloat()
                it.startAt = o.optLong("startAt", -1)
                it.settings = o.optJSONObject("settings")?.let { s -> PlaySettings.fromJson(s) }
            }
        }
    }
}
