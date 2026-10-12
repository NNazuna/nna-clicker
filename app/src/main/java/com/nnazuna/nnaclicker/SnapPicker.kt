package com.nnazuna.nnaclicker

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pick a saved screenshot, with thumbnails and delete. */
object SnapPicker {

    fun show(a: Activity, k: Kit, storage: Storage, title: String, onPick: ((File) -> Unit)?) {
        val p = k.p
        val list = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(12f), k.dp(4f), k.dp(12f), k.dp(4f))
        }
        lateinit var dialog: AlertDialog
        val fmt = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)

        fun render() {
            list.removeAllViews()
            val snaps = storage.snaps()
            val usage = storage.snapUsage()
            if (snaps.isEmpty()) {
                list.addView(k.text(
                    "No screenshots yet. Open the game, then press the camera button on the floating panel.",
                    15f, 0, p.muted,
                ).apply { setPadding(k.dp(8f), k.dp(12f), k.dp(8f), k.dp(12f)) })
                return
            }
            for (f in snaps) {
                val used = usage[f.name] ?: 0
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(k.dp(4f), k.dp(6f), 0, k.dp(6f))
                    background = k.ripple(null, k.dp(12f).toFloat())
                    if (onPick != null) setOnClickListener {
                        dialog.dismiss()
                        onPick(f)
                    }
                }
                val thumb = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 })
                row.addView(ImageView(a).apply {
                    setImageBitmap(thumb)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = k.rounded(p.bg, 8f)
                    clipToOutline = true
                }, LinearLayout.LayoutParams(k.dp(96f), k.dp(56f)).apply { rightMargin = k.dp(12f) })
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                col.addView(k.text(fmt.format(Date(f.lastModified())), 15f, 1))
                col.addView(k.text(
                    if (used > 0) "Used by $used image step" + (if (used > 1) "s" else "") else "Not used",
                    13f, 0, p.muted,
                ).apply { setPadding(0, k.dp(3f), 0, 0) })
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(k.icon(Icon.TRASH, p.rec, 22f).apply {
                    minimumWidth = k.dp(44f); minimumHeight = k.dp(44f)
                    background = k.ripple(null, k.dp(22f).toFloat())
                    setOnClickListener {
                        if (used > 0) {
                            AlertDialog.Builder(a, Ui.dialogTheme(p))
                                .setTitle("Delete screenshot?")
                                .setMessage("Image steps that use it keep working, but their area can't be changed without a new screenshot.")
                                .setPositiveButton("Delete") { _, _ -> storage.deleteSnap(f.name); render() }
                                .setNegativeButton("Cancel", null)
                                .show()
                        } else {
                            storage.deleteSnap(f.name)
                            render()
                        }
                    }
                })
                list.addView(row)
            }
        }

        render()
        dialog = AlertDialog.Builder(a, Ui.dialogTheme(p))
            .setTitle(title)
            .setView(ScrollView(a).apply { addView(list) })
            .setNegativeButton("Close", null)
            .setNeutralButton("Delete unused", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val usage = storage.snapUsage()
                val unused = storage.snaps().filter { (usage[it.name] ?: 0) == 0 }
                if (unused.isEmpty()) return@setOnClickListener
                AlertDialog.Builder(a, Ui.dialogTheme(p))
                    .setTitle("Delete ${unused.size} unused screenshot" + (if (unused.size > 1) "s" else "") + "?")
                    .setPositiveButton("Delete") { _, _ -> unused.forEach { storage.deleteSnap(it.name) }; render() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        dialog.show()
    }
}
