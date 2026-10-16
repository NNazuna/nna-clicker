package com.nnazuna.nnaclicker

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject
import java.io.FileDescriptor
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.concurrent.thread

/**
 * Mesin rekam/putar yang berjalan di proses ber-hak shell (lewat Shizuku), bukan di proses aplikasi.
 * Dipakai oleh [ShizukuInputService] (layanan pengguna Shizuku) dan [ShellMain] (proses app_process sendiri).
 * - Merekam: membaca event mentah touchscreen dari /dev/input/eventX secara langsung,
 *   jadi sentuhan tetap sampai ke game seketika (tanpa lapisan penangkap → tanpa delay).
 * - Memutar: menyuntik MotionEvent lewat InputManager dengan waktu yang presisi.
 */
class InputEngine {

    private class Device(
        val path: String,
        val minX: Int, val maxX: Int,
        val minY: Int, val maxY: Int,
    )

    @Volatile private var lastErr = ""
    private var device: Device? = null

    // --- rekam ---
    private class Session(val builder: EvdevGestureBuilder) {
        @Volatile var running = true
    }
    @Volatile private var session: Session? = null

    // --- putar ---
    @Volatile private var playing = false
    @Volatile private var currentLoop = -1
    @Volatile private var currentStep = -1
    private var playThread: Thread? = null
    @Volatile private var injectFailed = false
    @Volatile private var paused = false
    /** Closest match (0..1) of the image step being waited for, for the failure message. */
    private var bestScore = -1f

    fun setPaused(p: Boolean) { paused = p }

    private val injector: Pair<Any, Method>? by lazy {
        try {
            val cls = try {
                Class.forName("android.hardware.input.InputManagerGlobal")
            } catch (e: ClassNotFoundException) {
                Class.forName("android.hardware.input.InputManager")
            }
            val inst = cls.getMethod("getInstance").invoke(null)!!
            val m = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            Pair(inst, m)
        } catch (e: Throwable) {
            lastErr = "Injector: $e"
            null
        }
    }

    fun shutdown() {
        stopPlay()
        session?.running = false
    }

    fun lastError(): String = lastErr

    // ---------------- deteksi touchscreen ----------------

