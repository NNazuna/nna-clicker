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
        actions.addView(k.textButton("Add step", p.play) { addStep(-1) })
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
        // Keep the on-screen order even when two steps end up at the same time.
        for (i in 1 until steps.size) {
            if (steps[i].at <= steps[i - 1].at) steps[i].at = steps[i - 1].at + 1
        }
        storage.save(rec)
        render()
    }

    /**
     * Move a step to another position. The delays stay where they were in the sequence,
     * so the rhythm of the recording doesn't change.
     */
    private fun move(from: Int, to: Int) {
        if (to !in steps.indices || from == to) return
        val gaps = steps.indices.map { gapBefore(it) }
        val start = steps.first().at
        val order = steps.toMutableList()
        order.add(to, order.removeAt(from))
        var prevEnd = 0L
        order.forEachIndexed { i, st ->
            st.at = if (i == 0) start else prevEnd + gaps[i]
            prevEnd = st.at + st.duration
        }
        steps = order
        save()
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
            val g = steps[i] as? RecGesture
            if (g != null && g.strokes.size == 1) menu.add(0, 8, 0, "Edit position")
            menu.add(0, 9, 7, "Add step after")
            if (i > 0) menu.add(0, 5, 1, "Move up")
            if (i < steps.size - 1) menu.add(0, 6, 2, "Move down")
            if (steps.size > 2) menu.add(0, 7, 3, "Move to…")
            if (i > 0) menu.add(0, 1, 4, "Edit delay")
            menu.add(0, 2, 5, "Duplicate")
            menu.add(0, 3, 6, "Delete")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> askGap(i)
                    2 -> duplicate(i)
                    3 -> delete(i)
                    4 -> openImage(steps[i] as ImageStep)
                    8 -> editGesture(steps[i] as RecGesture)
                    9 -> addStep(i)
                    5 -> move(i, i - 1)
                    6 -> move(i, i + 1)
                    7 -> Ui.numberDialog(this@EditorActivity, p, "Move step ${i + 1} to", "${i + 1}", false, "1–${steps.size}") { v ->
                        v.toIntOrNull()?.let { n -> move(i, (n - 1).coerceIn(0, steps.size - 1)) }
                    }
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

    /** Add a tap, swipe, or image step after step [after] (-1 = at the end). */
    private fun addStep(after: Int) {
        val anchor = steps.getOrNull(after)
        val afterEnd = anchor?.let { it.at + it.duration } ?: -1L
        val afterStart = anchor?.at ?: -1L
        AlertDialog.Builder(this, Ui.dialogTheme(p))
            .setTitle(if (anchor == null) "Add step" else "Add step after ${after + 1}")
            .setItems(arrayOf("Tap", "Swipe", "Image (from a screenshot)")) { _, which ->
                when (which) {
                    0, 1 -> startActivity(
                        Intent(this, PointActivity::class.java)
                            .putExtra(PointActivity.EXTRA_REC, rec.id)
                            .putExtra(PointActivity.EXTRA_MODE, if (which == 0) PointActivity.MODE_TAP else PointActivity.MODE_SWIPE)
                            .putExtra(PointActivity.EXTRA_AFTER, afterEnd)
                            .putExtra(PointActivity.EXTRA_AFTER_START, afterStart)
                    )
                    else -> addImageStep(afterEnd, afterStart)
                }
            }
            .show()
    }

    private fun editGesture(g: RecGesture) {
        startActivity(
            Intent(this, PointActivity::class.java)
                .putExtra(PointActivity.EXTRA_REC, rec.id)
                .putExtra(PointActivity.EXTRA_GESTURE, rec.gestures.indexOf(g))
        )
    }

    /** Add an image step from a saved screenshot, then open it to choose the area. */
    private fun addImageStep(afterEnd: Long, afterStart: Long) {
        SnapPicker.show(this, k, storage, "Add image step from") { file ->
            val gap = PointActivity.GAP
            val at = if (afterEnd >= 0) {
                steps.filter { it.at > afterEnd || (it.at == afterEnd && it.at > afterStart) }.forEach { it.at += gap }
                afterEnd + gap
            } else (steps.maxOfOrNull { it.at + it.duration } ?: -gap) + gap
            val s = ImageStep(at, file.name)
            rec.images.add(s)
            storage.save(rec)
            openImage(s)
        }
    }
}
