package com.nnazuna.nnaclicker

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Titik masuk proses app_process yang dijalankan sendiri lewat Shizuku (jalur cadangan
 * kalau layanan pengguna Shizuku tidak mau tersambung). Cara ini sama dengan perintah `input` bawaan Android.
 *
 * Protokol baris lewat stdin/stdout. Setiap balasan diawali "@@" supaya tidak tercampur log lain.
 *   PING                         -> @@OK pong uid=<uid>
 *   PROBE                        -> @@OK <json>
 *   REC <natW> <natH> <rotation> -> @@OK | @@ERR <pesan>
 *   COUNT                        -> @@OK <n>
 *   STOPREC                      -> @@OK <json rekaman>
 *   PLAY <loops> <speed> <interval> <startDelay> <jitterPx> <jitterPct>
 *   <json rekaman satu baris>    -> @@OK | @@ERR <pesan>
 *   STOP                         -> @@OK
 *   LOOP                         -> @@OK <n>   (-1 = tidak sedang memutar)
 *   ERR                          -> @@OK <pesan error terakhir>
 *   SNAP                         -> @@OK <PNG base64>
 *   EXIT
 */
object ShellMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = PrintStream(System.out, true, "UTF-8")
        val input = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
        val engine = InputEngine()

        fun ok(s: String = "") = out.println(if (s.isEmpty()) "@@OK" else "@@OK $s")
        fun err(s: String) = out.println("@@ERR " + s.replace('\n', ' '))

        out.println("@@READY")
        while (true) {
            val line = try { input.readLine() } catch (e: Exception) { null } ?: break
            val p = line.trim().split(" ")
            try {
                when (p[0]) {
                    "PING" -> ok("pong uid=" + android.os.Process.myUid())
                    "PROBE" -> engine.probe().let { if (it.isEmpty()) err(engine.lastError()) else ok(it) }
                    "REC" -> if (engine.startRecord(p[1].toInt(), p[2].toInt(), p[3].toInt())) ok() else err(engine.lastError())
                    "COUNT" -> ok(engine.recordCount().toString())
                    "STOPREC" -> ok(engine.stopRecord())
                    "PLAY" -> {
                        val json = input.readLine() ?: break
                        val started = engine.play(
                            json, p[1].toInt(), p[2].toFloat(), p[3].toLong(), p[4].toLong(),
                            p[5].toFloat(), p[6].toFloat(),
                        )
                        if (started) ok() else err(engine.lastError())
                    }
                    "STOP" -> { engine.stopPlay(); ok() }
                    "LOOP" -> ok(engine.playLoop().toString())
                    "STEP" -> ok(engine.playStep().toString())
                    "ERR" -> ok(engine.lastError())
                    "SNAP" -> engine.snapPng()?.let {
                        ok(android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP))
                    } ?: err("screencap failed")
                    "EXIT" -> break
                    else -> err("unknown command: ${p[0]}")
                }
            } catch (e: Throwable) {
                err(e.toString())
            }
        }
        engine.shutdown()
        exitProcess(0)
    }
}
