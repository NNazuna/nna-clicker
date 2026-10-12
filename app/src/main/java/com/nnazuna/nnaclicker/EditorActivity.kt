package com.nnazuna.nnaclicker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Editor rekaman berbentuk garis waktu: langkah-langkah berurutan (gerakan dan langkah gambar),
 * dengan jeda di antaranya yang bisa diketuk untuk diubah.
 */
class EditorActivity : Activity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private lateinit var storage: Storage
    private lateinit var rec: Recording
    private lateinit var k: Kit
    private val p get() = k.p
    private lateinit var listBox: LinearLayout
    private lateinit var summary: TextView
    private var steps: List<Step> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        k = Kit(this)
        Ui.styleWindow(this, p)
        if (storage.get(intent.getStringExtra(EXTRA_ID)) == null) { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(12f), k.dp(16f), k.dp(16f), k.dp(40f))
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
        top.addView(k.text("", 22f, 2).apply {
            tag = "title"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(k.dp(6f), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top)

        summary = k.text("", 14f, 0, p.muted).apply {
            setPadding(k.dp(50f), k.dp(2f), 0, k.dp(6f))
            fontFeatureSettings = "tnum"
        }
        root.addView(summary)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(k.dp(38f), 0, 0, k.dp(8f))
        }
        actions.addView(k.textButton("Set all delays", p.play) { setAllGaps() })
        actions.addView(k.textButton("Add image step", p.play) { addImageStep() })
        root.addView(actions)

        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(4f), 0, 0, 0)
        }
        root.addView(listBox)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        // Reload: the image step screen saves the recording on its own.
        rec = storage.get(intent.getStringExtra(EXTRA_ID)) ?: run { finish(); return }
        (window.decorView.findViewWithTag<TextView>("title"))?.text = rec.name
        render()
    }

    private fun gapBefore(i: Int): Long {
        if (i == 0) return 0
        val prev = steps[i - 1]
        return (steps[i].at - (prev.at + prev.duration)).coerceAtLeast(0)
    }

    /** Ubah jeda sebelum langkah [i]; langkah sesudahnya ikut bergeser. */
    private fun setGap(i: Int, newGap: Long) {
        val delta = newGap.coerceAtLeast(0) - gapBefore(i)
        for (n in i until steps.size) steps[n].at += delta
    }

    private fun save() {
        storage.save(rec)
        render()
    }

    private fun render() {
        steps = rec.steps()
        val imgs = rec.images.size
        summary.text = "${steps.size} steps" + (if (imgs > 0) " ($imgs image)" else "") + "  ·  ${Ui.fmtSec(rec.length)}"
        listBox.removeAllViews()
        steps.forEachIndexed { i, s ->
            if (i > 0) listBox.addView(gapView(i))
            listBox.addView(if (s is ImageStep) imageView(i, s) else gestureView(i, s as RecGesture))
        }
    }

    /** Penghubung antar langkah: garis vertikal + jeda yang bisa diketuk. */
    private fun gapView(i: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = k.dp(40f)
            background = k.ripple(null, k.dp(10f).toFloat())
            setOnClickListener { askGap(i) }
        }
        row.addView(View(this).apply { setBackgroundColor(p.line) },
            LinearLayout.LayoutParams(k.dp(2f), k.dp(40f)).apply { leftMargin = k.dp(17f); rightMargin = k.dp(20f) })
        row.addView(k.text("wait ${gapBefore(i)} ms", 13.5f, 1, p.play).apply {
            fontFeatureSettings = "tnum"
            setPadding(k.dp(10f), k.dp(4f), k.dp(10f), k.dp(4f))
            background = k.rounded(p.surface, 12f)
        })
        return row
    }

    private fun number(i: Int) = k.text("${i + 1}", 13f, 2, p.ink).apply {
        gravity = Gravity.CENTER
        fontFeatureSettings = "tnum"
        background = k.rounded(p.bg, 14f)
    }

    private fun moreButton(i: Int) = k.icon(Icon.MORE, p.muted, 22f).apply {
        minimumWidth = k.dp(44f); minimumHeight = k.dp(44f)
        background = k.ripple(null, k.dp(22f).toFloat())
        setOnClickListener { stepMenu(it, i) }
    }

    private fun gestureView(i: Int, g: RecGesture): View {
        val pts = g.strokes[0].points
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = k.rounded(p.surface, 14f)
            setPadding(0, k.dp(10f), k.dp(4f), k.dp(10f))
        }
        row.addView(number(i), LinearLayout.LayoutParams(k.dp(28f), k.dp(28f)).apply { leftMargin = k.dp(4f); rightMargin = k.dp(14f) })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(k.text(g.kind, 16f, 2))
        val where = if (g.kind == "Swipe") {
            "${pts.first().x.toInt()}, ${pts.first().y.toInt()}  →  ${pts.last().x.toInt()}, ${pts.last().y.toInt()}"
        } else "${pts.first().x.toInt()}, ${pts.first().y.toInt()}"
        col.addView(k.text("$where  ·  ${g.duration} ms", 13f, 0, p.muted).apply {
            setPadding(0, k.dp(3f), 0, 0)
            fontFeatureSettings = "tnum"
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(moreButton(i))
        return row
    }

    private fun imageView(i: Int, s: ImageStep): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = k.ripple(k.rounded(p.surface, 14f, if (s.ready) null else p.warn), k.dp(14f).toFloat())
            setPadding(0, k.dp(10f), k.dp(4f), k.dp(10f))
            setOnClickListener { openImage(s) }
        }
        row.addView(number(i), LinearLayout.LayoutParams(k.dp(28f), k.dp(28f)).apply { leftMargin = k.dp(4f); rightMargin = k.dp(12f) })

        val thumb = templateBitmap(s)
        if (thumb != null) {
            row.addView(ImageView(this).apply {
                setImageBitmap(thumb)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = k.rounded(p.bg, 8f)
                clipToOutline = true
            }, LinearLayout.LayoutParams(k.dp(52f), k.dp(40f)).apply { rightMargin = k.dp(12f) })
        } else {
            row.addView(k.icon(Icon.IMAGE, p.warn, 22f), LinearLayout.LayoutParams(k.dp(52f), k.dp(40f)).apply { rightMargin = k.dp(12f) })
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(k.text(s.title, 16f, 2))
        val sub = if (!s.ready) "Tap to choose the area" else
            "${if (s.anywhere) "anywhere" else "near its spot"}  ·  up to ${s.timeoutMs / 1000} s  ·  ${(s.threshold * 100).toInt()}%"
        col.addView(k.text(sub, 13f, if (s.ready) 0 else 1, if (s.ready) p.muted else p.warn).apply {
            setPadding(0, k.dp(3f), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(moreButton(i))
        return row
    }

    private fun openImage(s: ImageStep) {
        startActivity(
            Intent(this, ImageStepActivity::class.java)
                .putExtra(ImageStepActivity.EXTRA_REC, rec.id)
                .putExtra(ImageStepActivity.EXTRA_INDEX, rec.images.indexOf(s))
        )
    }

    private fun stepMenu(anchor: View, i: Int) {
        PopupMenu(this, anchor, Gravity.END).apply {
            if (steps[i] is ImageStep) menu.add(0, 4, 0, "Edit image")
            if (i > 0) menu.add(0, 1, 1, "Edit delay")
            menu.add(0, 2, 2, "Duplicate")
            menu.add(0, 3, 3, "Delete")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> askGap(i)
                    2 -> duplicate(i)
                    3 -> delete(i)
                    4 -> openImage(steps[i] as ImageStep)
                }
                true
            }
            show()
        }
    }

    private fun askGap(i: Int) =
        Ui.numberDialog(this, p, "Delay before step ${i + 1} (ms)", gapBefore(i).toString(), false) { v ->
            v.toLongOrNull()?.let { setGap(i, it); save() }
        }

    private fun setAllGaps() =
        Ui.numberDialog(this, p, "Set all delays (ms)", "500", false) { v ->
            v.toLongOrNull()?.let { ms ->
                for (i in 1 until steps.size) setGap(i, ms)
                save()
            }
        }

    /** Sisipkan salinan langkah [i] tepat setelahnya, dengan jeda yang sama seperti sebelum langkah itu. */
    private fun duplicate(i: Int) {
        val s = steps[i]
        val gap = if (i > 0) gapBefore(i) else 300L
        val newAt = s.at + s.duration + gap
        val shift = s.duration + gap
        for (n in i + 1 until steps.size) steps[n].at += shift
        when (s) {
            is RecGesture -> rec.gestures.add(RecGesture(newAt, s.strokes))
            is ImageStep -> rec.images.add(s.copy(newAt))
        }
        save()
    }

    private fun delete(i: Int) {
        if (steps.size <= 1) {
            AlertDialog.Builder(this, Ui.dialogTheme(p))
                .setMessage("A recording needs at least one step.")
                .setPositiveButton("OK", null).show()
            return
        }
        when (val s = steps[i]) {
            is RecGesture -> rec.gestures.remove(s)
            is ImageStep -> rec.images.remove(s)
        }
        save()
    }

    /** Append an image step from a saved screenshot, then open it to choose the area. */
    private fun addImageStep() {
        val snaps = storage.snaps()
        if (snaps.isEmpty()) {
            AlertDialog.Builder(this, Ui.dialogTheme(p))
                .setTitle("No screenshots yet")
                .setMessage("Open the game, then press the camera button on the floating panel. The screenshot will show up here.")
                .setPositiveButton("OK", null).show()
            return
        }
        val fmt = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
        val labels = snaps.map { "Screenshot · " + fmt.format(Date(it.lastModified())) }.toTypedArray()
        AlertDialog.Builder(this, Ui.dialogTheme(p))
            .setTitle("Add image step from")
            .setItems(labels) { _, which ->
                val end = steps.maxOfOrNull { it.at + it.duration } ?: 0L
                val s = ImageStep(end + 500, snaps[which].name)
                rec.images.add(s)
                storage.save(rec)
                openImage(s)
            }
            .show()
    }
}
