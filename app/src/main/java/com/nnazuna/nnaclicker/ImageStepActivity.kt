package com.nnazuna.nnaclicker

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Choose the area of a screenshot to look for, and what to do when it's found. */
class ImageStepActivity : Activity() {

    companion object {
        const val EXTRA_REC = "rec"
        const val EXTRA_INDEX = "index"
    }

    private lateinit var storage: Storage
    private lateinit var k: Kit
    private val p get() = k.p
    private lateinit var rec: Recording
    private lateinit var step: ImageStep
    private var shot: Bitmap? = null
    private var crop: CropView? = null

    private lateinit var actionBox: LinearLayout
    private lateinit var searchBox: LinearLayout
    private lateinit var failBox: LinearLayout
    private lateinit var foundBox: LinearLayout
    private lateinit var timeoutValue: TextView
    private lateinit var matchValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        k = Kit(this)
        Ui.styleWindow(this, p)
        rec = storage.get(intent.getStringExtra(EXTRA_REC)) ?: run { finish(); return }
        step = rec.images.getOrNull(intent.getIntExtra(EXTRA_INDEX, -1)) ?: run { finish(); return }
        shot = storage.snapFile(step.snap)?.let { BitmapFactory.decodeFile(it.absolutePath) }
        build()
    }

    override fun onDestroy() {
        super.onDestroy()
        shot?.recycle()
    }

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(12f), k.dp(16f), k.dp(12f), k.dp(40f))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(k.icon(Icon.BACK, p.ink, 26f).apply {
            minimumWidth = k.dp(44f); minimumHeight = k.dp(44f)
            background = k.ripple(null, k.dp(22f).toFloat())
            setOnClickListener { finish() }
        })
        top.addView(k.text("Image step", 22f, 2).apply { setPadding(k.dp(6f), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(k.textButton("Save", p.play) { save() })
        root.addView(top)

        val bmp = shot
        if (bmp != null) {
            root.addView(k.text("Drag a box around what to look for. Pinch or double-tap to zoom, move with two fingers.", 14f, 0, p.muted).apply {
                setPadding(k.dp(8f), k.dp(4f), k.dp(8f), k.dp(8f))
            })
            val initial = if (step.w > 0 && step.h > 0) Rect(step.x, step.y, step.x + step.w, step.y + step.h)
            else Rect(bmp.width * 3 / 8, bmp.height * 3 / 8, bmp.width * 5 / 8, bmp.height / 2)
            val maxH = resources.displayMetrics.heightPixels * 55 / 100
            val cv = CropView(this, bmp, initial, p.play, maxH)
            crop = cv
            root.addView(cv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = k.dp(6f)
            })

            // Zoom controls + live preview of the selection
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(k.dp(4f), 0, k.dp(4f), 0)
            }
            fun zbtn(label: String, onClick: () -> Unit) = k.text(label, 18f, 2, p.ink).apply {
                gravity = Gravity.CENTER
                minWidth = k.dp(48f); minHeight = k.dp(44f)
                background = k.ripple(k.rounded(p.surface, 12f), k.dp(12f).toFloat())
                setOnClickListener { onClick() }
            }
            bar.addView(zbtn("−") { cv.zoomBy(1 / 1.6f) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = k.dp(6f) })
            bar.addView(zbtn("+") { cv.zoomBy(1.6f) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = k.dp(6f) })
            bar.addView(k.textButton("Zoom to box", p.play) { cv.zoomToSelection() })
            bar.addView(k.textButton("Fit", p.play) { cv.resetZoom() })
            bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            val preview = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = k.rounded(p.surface, 10f, p.line)
                setPadding(k.dp(4f), k.dp(4f), k.dp(4f), k.dp(4f))
            }
            bar.addView(preview, LinearLayout.LayoutParams(k.dp(84f), k.dp(56f)))
            root.addView(bar)

            fun showPreview(r: Rect) {
                if (r.width() < 2 || r.height() < 2) return
                preview.setImageBitmap(Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height()))
            }
            cv.onChange = { showPreview(it) }
            showPreview(cv.selection())
        } else {
            root.addView(k.text(
                if (step.ready) "The original screenshot is gone. Pick a new one to change the area."
                else "Pick a screenshot to choose the area.",
                14.5f, 0, p.muted,
            ).apply { setPadding(k.dp(8f), k.dp(8f), k.dp(8f), k.dp(8f)) })
            if (step.ready) {
                root.addView(ImageView(this).apply {
                    setImageBitmap(templateBitmap(step))
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_START
                    setPadding(k.dp(8f), 0, 0, k.dp(8f))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, k.dp(96f)))
            }
        }
        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        links.addView(k.textButton("Test on screen", p.play) { test() }.apply {
            setPadding(k.dp(8f), k.dp(10f), k.dp(12f), k.dp(10f))
        })
        links.addView(k.textButton("Use another screenshot", p.play) { pickScreenshot() }.apply {
            setPadding(k.dp(8f), k.dp(10f), k.dp(8f), k.dp(10f))
        })
        root.addView(links)

        // ---- Options ----
        root.addView(k.sectionHeader("When playing"))
        val g = k.group()
        actionBox = segRow(g, "Action")
        g.addView(k.divider())
        searchBox = segRow(g, "Search")
        g.addView(k.divider())
        k.valueRow("Wait up to", "How long to look. 0 = check once") {
            Ui.numberDialog(this, p, "Wait up to (seconds)", (step.timeoutMs / 1000).toString(), false,
                "0 checks the screen once. Use it with \"If found\" and \"If not found\" to choose what runs next.") { v ->
                v.toLongOrNull()?.let { step.timeoutMs = (it.coerceIn(0, 3600)) * 1000; refresh() }
            }
        }.let { (row, v) -> timeoutValue = v; g.addView(row) }
        g.addView(k.divider())
        foundBox = segRow(g, "If found")
        g.addView(k.divider())
        failBox = segRow(g, "If not found")
        g.addView(k.divider())
        k.valueRow("Match", "Lower finds more, but may find the wrong thing") {
            Ui.numberDialog(this, p, "Match (%)", (step.threshold * 100).toInt().toString(), false, "50–99, default 82") { v ->
                v.toIntOrNull()?.let { step.threshold = it.coerceIn(50, 99) / 100f; refresh() }
            }
        }.let { (row, v) -> matchValue = v; g.addView(row) }
        root.addView(g)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        })
        refresh()
    }

    /** A labeled row of segmented choices; returns the container for the choices. */
    private fun segRow(group: LinearLayout, label: String): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(16f), k.dp(12f), k.dp(16f), k.dp(12f))
        }
        col.addView(k.text(label, 16f, 1))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = k.rounded(p.bg, 10f)
            setPadding(k.dp(3f), k.dp(3f), k.dp(3f), k.dp(3f))
        }
        col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = k.dp(10f)
        })
        group.addView(col)
        return box
    }

    private fun fill(box: LinearLayout, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        box.removeAllViews()
        options.forEachIndexed { i, label ->
            val on = i == selected
            box.addView(k.text(label, 14.5f, if (on) 2 else 1, if (on) (if (p.dark) 0xFF0F1312.toInt() else Color.WHITE) else p.ink).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(k.dp(4f), k.dp(9f), k.dp(4f), k.dp(9f))
                background = if (on) k.rounded(p.play, 8f) else k.ripple(null, k.dp(8f).toFloat())
                setOnClickListener { onPick(i); refresh() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun refresh() {
        fill(actionBox, listOf("Wait for it", "Tap it", "Wait until gone"), step.action) { step.action = it }
        fill(searchBox, listOf("Near this spot", "Anywhere"), if (step.anywhere) 1 else 0) { step.anywhere = it == 1 }
        val gone = step.action == ImageStep.ACTION_GONE
        rowLabel(foundBox).text = L.t(if (gone) "If gone" else "If found")
        rowLabel(failBox).text = L.t(if (gone) "If still there" else "If not found")
        fill(foundBox, listOf("Next step", gotoText(step.foundGoto)), if (step.foundGoto != null) 1 else 0) { i ->
            if (i == 0) step.foundGoto = null
            else FlowUi.pickLabel(this, p, rec, step.foundGoto, "Then go to") { step.foundGoto = it; refresh() }
        }
        fill(failBox, listOf("Stop", "Next step", gotoText(step.failGoto.takeIf { step.onTimeout == ImageStep.ON_TIMEOUT_GOTO })),
            step.onTimeout) { i ->
            if (i < 2) step.onTimeout = i
            else FlowUi.pickLabel(this, p, rec, step.failGoto, "Then go to") {
                step.failGoto = it; step.onTimeout = ImageStep.ON_TIMEOUT_GOTO; refresh()
            }
        }
        timeoutValue.text = if (step.timeoutMs <= 0) L.t("Check once") else "${step.timeoutMs / 1000} s"
        matchValue.text = "${(step.threshold * 100).toInt()}%"
    }

    private fun rowLabel(box: LinearLayout) = (box.parent as LinearLayout).getChildAt(0) as TextView

    private fun gotoText(label: String?) = label?.let { "→ ${rec.labelName(it) ?: "?"}" } ?: "Go to…"

    private fun pickScreenshot() {
        SnapPicker.show(this, k, storage, "Use screenshot") { file ->
            saveOptionsOnly()
            step.snap = file.name
            step.w = 0; step.h = 0
            storage.save(rec)
            recreate()
        }
    }

    /** Persist the option choices without touching the area. */
    private fun saveOptionsOnly() {
        storage.save(rec)
    }

    /**
     * The step as it would be saved: current box and options. Returns null (with a message)
     * when there is nothing to look for yet. When [apply] is false, a copy is returned and
     * the real step is left unchanged.
     */
    private fun buildStep(apply: Boolean): ImageStep? {
        val target = if (apply) step else step.copy()
        val bmp = shot
        val cv = crop
        if (bmp != null && cv != null) {
            val r = cv.selection()
            if (r.width() < 16 || r.height() < 16) {
                LToast.makeText(this, "Select a bigger area", Toast.LENGTH_SHORT).show()
                return null
            }
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val t = ImageMatcher.cut(px, bmp.width, bmp.height, r.left, r.top, r.width(), r.height())
            target.x = r.left; target.y = r.top; target.w = r.width(); target.h = r.height()
            target.screenW = bmp.width; target.screenH = bmp.height
            target.tw = t.w; target.th = t.h; target.tpl = t.rgb
        } else if (!target.ready) {
            LToast.makeText(this, "Pick a screenshot first", Toast.LENGTH_SHORT).show()
            return null
        }
        return target
    }

    private fun save() {
        buildStep(apply = true) ?: return
        storage.save(rec)
        LToast.makeText(this, "Image step saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ---------- test on the live screen ----------

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var testResult: (() -> Unit)? = null

    /**
     * Go to the game, look for the current box on the real screen after a short countdown,
     * then come back here with the result.
     */
    private fun test() {
        val svc = RecorderService.instance
        if (svc == null) {
            LToast.makeText(this, "Turn on the accessibility service first", Toast.LENGTH_SHORT).show()
            return
        }
        val tmp = buildStep(apply = false) ?: return
        LToast.makeText(this, "Open the game. Checking the screen in 3 seconds…", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
        handler.postDelayed({
            svc.captureFrame { frame ->
                val diag = ImageMatcher.Diag()
                val m = frame?.let { ImageMatcher.find(it, tmp, 0f, diag) }
                testResult = { showTestResult(tmp, frame != null, m, diag) }
                startActivity(
                    android.content.Intent(this, ImageStepActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }, 3000)
    }

    override fun onResume() {
        super.onResume()
        testResult?.let { testResult = null; it() }
    }

    private fun showTestResult(tmp: ImageStep, captured: Boolean, m: ImageMatcher.Match?, d: ImageMatcher.Diag) {
        val need = (tmp.threshold * 100).toInt()
        val best = (d.best * 100).toInt().coerceAtLeast(0)
        val b = TBuilder(this, Ui.dialogTheme(p))
        when {
            !captured -> b.setTitle("Couldn't capture the screen")
                .setMessage("Connect Shizuku, or use Android 11 or later.")
                .setPositiveButton("OK", null)
            m != null -> b.setTitle("Found · ${(m.score * 100).toInt()}%")
                .setMessage(
                    if (tmp.action == ImageStep.ACTION_TAP) "It will be tapped at ${m.x}, ${m.y}."
                    else "Found at ${m.x}, ${m.y}."
                )
                .setPositiveButton("OK", null)
            d.colorRejected -> b.setTitle("Shape matches, colors don't")
                .setMessage("The shape matched $best%, but the colors are different. The button may look disabled on this screen.")
                .setPositiveButton("OK", null)
            best >= 50 -> b.setTitle("Not found")
                .setMessage("Closest match was $best%, below the $need% needed." +
                    if (!tmp.anywhere) " It might also be outside the search area." else "")
                .setPositiveButton("Set match to ${best - 3}%") { _, _ ->
                    step.threshold = (best - 3).coerceIn(50, 99) / 100f
                    refresh()
                }
                .setNeutralButton(if (tmp.anywhere) "OK" else "Search anywhere") { _, _ ->
                    if (!tmp.anywhere) { step.anywhere = true; refresh() }
                }
            else -> b.setTitle("Not found")
                .setMessage("Nothing similar is on the screen. Make sure the game shows the same screen as the screenshot.")
                .setPositiveButton("OK", null)
        }
        b.show()
    }
}

/** Template preview bitmap (half resolution). */
fun templateBitmap(s: ImageStep): Bitmap? {
    if (!s.ready) return null
    val px = IntArray(s.tpl.size) { s.tpl[it] or (0xFF shl 24) }
    return Bitmap.createBitmap(px, s.tw, s.th, Bitmap.Config.ARGB_8888)
}
