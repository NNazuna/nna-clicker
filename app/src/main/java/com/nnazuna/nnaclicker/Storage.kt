package com.nnazuna.nnaclicker

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Menyimpan rekaman sebagai file JSON di penyimpanan internal aplikasi + pengaturan putar. */
class Storage(context: Context) {
    private val dir = File(context.filesDir, "recordings").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun list(): List<Recording> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { Recording.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.created }

    fun get(id: String?): Recording? {
        if (id == null) return null
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return runCatching { Recording.fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun save(r: Recording) {
        File(dir, "${r.id}.json").writeText(r.toJson().toString())
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        if (selectedId == id) selectedId = null
    }

    var selectedId: String?
        get() = prefs.getString("selected", null)
        set(v) = prefs.edit().putString("selected", v).apply()

    /** Recording yang dipilih; kalau belum ada yang dipilih, pakai yang terbaru. */
    fun selected(): Recording? = get(selectedId) ?: list().firstOrNull()

    /** 0 = ulangi tanpa batas. */
    var loops: Int
        get() = prefs.getInt("loops", 1)
        set(v) = prefs.edit().putInt("loops", v.coerceAtLeast(0)).apply()

    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        set(v) = prefs.edit().putFloat("speed", v.coerceIn(0.1f, 10f)).apply()

    /** Jeda antar ulangan, milidetik. */
    var intervalMs: Long
        get() = prefs.getLong("interval", 1000L)
        set(v) = prefs.edit().putLong("interval", v.coerceAtLeast(0L)).apply()

    /** Jeda sebelum putar dimulai setelah tombol ▶ ditekan, milidetik. */
    var startDelayMs: Long
        get() = prefs.getLong("start_delay", 1000L)
        set(v) = prefs.edit().putLong("start_delay", v.coerceAtLeast(0L)).apply()

    /** Pakai Shizuku untuk rekam & putar (tanpa delay). */
    var useShizuku: Boolean
        get() = prefs.getBoolean("use_shizuku", false)
        set(v) = prefs.edit().putBoolean("use_shizuku", v).apply()

    /** Acak posisi tiap gerakan ±piksel (0 = mati). */
    var jitterPx: Float
        get() = prefs.getFloat("jitter_px", 0f)
        set(v) = prefs.edit().putFloat("jitter_px", v.coerceIn(0f, 200f)).apply()

    /** Acak jeda antar gerakan ±persen (0 = mati). */
    var jitterPct: Float
        get() = prefs.getFloat("jitter_pct", 0f)
        set(v) = prefs.edit().putFloat("jitter_pct", v.coerceIn(0f, 90f)).apply()

    /** Berhenti otomatis setelah sekian menit memutar (0 = tanpa batas). */
    var maxMinutes: Int
        get() = prefs.getInt("max_minutes", 0)
        set(v) = prefs.edit().putInt("max_minutes", v.coerceIn(0, 24 * 60)).apply()

    /** Gelapkan layar selama memutar. */
    var dimScreen: Boolean
        get() = prefs.getBoolean("dim_screen", false)
        set(v) = prefs.edit().putBoolean("dim_screen", v).apply()

    /** Tingkat gelap 10–95 %. */
    var dimLevel: Int
        get() = prefs.getInt("dim_level", 80)
        set(v) = prefs.edit().putInt("dim_level", v.coerceIn(10, 95)).apply()
}
