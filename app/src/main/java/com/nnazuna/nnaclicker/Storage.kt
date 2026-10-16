package com.nnazuna.nnaclicker

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Menyimpan rekaman sebagai file JSON di penyimpanan internal aplikasi + pengaturan putar. */
class Storage(context: Context) {
    companion object {
        const val LOG_MAX = 20
    }

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

    /** The setup guide was finished or dismissed. */
    var setupSeen: Boolean
        get() = prefs.getBoolean("setup_seen", false)
        set(v) = prefs.edit().putBoolean("setup_seen", v).apply()

    // ---------- screenshots for image steps ----------

    private val snapDir = File(context.filesDir, "snaps").apply { mkdirs() }

    fun snapFile(name: String?): File? = name?.let { File(snapDir, it) }?.takeIf { it.exists() }

    /** Save a PNG screenshot and return its file name. Keeps the newest ones plus any still in use. */
    fun saveSnap(png: ByteArray): String {
        val name = "snap-" + System.currentTimeMillis() + ".png"
        File(snapDir, name).writeBytes(png)
        pruneSnaps()
        return name
    }

    /** Newest first. */
    fun snaps(): List<File> =
        (snapDir.listFiles { f -> f.name.endsWith(".png") } ?: emptyArray()).sortedByDescending { it.lastModified() }

    /** How many image steps use each screenshot. */
    fun snapUsage(): Map<String, Int> =
        list().flatMap { r -> r.images.mapNotNull { it.snap } }.groupingBy { it }.eachCount()

    fun deleteSnap(name: String) {
        File(snapDir, name).delete()
    }

    private fun pruneSnaps() {
        val used = list().flatMap { r -> r.images.mapNotNull { it.snap } }.toSet()
        snaps().drop(12).filter { it.name !in used }.forEach { it.delete() }
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

    /** The global playback options. */
    fun globalSettings() = PlaySettings(loops, speed, intervalMs, startDelayMs, maxMinutes, jitterPx, jitterPct)

    fun saveGlobal(s: PlaySettings) {
        loops = s.loops; speed = s.speed; intervalMs = s.intervalMs; startDelayMs = s.startDelayMs
        maxMinutes = s.maxMinutes; jitterPx = s.jitterPx; jitterPct = s.jitterPct
    }

    /** Options used to play [r]: its own if it has them, otherwise the global ones. */
    fun settingsFor(r: Recording?): PlaySettings = r?.settings ?: globalSettings()

    /** The paged tutorial was shown. */
    var tutorialSeen: Boolean
        get() = prefs.getBoolean("tutorial_seen", false)
        set(v) = prefs.edit().putBoolean("tutorial_seen", v).apply()

    /** "auto" (follow the phone), "en" or "id". */
    var language: String
        get() = prefs.getString("language", "auto") ?: "auto"
        set(v) = prefs.edit().putString("language", v).apply()

    // ---------- run log: screenshots saved when an image step stops playback ----------

    private val logDir = File(context.filesDir, "runlog").apply { mkdirs() }
    private val logFile = File(logDir, "log.json")

    fun runLog(): List<RunLogEntry> = runCatching {
        val a = org.json.JSONArray(logFile.readText())
        (0 until a.length()).map { RunLogEntry.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun logShot(e: RunLogEntry): File? = e.shot?.let { File(logDir, it) }?.takeIf { it.exists() }

    /** Add an entry (newest first) and keep the last [LOG_MAX]. */
    fun addRunLog(e: RunLogEntry, png: ByteArray?) {
        if (png != null) {
            val name = "fail-${e.time}.png"
            File(logDir, name).writeBytes(png)
            e.shot = name
        }
        val all = listOf(e) + runLog()
        all.drop(LOG_MAX).forEach { old -> old.shot?.let { File(logDir, it).delete() } }
        writeLog(all.take(LOG_MAX))
    }

    fun deleteRunLog(e: RunLogEntry) {
        e.shot?.let { File(logDir, it).delete() }
        writeLog(runLog().filter { it.time != e.time })
    }

    fun clearRunLog() {
        logDir.listFiles()?.forEach { it.delete() }
    }

    private fun writeLog(list: List<RunLogEntry>) {
        logFile.writeText(org.json.JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
    }

    /** Copy a run log screenshot into the screenshots used by image steps; returns its name. */
    fun snapFromLog(e: RunLogEntry): String? = logShot(e)?.let { saveSnap(it.readBytes()) }

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

    /** Vibrate and sound when playback ends on its own or stops with an error. */
    var alertDone: Boolean
        get() = prefs.getBoolean("alert_done", false)
        set(v) = prefs.edit().putBoolean("alert_done", v).apply()

    /** Tingkat gelap 10–95 %. */
    var dimLevel: Int
        get() = prefs.getInt("dim_level", 80)
        set(v) = prefs.edit().putInt("dim_level", v.coerceIn(10, 95)).apply()
}
