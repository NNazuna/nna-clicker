package com.nnazuna.nnaclicker

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Screen image at [ImageMatcher.SCALE] (half resolution), packed 0xRRGGBB. */
class ScreenFrame(val w: Int, val h: Int, val rgb: IntArray)

/**
 * Template matching tuned for phones:
 * - grayscale normalized cross-correlation (robust to brightness changes and the dim overlay),
 * - coarse-to-fine image pyramid, so a full-screen search stays in the tens of milliseconds,
 * - search limited to the area around where the template was cut, unless "anywhere" is set,
 * - a final color check so a gray "disabled" button doesn't match a colored one.
 */
object ImageMatcher {
    /** Frames and templates are stored at 1/SCALE of the screen resolution. */
    const val SCALE = 2

    /** Largest template side allowed at the coarsest pyramid level. */
    private const val COARSE_MAX_SIDE = 24
    private const val COARSE_MIN_SIDE = 4
    private const val CANDIDATES = 8
    /** Mean (per 4x4 cell) of the largest channel difference allowed, 0..255. */
    private const val MAX_COLOR_DIFF = 40f

    /** What the search saw, for the "Test" button. */
    class Diag {
        /** Best similarity of any candidate, before the color check (-1 when nothing was compared). */
        var best = -1f
        /** The best candidate looked right but its colors were too different. */
        var colorRejected = false
    }

    class Match(
        /** Center of the match in full-resolution screen pixels. */
        val x: Int,
        val y: Int,
        val score: Float,
    )

    // ---------- pixel helpers ----------

    fun gray(c: Int): Int = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000

