package com.nnazuna.nnaclicker

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView

/**
 * Editor rekaman berbentuk garis waktu: langkah-langkah berurutan,
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        k = Kit(this)
        Ui.styleWindow(this, p)
        val r = storage.get(intent.getStringExtra(EXTRA_ID))
        if (r == null) { finish(); return }
        rec = r

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
        top.addView(k.text(rec.name, 22f, 2).apply {
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
        render()
    }

    private fun gapBefore(i: Int): Long {
        if (i == 0) return 0
        val prev = rec.gestures[i - 1]
        return (rec.gestures[i].at - (prev.at + prev.duration)).coerceAtLeast(0)
    }

    /** Ubah jeda sebelum langkah [i]; langkah sesudahnya ikut bergeser. */
    private fun setGap(i: Int, newGap: Long) {
        val delta = newGap.coerceAtLeast(0) - gapBefore(i)
        for (n in i until rec.gestures.size) rec.gestures[n].at += delta
    }

    private fun save() {
        storage.save(rec)
        render()
    }

    private fun render() {
        summary.text = "${rec.gestures.size} steps  ·  ${Ui.fmtSec(rec.length)}"
        listBox.removeAllViews()
        rec.gestures.forEachIndexed { i, g ->
            if (i > 0) listBox.addView(gapView(i))
            listBox.addView(stepView(i, g))
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

    private fun stepView(i: Int, g: RecGesture): View {
        val pts = g.strokes[0].points
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = k.rounded(p.surface, 14f)
            setPadding(0, k.dp(10f), k.dp(4f), k.dp(10f))
        }
        // Nomor langkah (memang urutan) di dalam lingkaran.
        row.addView(k.text("${i + 1}", 13f, 2, p.ink).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
            background = k.rounded(p.bg, 14f)
        }, LinearLayout.LayoutParams(k.dp(28f), k.dp(28f)).apply { leftMargin = k.dp(4f); rightMargin = k.dp(14f) })

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

        row.addView(k.icon(Icon.MORE, p.muted, 22f).apply {
            minimumWidth = k.dp(44f); minimumHeight = k.dp(44f)
            background = k.ripple(null, k.dp(22f).toFloat())
            setOnClickListener { stepMenu(it, i) }
        })
        return row
    }

    private fun stepMenu(anchor: View, i: Int) {
        PopupMenu(this, anchor, Gravity.END).apply {
            if (i > 0) menu.add(0, 1, 0, "Edit delay")
            menu.add(0, 2, 1, "Duplicate")
            menu.add(0, 3, 2, "Delete")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> askGap(i)
                    2 -> duplicate(i)
                    3 -> delete(i)
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
                for (i in 1 until rec.gestures.size) setGap(i, ms)
                save()
            }
        }

    /** Sisipkan salinan langkah [i] tepat setelahnya, dengan jeda yang sama seperti sebelum langkah itu. */
    private fun duplicate(i: Int) {
        val g = rec.gestures[i]
        val gap = if (i > 0) gapBefore(i) else 300L
        val copy = RecGesture(g.at + g.duration + gap, g.strokes)
        val shift = copy.duration + gap
        for (n in i + 1 until rec.gestures.size) rec.gestures[n].at += shift
        rec.gestures.add(i + 1, copy)
        save()
    }

    private fun delete(i: Int) {
        if (rec.gestures.size <= 1) {
            AlertDialog.Builder(this, Ui.dialogTheme(p))
                .setMessage("A recording needs at least one step.")
                .setPositiveButton("OK", null).show()
            return
        }
        rec.gestures.removeAt(i)
        save()
    }
}
