package com.nnazuna.nnaclicker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Times playback stopped because of a problem (usually an image that wasn't found), with the
 * screen at that moment and quick fixes for the image step.
 */
class RunLogActivity : Activity() {

    private lateinit var storage: Storage
    private lateinit var k: Kit
    private val p get() = k.p
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        k = Kit(this)
        Ui.styleWindow(this, p)

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
        top.addView(k.text("Run log", 22f, 2).apply { setPadding(k.dp(6f), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(k.textButton("Clear", p.play) { confirmClear() })
        root.addView(top)
        root.addView(k.text(
            "When playback stops because an image wasn't found, the screen at that moment is saved here. " +
                "Tap an entry to see it and fix the step.",
            14f, 0, p.muted,
        ).apply { setPadding(k.dp(50f), k.dp(2f), 0, k.dp(12f)) })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        listBox.removeAllViews()
        val entries = storage.runLog()
        if (entries.isEmpty()) {
            listBox.addView(k.group().apply {
                addView(k.text("Nothing here yet. Good news: no playback has stopped with a problem.", 14.5f, 0, p.muted).apply {
                    setPadding(k.dp(16f), k.dp(18f), k.dp(16f), k.dp(18f))
                })
            })
            return
        }
        val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.US)
        val g = k.group()
        entries.forEachIndexed { i, e ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(k.dp(12f), k.dp(10f), k.dp(12f), k.dp(10f))
                background = k.ripple(null)
                setOnClickListener { open(e) }
                setOnLongClickListener { confirmDelete(e); true }
            }
            val thumb = storage.logShot(e)?.let { f ->
                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 })
            }
            row.addView(ImageView(this).apply {
                if (thumb != null) setImageBitmap(thumb) else setImageDrawable(IconDrawable(Icon.IMAGE, p.muted, k.dp(22f)))
                scaleType = if (thumb != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER
                background = k.rounded(p.bg, 8f)
                clipToOutline = true
            }, LinearLayout.LayoutParams(k.dp(56f), k.dp(56f)).apply { rightMargin = k.dp(12f) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(k.text(e.recName, 16f, 1).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            col.addView(k.text(summary(e), 13f, 0, p.muted).apply { setPadding(0, k.dp(3f), 0, 0) })
            col.addView(k.text(fmt.format(Date(e.time)), 12.5f, 0, p.muted).apply { setPadding(0, k.dp(2f), 0, 0) })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            g.addView(row)
            if (i < entries.size - 1) g.addView(k.divider(80f))
        }
        listBox.addView(g)
    }

    private fun summary(e: RunLogEntry): String = when {
        e.stepNo > 0 && e.closest >= 0 -> L.t("Step {} · closest {}%, needs {}%", e.stepNo, e.closest, e.need)
        e.stepNo > 0 -> L.t("Step {} · image not found", e.stepNo)
        else -> L.t(e.message)
    }

    /** The image step this entry is about, if it still exists. */
    private fun findStep(e: RunLogEntry): Pair<Recording, ImageStep>? {
        val rec = storage.get(e.recId) ?: return null
        val st = rec.images.firstOrNull { it.at == e.stepAt } ?: return null
        return rec to st
    }

    private fun open(e: RunLogEntry) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(20f), k.dp(8f), k.dp(20f), 0)
        }
        storage.logShot(e)?.let { f ->
            val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })
            if (bmp != null) box.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = k.rounded(p.bg, 8f)
                clipToOutline = true
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = k.dp(10f)
            })
        }
        val found = findStep(e)
        found?.second?.let { st ->
            templateBitmap(st)?.let { t ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                r.addView(k.text("Looking for", 13.5f, 1, p.muted).apply { setPadding(0, 0, k.dp(10f), 0) })
                r.addView(ImageView(this).apply {
                    setImageBitmap(t)
                    scaleType = ImageView.ScaleType.FIT_START
                    adjustViewBounds = true
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, k.dp(44f)))
                box.addView(r)
            }
        }
        box.addView(k.text(advice(e, found != null), 14f, 0, p.ink).apply { setPadding(0, k.dp(10f), 0, k.dp(4f)) })

        val b = TBuilder(this, Ui.dialogTheme(p))
            .setTitle(L.t("{} · step {}", e.recName, e.stepNo))
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton(L.t("Close"), null)
        if (found != null) {
            b.setPositiveButton(L.t("Fix step")) { _, _ -> fixes(e, found.first, found.second) }
        }
        b.show()
    }

    private fun advice(e: RunLogEntry, stepExists: Boolean): String = when {
        !stepExists -> L.t("This step has been changed or deleted since then.")
        e.closest >= 50 -> L.t("The closest match was {}%, but {}% is needed. If the right thing is on screen, lower the match or use this screenshot.", e.closest, e.need)
        e.closest >= 0 -> L.t("Nothing similar was on the screen. The game may have shown a different screen, a popup, or loaded slowly.")
        else -> L.t(e.message)
    }

    private fun fixes(e: RunLogEntry, rec: Recording, st: ImageStep) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (storage.logShot(e) != null) options += L.t("Use this screenshot for the step") to {
            val name = storage.snapFromLog(e)
            if (name != null) {
                st.snap = name
                storage.save(rec)
                openStep(rec, st)
            }
        }
        if (e.closest >= 50) options += L.t("Set match to {}%", (e.closest - 3).coerceIn(50, 99)) to {
            st.threshold = (e.closest - 3).coerceIn(50, 99) / 100f
            storage.save(rec)
            toast(L.t("Match set to {}%", (st.threshold * 100).toInt()))
        }
        if (!st.anywhere) options += L.t("Search anywhere on the screen") to {
            st.anywhere = true
            storage.save(rec)
            toast(L.t("The step now searches the whole screen"))
        }
        if (st.timeoutMs in 1..59_999) options += L.t("Wait longer ({} s)", st.timeoutMs * 2 / 1000) to {
            st.timeoutMs = st.timeoutMs * 2
            storage.save(rec)
            toast(L.t("The step now waits up to {} s", st.timeoutMs / 1000))
        }
        options += L.t("Open the step") to { openStep(rec, st) }
        TBuilder(this, Ui.dialogTheme(p))
            .setTitle(L.t("Fix step {}", e.stepNo))
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton(L.t("Cancel"), null)
            .show()
    }

    private fun openStep(rec: Recording, st: ImageStep) {
        startActivity(
            Intent(this, ImageStepActivity::class.java)
                .putExtra(ImageStepActivity.EXTRA_REC, rec.id)
                .putExtra(ImageStepActivity.EXTRA_INDEX, rec.images.indexOf(st))
        )
    }

    private fun confirmDelete(e: RunLogEntry) {
        TBuilder(this, Ui.dialogTheme(p))
            .setTitle(L.t("Delete this entry?"))
            .setPositiveButton(L.t("Delete")) { _, _ -> storage.deleteRunLog(e); render() }
            .setNegativeButton(L.t("Cancel"), null)
            .show()
    }

    private fun confirmClear() {
        if (storage.runLog().isEmpty()) return
        TBuilder(this, Ui.dialogTheme(p))
            .setTitle(L.t("Clear the run log?"))
            .setMessage(L.t("All entries and their screenshots will be deleted."))
            .setPositiveButton(L.t("Clear")) { _, _ -> storage.clearRunLog(); render() }
            .setNegativeButton(L.t("Cancel"), null)
            .show()
    }

    private fun toast(s: String) = LToast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
}