    /** Box-average downscale of a full-resolution ARGB/RGB buffer by [SCALE]. */
    fun downscale(src: IntArray, w: Int, h: Int, stride: Int = w): ScreenFrame {
        val fw = w / SCALE
        val fh = h / SCALE
        val out = IntArray(fw * fh)
        val n = SCALE * SCALE
        for (y in 0 until fh) {
            for (x in 0 until fw) {
                var r = 0; var g = 0; var b = 0
                for (dy in 0 until SCALE) {
                    val row = (y * SCALE + dy) * stride + x * SCALE
                    for (dx in 0 until SCALE) {
                        val c = src[row + dx]
                        r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
                    }
                }
                out[y * fw + x] = ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return ScreenFrame(fw, fh, out)
    }

    /** Cut the template for a screen rectangle (full-res coords) out of a full-res frame. */
    fun cut(full: IntArray, w: Int, h: Int, rx: Int, ry: Int, rw: Int, rh: Int): ScreenFrame {
        val x0 = rx.coerceIn(0, w - 1)
        val y0 = ry.coerceIn(0, h - 1)
        val cw = min(rw, w - x0) / SCALE * SCALE
        val ch = min(rh, h - y0) / SCALE * SCALE
        val sub = IntArray(cw * ch)
        for (yy in 0 until ch) System.arraycopy(full, (y0 + yy) * w + x0, sub, yy * cw, cw)
        return downscale(sub, cw, ch)
    }

    // ---------- gray pyramid ----------

    private class G(val w: Int, val h: Int, val v: FloatArray)

    private fun grayOf(f: IntArray, fw: Int, x0: Int, y0: Int, w: Int, h: Int): G {
        val v = FloatArray(w * h)
        for (y in 0 until h) {
            val row = (y0 + y) * fw + x0
            for (x in 0 until w) v[y * w + x] = gray(f[row + x]).toFloat()
        }
        return G(w, h, v)
    }

    private fun shrink(g: G, f: Int): G {
        if (f == 1) return g
        val w = g.w / f
        val h = g.h / f
        val v = FloatArray(w * h)
        val n = (f * f).toFloat()
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (dy in 0 until f) {
                val row = (y * f + dy) * g.w + x * f
                for (dx in 0 until f) s += g.v[row + dx]
            }
            v[y * w + x] = s / n
        }
        return G(w, h, v)
    }

    /** NCC of template [t] placed at (px, py) in [img]. */
    private fun ncc(img: G, t: G, tMean: Float, tNorm: Float, px: Int, py: Int): Float {
        if (px < 0 || py < 0 || px + t.w > img.w || py + t.h > img.h || tNorm <= 0f) return -1f
        var sum = 0f; var sumSq = 0f; var cross = 0f
        for (y in 0 until t.h) {
            val ir = (py + y) * img.w + px
            val tr = y * t.w
            for (x in 0 until t.w) {
                val a = img.v[ir + x]
                sum += a; sumSq += a * a
                cross += a * (t.v[tr + x] - tMean)
            }
        }
        val n = (t.w * t.h).toFloat()
        val varI = sumSq - sum * sum / n
        if (varI <= 1e-3f) return 0f
        return cross / (sqrt(varI) * tNorm)
    }

    private fun stats(t: G): Pair<Float, Float> {
        var s = 0f
        for (a in t.v) s += a
        val mean = s / t.v.size
        var q = 0f
        for (a in t.v) q += (a - mean) * (a - mean)
        return Pair(mean, sqrt(q))
    }

    /** Full NCC map at the coarse level using integral images for the window statistics. */
    private fun scan(img: G, t: G): Array<FloatArray> {
        val (tMean, tNorm) = stats(t)
        val ow = img.w - t.w + 1
        val oh = img.h - t.h + 1
        if (ow <= 0 || oh <= 0 || tNorm <= 0f) return emptyArray()
        val iw = img.w + 1
        val s1 = DoubleArray(iw * (img.h + 1))
        val s2 = DoubleArray(iw * (img.h + 1))
        for (y in 0 until img.h) {
            var r1 = 0.0; var r2 = 0.0
            for (x in 0 until img.w) {
                val a = img.v[y * img.w + x].toDouble()
                r1 += a; r2 += a * a
                s1[(y + 1) * iw + x + 1] = s1[y * iw + x + 1] + r1
                s2[(y + 1) * iw + x + 1] = s2[y * iw + x + 1] + r2
            }
        }
        val tz = FloatArray(t.v.size) { t.v[it] - tMean }
        val n = (t.w * t.h).toDouble()
        val out = Array(oh) { FloatArray(ow) }
        for (py in 0 until oh) for (px in 0 until ow) {
            val a = py * iw + px; val b = py * iw + px + t.w
            val c = (py + t.h) * iw + px; val d = (py + t.h) * iw + px + t.w
            val sum = s1[d] - s1[b] - s1[c] + s1[a]
            val sq = s2[d] - s2[b] - s2[c] + s2[a]
            val varI = sq - sum * sum / n
            if (varI <= 1e-3) { out[py][px] = 0f; continue }
            var cross = 0f
            for (y in 0 until t.h) {
                val ir = (py + y) * img.w + px
                val tr = y * t.w
                for (x in 0 until t.w) cross += img.v[ir + x] * tz[tr + x]
            }
            out[py][px] = (cross / (sqrt(varI) * tNorm)).toFloat()
        }
        return out
    }

    /** Color difference (0..255) between the template and the matched patch: largest channel gap per cell of a 4x4 grid, averaged. */
    private fun colorDiff(f: ScreenFrame, x0: Int, y0: Int, tpl: IntArray, tw: Int, th: Int, dim: Float): Float {
        val gain = if (dim in 0.01f..0.95f) 1f / (1f - dim) else 1f
        val cells = 4
        var total = 0f
        var count = 0
        for (cy in 0 until cells) for (cx in 0 until cells) {
            val xa = cx * tw / cells; val xb = max(xa + 1, (cx + 1) * tw / cells)
            val ya = cy * th / cells; val yb = max(ya + 1, (cy + 1) * th / cells)
            var tr = 0f; var tg = 0f; var tb = 0f; var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
            for (y in ya until min(yb, th)) for (x in xa until min(xb, tw)) {
                val t = tpl[y * tw + x]
                val s = f.rgb[(y0 + y) * f.w + x0 + x]
                tr += (t shr 16) and 255; tg += (t shr 8) and 255; tb += t and 255
                sr += (s shr 16) and 255; sg += (s shr 8) and 255; sb += s and 255
                n++
            }
            if (n == 0) continue
            total += max(abs(tr - sr * gain), max(abs(tg - sg * gain), abs(tb - sb * gain))) / n
            count++
        }
        return if (count == 0) 0f else total / count
    }

    /** Pyramid phase alignment; the region origin keeps the template's original spot on this grid. */
    private const val ALIGN = 16

    /**
     * Search area in frame coordinates: around the original spot (a margin of about
     * a quarter of the short screen side), or the whole frame. The origin is aligned so the
     * original spot falls on the pyramid grid, which keeps the coarse search exact when the
     * target hasn't moved.
     */
    fun region(step: ImageStep, f: ScreenFrame): IntArray {
        val sameScreen = step.screenW / SCALE == f.w && step.screenH / SCALE == f.h
        val tx = step.x / SCALE
        val ty = step.y / SCALE
        fun align(raw: Int, target: Int): Int {
            var o = raw - Math.floorMod(target - raw, ALIGN)
            while (o < 0) o += ALIGN
            return o
        }
        if (step.anywhere || !sameScreen) {
            return intArrayOf(align(0, tx).coerceAtMost(f.w), align(0, ty).coerceAtMost(f.h), f.w, f.h)
        }
        val m = min(f.w, f.h) / 4 + max(step.tw, step.th) / 2
        val x0 = align((tx - m).coerceIn(0, f.w), tx).coerceAtMost(f.w)
        val y0 = align((ty - m).coerceIn(0, f.h), ty).coerceAtMost(f.h)
        val x1 = (tx + step.tw + m).coerceIn(0, f.w)
        val y1 = (ty + step.th + m).coerceIn(0, f.h)
        return intArrayOf(x0, y0, x1, y1)
    }

    fun find(f: ScreenFrame, step: ImageStep, dim: Float = 0f, diag: Diag? = null): Match? =
        find(f, step.tpl, step.tw, step.th, region(step, f), step.threshold, dim, diag)

    private fun crop(g: G, x0: Int, y0: Int): G {
        val w = g.w - x0
        val h = g.h - y0
        val v = FloatArray(max(0, w * h))
        for (y in 0 until h) System.arraycopy(g.v, (y + y0) * g.w + x0, v, y * w, w)
        return G(w, h, v)
    }

    fun find(
        f: ScreenFrame, tpl: IntArray, tw: Int, th: Int, region: IntArray, threshold: Float,
        dim: Float = 0f, diag: Diag? = null,
    ): Match? {
        val rx0 = region[0]; val ry0 = region[1]
        val rw = region[2] - rx0; val rh = region[3] - ry0
        if (tw < 2 || th < 2 || tw > rw || th > rh) return null

        val img = grayOf(f.rgb, f.w, rx0, ry0, rw, rh)
        val t = G(tw, th, FloatArray(tw * th) { gray(tpl[it]).toFloat() })

        // Pyramid levels are built once and shared by all candidates.
        val imgLv = HashMap<Int, G>()
        val tplLv = HashMap<Int, G>()
        fun il(l: Int) = imgLv.getOrPut(l) { shrink(img, l) }
        fun tl(l: Int) = tplLv.getOrPut(l) { shrink(t, l) }
        fun std(g: G) = stats(g).second / sqrt(g.v.size.toFloat())

        // Coarsest level: shrink while the template is still big AND keeps most of its detail
        // (fine patterns such as text or stripes blur away when shrunk too far).
        var c = 1
        val detail = std(t)
        while (max(tw, th) / c > COARSE_MAX_SIDE && min(tw, th) / (c * 2) >= COARSE_MIN_SIDE &&
            std(tl(c * 2)) >= 0.5f * detail
        ) c *= 2
        val tc = tl(c)

        // Coarse scan. Shrinking loses position phase, so a target that moved is also searched
        // on a grid shifted by half a cell. Candidates are kept in full (level 1) coordinates.
        val cands = ArrayList<IntArray>()
        val phases = if (c >= 2) intArrayOf(0, c / 2) else intArrayOf(0)
        for (ph in phases) {
            val ic = if (ph == 0) il(c) else shrink(crop(img, ph, ph), c)
            val map = scan(ic, tc)
            if (map.isEmpty()) continue
            val sup = max(1, min(tc.w, tc.h) / 4)
            val taken = Array(map.size) { BooleanArray(map[0].size) }
            var first = true
            repeat(CANDIDATES / phases.size) {
                var best = -2f; var bx = -1; var by = -1
                for (y in map.indices) for (x in map[y].indices) {
                    if (!taken[y][x] && map[y][x] > best) { best = map[y][x]; bx = x; by = y }
                }
                if (bx < 0 || (!first && best < threshold - 0.25f)) return@repeat
                first = false
                cands.add(intArrayOf(bx * c + ph, by * c + ph))
                for (y in max(0, by - sup)..min(map.size - 1, by + sup))
                    for (x in max(0, bx - sup)..min(map[0].size - 1, bx + sup)) taken[y][x] = true
            }
        }
        if (cands.isEmpty()) return null

        // Refine each candidate down the pyramid (from half the coarse level), then check color.
        var bestMatch: Match? = null
        for (cand in cands) {
            var level = max(1, c / 2)
            var px = cand[0] / level; var py = cand[1] / level
            var score: Float
            while (true) {
                val iL = il(level)
                val tL = tl(level)
                val (tm, tn) = stats(tL)
                var bs = -2f; var bx = px; var by = py
                for (dy in -3..3) for (dx in -3..3) {
                    val s = ncc(iL, tL, tm, tn, px + dx, py + dy)
                    if (s > bs) { bs = s; bx = px + dx; by = py + dy }
                }
                score = bs; px = bx; py = by
                if (level == 1) break
                level /= 2
                px *= 2; py *= 2
            }
            val fx = rx0 + px; val fy = ry0 + py
            val colorBad = colorDiff(f, fx, fy, tpl, tw, th, dim) > MAX_COLOR_DIFF
            if (diag != null && score > diag.best) {
                diag.best = score
                diag.colorRejected = colorBad && score >= threshold
            }
            if (score < threshold || colorBad) continue
            if (bestMatch == null || score > bestMatch.score) {
                bestMatch = Match((fx + tw / 2) * SCALE, (fy + th / 2) * SCALE, score)
            }
        }
        return bestMatch
    }
}
