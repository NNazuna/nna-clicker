package com.nnazuna.nnaclicker

import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Jalur ke mesin rekam/putar yang berjalan dengan hak shell. */
interface InputBackend {
    val label: String
    fun isAlive(): Boolean
    fun startRecord(natW: Int, natH: Int, rotation: Int): Boolean
    fun recordCount(): Int
    fun stopRecord(): Recording?
    fun play(r: Recording, loops: Int, speed: Float, intervalMs: Long, startDelayMs: Long, jitterPx: Float, jitterPct: Float): Boolean
    fun stopPlay()
    fun playLoop(): Int
    fun playStep(): Int
    fun lastError(): String
    /** Full-resolution PNG of the screen. */
    fun snap(): ByteArray?
    fun close()
}

/** Lewat layanan pengguna Shizuku (binder AIDL). */
class BinderBackend(private val s: IInputService) : InputBackend {
    override val label = "user service"
    private val chunk = 200_000

    override fun isAlive() = s.asBinder().pingBinder()
    override fun startRecord(natW: Int, natH: Int, rotation: Int) = s.startRecord(natW, natH, rotation)
    override fun recordCount() = s.recordCount()

    override fun stopRecord(): Recording? {
        val len = s.stopRecord()
        if (len <= 0) return null
        val sb = StringBuilder(len)
        var off = 0
        while (off < len) {
            sb.append(s.readResult(off, chunk))
            off += chunk
        }
        return Recording.fromJson(JSONObject(sb.toString()))
    }

    override fun play(r: Recording, loops: Int, speed: Float, intervalMs: Long, startDelayMs: Long, jitterPx: Float, jitterPct: Float): Boolean {
        val json = r.toJson().toString()
        s.beginUpload()
        var off = 0
        while (off < json.length) {
            s.appendUpload(json.substring(off, minOf(json.length, off + chunk)))
            off += chunk
        }
        return s.play(loops, speed, intervalMs, startDelayMs, jitterPx, jitterPct)
    }

    override fun snap(): ByteArray? {
        val len = s.snap()
        if (len <= 0) return null
        val sb = StringBuilder(len)
        var off = 0
        while (off < len) {
            sb.append(s.readResult(off, chunk))
            off += chunk
        }
        return android.util.Base64.decode(sb.toString(), android.util.Base64.NO_WRAP)
    }

    override fun stopPlay() = s.stopPlay()
    override fun playLoop() = s.playLoop()
    override fun playStep() = s.playStep()
    override fun lastError(): String = s.lastError() ?: ""
    override fun close() {}
}

/** Lewat proses app_process sendiri ([ShellMain]) yang dijalankan Shizuku; bicara lewat stdin/stdout. */
class ProcessBackend private constructor(private val proc: Process) : InputBackend {
    override val label = "shell process"

    private val writer = proc.outputStream.bufferedWriter(Charsets.UTF_8)
    private val replies = LinkedBlockingQueue<String>()
    private val log = ArrayDeque<String>()
    @Volatile private var dead = false

    init {
        thread(name = "shell-out", isDaemon = true) {
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (line.startsWith("@@")) replies.put(line.substring(2)) else addLog(line)
                }
            } catch (_: Exception) {}
            dead = true
            replies.put("DEAD")
        }
        thread(name = "shell-err", isDaemon = true) {
            try {
                proc.errorStream.bufferedReader().forEachLine { addLog(it) }
            } catch (_: Exception) {}
        }
    }

    private fun addLog(s: String) = synchronized(log) {
        log.addLast(s)
        while (log.size > 30) log.removeFirst()
    }

    /** Beberapa baris log terakhir dari proses (untuk diagnosa). */
    fun recentLog(): String = synchronized(log) { log.takeLast(6).joinToString("\n") }

    @Synchronized
    private fun call(cmd: String, extra: String? = null, timeoutMs: Long = 4000): String {
        if (dead) throw IllegalStateException("shell process stopped. ${recentLog()}")
        replies.clear()
        writer.write(cmd)
        writer.newLine()
        if (extra != null) {
            writer.write(extra)
            writer.newLine()
        }
        writer.flush()
        val r = replies.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw IllegalStateException("no reply to $cmd")
        if (r == "DEAD") throw IllegalStateException("shell process stopped. ${recentLog()}")
        if (r.startsWith("ERR")) throw IllegalStateException(r.removePrefix("ERR").trim())
        return r.removePrefix("OK").trim()
    }

    private var lastLocalErr = ""

    private inline fun <T> guard(def: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        lastLocalErr = e.message ?: e.toString()
        def
    }

    fun ping(): String = call("PING", timeoutMs = 3000)

    override fun isAlive() = !dead
    override fun startRecord(natW: Int, natH: Int, rotation: Int) = guard(false) {
        call("REC $natW $natH $rotation"); true
    }
    override fun recordCount() = guard(0) { call("COUNT").toInt() }
    override fun stopRecord(): Recording? = guard(null) {
        val json = call("STOPREC", timeoutMs = 15000)
        if (json.isEmpty()) null else Recording.fromJson(JSONObject(json))
    }
    override fun play(r: Recording, loops: Int, speed: Float, intervalMs: Long, startDelayMs: Long, jitterPx: Float, jitterPct: Float) =
        guard(false) {
            call("PLAY $loops $speed $intervalMs $startDelayMs $jitterPx $jitterPct", r.toJson().toString(), 15000)
            true
        }
    override fun snap(): ByteArray? = guard(null) {
        android.util.Base64.decode(call("SNAP", timeoutMs = 15000), android.util.Base64.NO_WRAP)
    }
    override fun stopPlay() { guard(Unit) { call("STOP"); Unit } }
    override fun playLoop() = guard(-1) { call("LOOP").toInt() }
    override fun playStep() = guard(-1) { call("STEP").toInt() }
    override fun lastError(): String {
        val remote = guard("") { call("ERR") }
        return remote.ifEmpty { lastLocalErr }
    }
    override fun close() {
        try {
            writer.write("EXIT\n"); writer.flush()
        } catch (_: Exception) {}
        try { proc.destroy() } catch (_: Exception) {}
        dead = true
    }

    companion object {
        /** Jalankan [ShellMain] dengan hak shell lewat Shizuku. Blocking ±1-3 detik, panggil di luar main thread. */
        fun start(apkPath: String): ProcessBackend {
            val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java,
            )
            m.isAccessible = true
            val cmd = "CLASSPATH='$apkPath' exec /system/bin/app_process /system/bin ${ShellMain::class.java.name}"
            val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val b = ProcessBackend(proc)
            val first = b.replies.poll(10, TimeUnit.SECONDS)
            if (first != "READY") {
                val log = b.recentLog()
                b.close()
                throw IllegalStateException(if (first == null) "process not responding. $log" else "process failed to start. $log")
            }
            return b
        }
    }
}
