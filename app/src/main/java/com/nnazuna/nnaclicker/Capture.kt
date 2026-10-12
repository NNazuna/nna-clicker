package com.nnazuna.nnaclicker

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.view.Display
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/** Screen capture from the shell process (Shizuku): fast, no prompts, works in the background. */
object ShellCapture {

    /** Current screen as a half-resolution [ScreenFrame], read from raw `screencap` output (no PNG encoding). */
    fun grab(): ScreenFrame? {
        val bytes = try {
            val p = Runtime.getRuntime().exec(arrayOf("screencap"))
            val b = p.inputStream.readBytes()
            p.waitFor()
            b
        } catch (e: Throwable) {
            return null
        }
        if (bytes.size < 16) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = bb.getInt(0)
        val h = bb.getInt(4)
        val format = bb.getInt(8)
        if (w <= 0 || h <= 0) return null
        val header = bytes.size - w * h * 4
        if (header < 12 || header > 16) return null
        // 1 = RGBA_8888, 2 = RGBX_8888, 5 = BGRA_8888
        val bgr = format == 5
        val s = ImageMatcher.SCALE
        val fw = w / s
        val fh = h / s
        val out = IntArray(fw * fh)
        val n = s * s
        for (y in 0 until fh) {
            for (x in 0 until fw) {
                var r = 0; var g = 0; var b = 0
                for (dy in 0 until s) {
                    var o = header + ((y * s + dy) * w + x * s) * 4
                    for (dx in 0 until s) {
                        val c0 = bytes[o].toInt() and 255
                        val c1 = bytes[o + 1].toInt() and 255
                        val c2 = bytes[o + 2].toInt() and 255
                        if (bgr) { b += c0; g += c1; r += c2 } else { r += c0; g += c1; b += c2 }
                        o += 4
                    }
                }
                out[y * fw + x] = ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return ScreenFrame(fw, fh, out)
    }

    /** Full-resolution PNG of the screen, for cutting templates. */
    fun png(): ByteArray? = try {
        val p = Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
        val b = p.inputStream.readBytes()
        p.waitFor()
        if (b.size > 100) b else null
    } catch (e: Throwable) {
        null
    }
}

/** Screen capture through the accessibility service (Android 11+). Limited to about 3 per second. */
@TargetApi(30)
object A11yCapture {
    private val executor = Executors.newSingleThreadExecutor()

    /** Full-resolution software bitmap, or null on failure (for example when called too often). */
    fun bitmap(svc: AccessibilityService, cb: (Bitmap?) -> Unit) {
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp = try {
                        Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.let { hw ->
                            hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
                        }
                    } catch (e: Throwable) {
                        null
                    } finally {
                        hb.close()
                    }
                    cb(bmp)
                }

                override fun onFailure(errorCode: Int) = cb(null)
            })
        } catch (e: Throwable) {
            cb(null)
        }
    }

    /** Half-resolution frame for matching. Callback runs on a background thread. */
    fun frame(svc: AccessibilityService, cb: (ScreenFrame?) -> Unit) {
        bitmap(svc) { bmp ->
            if (bmp == null) { cb(null); return@bitmap }
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            cb(ImageMatcher.downscale(px, w, h))
        }
    }
}
