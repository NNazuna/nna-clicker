package com.nnazuna.nnaclicker

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * Sisi aplikasi: cek Shizuku, minta izin, dan sambungkan ke mesin rekam/putar ber-hak shell.
 * Urutan: coba layanan pengguna Shizuku dulu; kalau tidak tersambung dalam beberapa detik,
 * jalankan proses app_process sendiri lewat Shizuku (jalur cadangan).
 */
object ShizukuBridge {
    private const val REQ = 4721
    private const val BIND_TIMEOUT_MS = 5000L

    @Volatile var backend: InputBackend? = null
        private set

    /** Pesan kegagalan terakhir saat menyambung (untuk ditampilkan di aplikasi). */
    @Volatile var lastError: String = ""
        private set

    @Volatile var connecting = false
        private set

    /** Dipanggil saat status Shizuku berubah (supaya UI bisa refresh). */
    var onChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var initialized = false
    private var args: Shizuku.UserServiceArgs? = null
    private var appContext: Context? = null

    private fun changed() = main.post { onChanged?.invoke() }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            if (backend?.isAlive() != true) {
                backend = BinderBackend(IInputService.Stub.asInterface(binder))
                connecting = false
                lastError = ""
            }
            changed()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (backend is BinderBackend) backend = null
            changed()
        }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky {
            if (Storage(appContext!!).useShizuku && hasPermission()) connect()
            changed()
        }
        Shizuku.addBinderDeadListener {
            backend?.close()
            backend = null
            changed()
        }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code == REQ && result == PackageManager.PERMISSION_GRANTED) connect()
            changed()
        }
    }

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isRunning() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            if (isRunning() && !hasPermission()) Shizuku.requestPermission(REQ)
        } catch (_: Throwable) {}
    }

    val isConnected: Boolean get() = backend?.isAlive() == true

    /** Info singkat untuk diagnosa: versi Shizuku, mode (root/adb), jalur aktif, error terakhir. */
    fun diagnostics(): String {
        if (!isRunning()) return ""
        val sb = StringBuilder()
        try {
            val uid = Shizuku.getUid()
            sb.append("Shizuku v").append(Shizuku.getVersion())
                .append(" · ").append(if (uid == 0) "root" else if (uid == 2000) "adb" else "uid $uid")
        } catch (_: Throwable) {}
        backend?.let { sb.append(" · via ").append(it.label) }
        if (lastError.isNotEmpty()) sb.append("\nError: ").append(lastError)
        return sb.toString()
    }

    /** Sambungkan (idempoten). */
    fun connect() {
        val ctx = appContext ?: return
        if (!hasPermission() || isConnected || connecting) return
        connecting = true
        lastError = ""
        changed()

        val a = args ?: Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShizukuInputService::class.java.name))
            .daemon(false)
            .processNameSuffix("input")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE)
            .also { args = it }
        try {
            Shizuku.bindUserService(a, conn)
        } catch (e: Throwable) {
            lastError = "bindUserService: $e"
        }

        // Kalau layanan pengguna tidak tersambung, pakai jalur proses sendiri.
        main.postDelayed({
            if (!isConnected && connecting) startProcessBackend(ctx)
        }, BIND_TIMEOUT_MS)
    }

    private fun startProcessBackend(ctx: Context) {
        val apk = ctx.applicationInfo.sourceDir
        thread(name = "shell-start", isDaemon = true) {
            try {
                val b = ProcessBackend.start(apk)
                val pong = b.ping()
                if (backend?.isAlive() == true) {
                    b.close()
                } else {
                    backend = b
                    lastError = ""
                    android.util.Log.i("NNAClicker", "process backend ready: $pong")
                }
            } catch (e: Throwable) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                lastError = "Couldn't start Shizuku service: ${cause.message ?: cause}"
            } finally {
                connecting = false
                changed()
            }
        }
    }

    fun disconnect() {
        args?.let {
            try { Shizuku.unbindUserService(it, conn, true) } catch (_: Throwable) {}
        }
        backend?.close()
        backend = null
        connecting = false
        changed()
    }

    /** Putus lalu sambung ulang dari awal. */
    fun reconnect() {
        disconnect()
        main.postDelayed({ connect() }, 300)
    }
}
