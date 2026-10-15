package com.nnazuna.nnaclicker

import android.content.Context
import kotlin.system.exitProcess

/** Layanan pengguna Shizuku: pembungkus binder di atas [InputEngine]. */
class ShizukuInputService() : IInputService.Stub() {

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    private val engine = InputEngine()
    private var result = ""
    private val upload = StringBuilder()

    override fun destroy() {
        engine.shutdown()
        exitProcess(0)
    }

    override fun probe(): String = engine.probe()
    override fun startRecord(natW: Int, natH: Int, rotation: Int): Boolean = engine.startRecord(natW, natH, rotation)
    override fun recordCount(): Int = engine.recordCount()

    override fun stopRecord(): Int {
        result = engine.stopRecord()
        return result.length
    }

    override fun readResult(offset: Int, length: Int): String {
        val start = offset.coerceIn(0, result.length)
        val end = (offset + length).coerceIn(start, result.length)
        return result.substring(start, end)
    }

    override fun beginUpload() { upload.setLength(0) }
    override fun appendUpload(chunk: String) { upload.append(chunk) }

    override fun play(
        loops: Int, speed: Float, intervalMs: Long, startDelayMs: Long,
        jitterPx: Float, jitterPct: Float,
    ): Boolean = engine.play(upload.toString(), loops, speed, intervalMs, startDelayMs, jitterPx, jitterPct)

    override fun snap(): Int {
        val png = engine.snapPng() ?: return 0
        result = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
        return result.length
    }

    override fun stopPlay() = engine.stopPlay()
    override fun playLoop(): Int = engine.playLoop()
    override fun playStep(): Int = engine.playStep()
    override fun lastError(): String = engine.lastError()
}