    private fun findDevice(): Device? {
        device?.let { return it }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("getevent", "-pl"))
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            val maxRe = Regex("min (-?\\d+), max (-?\\d+)")
            var path: String? = null
            var x: Pair<Int, Int>? = null
            var y: Pair<Int, Int>? = null
            var found: Device? = null
            for (line in text.lines()) {
                if (line.startsWith("add device")) {
                    if (found == null && path != null && x != null && y != null) {
                        found = Device(path, x.first, x.second, y.first, y.second)
                    }
                    path = line.substringAfter(": ").trim()
                    x = null; y = null
                    continue
                }
                val m = maxRe.find(line) ?: continue
                val range = Pair(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                if (line.contains("ABS_MT_POSITION_X")) x = range
                if (line.contains("ABS_MT_POSITION_Y")) y = range
            }
            if (found == null && path != null && x != null && y != null) {
                found = Device(path, x.first, x.second, y.first, y.second)
            }
            if (found == null) lastErr = "Touchscreen not found"
            device = found
            found
        } catch (e: Throwable) {
            lastErr = "probe: $e"
            null
        }
    }

    fun probe(): String {
        val d = findDevice() ?: return ""
        return JSONObject().apply {
            put("path", d.path)
            put("minX", d.minX); put("maxX", d.maxX)
            put("minY", d.minY); put("maxY", d.maxY)
            put("is64", Process.is64Bit())
        }.toString()
    }

    // ---------------- merekam ----------------

    fun startRecord(natW: Int, natH: Int, rotation: Int): Boolean {
        session?.running = false
        val d = findDevice() ?: return false
        val fd: FileDescriptor = try {
            Os.open(d.path, OsConstants.O_RDONLY, 0)
        } catch (e: Throwable) {
            lastErr = "Can't open ${d.path}: $e"
            return false
        }
        val startUs = System.currentTimeMillis() * 1000
        val s = Session(EvdevGestureBuilder(d.minX, d.maxX, d.minY, d.maxY, natW, natH, rotation, startUs))
        session = s
        thread(name = "evdev-reader", isDaemon = true) { readLoop(fd, s) }
        return true
    }

    private fun readLoop(fd: FileDescriptor, s: Session) {
        // struct input_event: timeval (2 x long) + u16 type + u16 code + s32 value
        val is64 = Process.is64Bit()
        val size = if (is64) 24 else 16
        val buf = ByteArray(size * 64)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val pfd = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLIN.toShort()
        }
        try {
            while (s.running) {
                pfd.revents = 0
                if (Os.poll(arrayOf(pfd), 200) <= 0) continue
                val n = Os.read(fd, buf, 0, buf.size)
                if (n <= 0) continue
                synchronized(s) {
                    var off = 0
                    while (off + size <= n) {
                        val sec: Long
                        val usec: Long
                        if (is64) {
                            sec = bb.getLong(off); usec = bb.getLong(off + 8)
                        } else {
                            sec = bb.getInt(off).toLong(); usec = bb.getInt(off + 4).toLong()
                        }
                        val type = bb.getShort(off + size - 8).toInt() and 0xffff
                        val code = bb.getShort(off + size - 6).toInt() and 0xffff
                        val value = bb.getInt(off + size - 4)
                        s.builder.onEvent(sec * 1_000_000 + usec, type, code, value)
                        off += size
                    }
                }
            }
        } catch (e: Throwable) {
            lastErr = "read: $e"
        } finally {
            try { Os.close(fd) } catch (_: Throwable) {}
        }
    }

    fun recordCount(): Int {
        val s = session ?: return 0
        return synchronized(s) { s.builder.gestures.size }
    }

    /** Hentikan rekaman, kembalikan JSON rekaman (format [Recording]). */
    fun stopRecord(): String {
        val s = session ?: return ""
        s.running = false
        session = null
        val list = synchronized(s) { s.builder.gestures.toMutableList() }
        return Recording("tmp", "tmp", 0L, list).toJson().toString()
    }

    // ---------------- memutar ----------------

    fun playLoop(): Int = if (playing) currentLoop else -1

    /** Index of the step being played, or -1. */
    fun playStep(): Int = if (playing) currentStep else -1

    fun stopPlay() {
        // Thread pemutar mengecek flag ini minimal tiap 100 ms lalu melepas jari yang masih menempel.
        playing = false
    }

    fun play(
        json: String,
        loops: Int, speed: Float, intervalMs: Long, startDelayMs: Long,
        jitterPx: Float, jitterPct: Float,
    ): Boolean {
        stopPlay()
        playThread?.join(500)
        val rec = try {
            Recording.fromJson(JSONObject(json))
        } catch (e: Throwable) {
            lastErr = "Invalid recording: $e"
            return false
        }
        lastErr = ""
        injectFailed = false
        paused = false
        val prog = Program(rec)
        if (prog.isEmpty || injector == null) return false
        val sp = speed.coerceIn(0.1f, 10f)
        playing = true
        currentLoop = 1
        currentStep = 0
        playThread = thread(name = "player", isDaemon = true) {
            val rnd = Random()
            try {
                sleepFor(startDelayMs)
                var loop = 1
                while (playing && (loops == 0 || loop <= loops)) {
                    currentLoop = loop
                    prog.reset()
                    var i = if (loop == 1) prog.start else 0
                    var first = true
                    var instant = 0
                    var wallEnd = SystemClock.uptimeMillis()
                    while (playing && i in prog.steps.indices) {
                        val st = prog.steps[i]
                        currentStep = i
                        while (playing && paused) Thread.sleep(50)
                        // The delay before a step is skipped right after a jump.
                        if (!first && !prog.jumped) {
                            var gap = prog.gaps[i].toDouble()
                            if (jitterPct > 0) gap *= 1 + (rnd.nextDouble() * 2 - 1) * jitterPct / 100.0
                            sleepUntil(wallEnd + (gap / sp).toLong())
                        }
                        first = false
                        if (!playing) break
                        var found = true
                        when (st) {
                            is RecGesture -> {
                                val gg = if (jitterPx > 0) st.shifted(
                                    ((rnd.nextDouble() * 2 - 1) * jitterPx).toFloat(),
                                    ((rnd.nextDouble() * 2 - 1) * jitterPx).toFloat(),
                                ) else st
                                wallEnd = inject(gg, sp)
                                instant = 0
                            }
                            is ImageStep -> {
                                found = waitImage(st, rec.dim, jitterPx, rnd)
                                wallEnd = SystemClock.uptimeMillis()
                                instant = 0
                            }
                            is FlowStep -> {
                                when (st.kind) {
                                    FlowStep.WAIT -> sleepFor((prog.waitMs(st, rnd) / sp).toLong())
                                    FlowStep.KEY -> key(st.ref)
                                    FlowStep.LAUNCH -> launch(st.ref)
                                }
                                if (Program.isInstant(st)) instant++ else instant = 0
                                wallEnd = SystemClock.uptimeMillis()
                            }
                        }
                        if (!playing) break
                        val n = prog.next(i, found)
                        if (n == Program.STOP) {
                            if (st is ImageStep) lastErr = notFoundMessage(i, st, bestScore)
                            playing = false
                            break
                        }
                        // A loop made only of labels and jumps would spin; give it a breather.
                        if (instant > 50) { sleepFor(100); instant = 0 }
                        i = n
                    }
                    loop++
                    if (playing && (loops == 0 || loop <= loops)) sleepFor(intervalMs)
                }
            } catch (e: Throwable) {
                lastErr = "play: $e"
            } finally {
                playing = false
                currentLoop = -1
                currentStep = -1
            }
        }
        return true
    }

    /**
     * Look for [st] on screen until it appears (or disappears), then run its action.
     * Returns false on timeout.
     */
    private fun waitImage(st: ImageStep, dim: Float, jitterPx: Float, rnd: Random): Boolean {
        var deadline = SystemClock.uptimeMillis() + st.timeoutMs
        bestScore = -1f
        while (playing) {
            if (paused) { Thread.sleep(100); deadline += 100; continue }
            val frame = ShellCapture.grab()
            if (frame != null) {
                val d = ImageMatcher.Diag()
                val m = ImageMatcher.find(frame, st, dim, d)
                bestScore = maxOf(bestScore, d.best)
                when (st.action) {
                    ImageStep.ACTION_GONE -> if (m == null) return true
                    ImageStep.ACTION_TAP -> if (m != null) {
                        val j = { if (jitterPx > 0) ((rnd.nextDouble() * 2 - 1) * minOf(jitterPx, st.w / 4f)).toFloat() else 0f }
                        inject(RecGesture.tap(m.x + j(), m.y + j()), 1f)
                        return true
                    }
                    else -> if (m != null) return true
                }
            }
            if (SystemClock.uptimeMillis() >= deadline) return false
            sleepFor(60)
        }
        return false
    }

    private fun notFoundMessage(i: Int, st: ImageStep, best: Float) = Program.notFoundText(i, st, best)

    /** Press a system button: back, home, or recent apps. */
    private fun key(ref: String) {
        val (im, m) = injector ?: return
        val code = when (ref) {
            FlowStep.KEY_HOME -> KeyEvent.KEYCODE_HOME
            FlowStep.KEY_RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
            else -> KeyEvent.KEYCODE_BACK
        }
        val down = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val ev = KeyEvent(down, SystemClock.uptimeMillis(), action, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
            try {
                m.invoke(im, ev, 0)
            } catch (e: Throwable) {
                lastErr = "Key press blocked by system: ${e.cause ?: e}"
            }
        }
    }

    /** Open an app by package name, the same way the launcher does. */
    private fun launch(pkg: String) {
        if (pkg.isEmpty()) return
        try {
            val p = Runtime.getRuntime().exec(arrayOf(
                "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1",
            ))
            p.inputStream.readBytes()
            p.waitFor()
        } catch (e: Throwable) {
            lastErr = "Can't open $pkg: $e"
        }
    }

    /** Full-resolution PNG of the current screen. */
    fun snapPng(): ByteArray? = ShellCapture.png()

    private fun sleepFor(ms: Long) = sleepUntil(SystemClock.uptimeMillis() + ms)

    /** Sleep until [target]; while paused the target moves later, so waits resume where they were. */
    private fun sleepUntil(target: Long, pausable: Boolean = true) {
        var t = target
        while (playing) {
            if (pausable && paused) { Thread.sleep(50); t += 50; continue }
            val d = t - SystemClock.uptimeMillis()
            if (d <= 0) return
            if (d > 3) Thread.sleep(minOf(d - 2, 100)) else Thread.yield()
        }
    }

    /** Suntik satu gerakan; mengembalikan uptime saat gerakan selesai. */
    private fun inject(g: RecGesture, speed: Float): Long {
        val frames = Timeline.frames(g)
        val downTime = SystemClock.uptimeMillis()
        var last: Frame? = null
        for (f in frames) {
            // Never pause in the middle of a gesture: the finger would stay down.
            sleepUntil(downTime + (f.t / speed).toLong(), pausable = false)
            if (!playing) break
            send(downTime, f.action, f)
            last = f
        }
        // Dihentikan di tengah gerakan: batalkan supaya jari tidak "tersangkut".
        val l = last
        if (l != null && l.action and MotionEvent.ACTION_MASK != MotionEvent.ACTION_UP) {
            send(downTime, MotionEvent.ACTION_CANCEL, l)
        }
        return SystemClock.uptimeMillis()
    }

    private fun send(downTime: Long, action: Int, f: Frame) {
        val (im, m) = injector ?: return
        val n = f.ids.size
        val props = Array(n) {
            MotionEvent.PointerProperties().apply {
                id = f.ids[it]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(n) {
            MotionEvent.PointerCoords().apply {
                x = f.xs[it]; y = f.ys[it]
                pressure = 1f; size = 1f
            }
        }
        val ev = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, n, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            m.invoke(im, ev, 0)
        } catch (e: Throwable) {
            // Contoh: SecurityException karena ROM memblokir suntik sentuhan dari ADB.
            lastErr = "Touch injection blocked by system: ${e.cause ?: e}"
            injectFailed = true
            playing = false
        } finally {
            ev.recycle()
        }
    }
}
