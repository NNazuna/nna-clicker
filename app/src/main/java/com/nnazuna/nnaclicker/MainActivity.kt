package com.nnazuna.nnaclicker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {

    companion object {
        private const val REQ_EXPORT = 11
        private const val REQ_IMPORT = 12
        private val SPEEDS = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f)
    }

    private lateinit var storage: Storage
    private lateinit var k: Kit
    private val p get() = k.p

    // status
    private lateinit var a11yDot: View
    private lateinit var a11ySub: TextView
    private lateinit var a11yAction: TextView
    private lateinit var shzDot: View
    private lateinit var shzSub: TextView
    private lateinit var shzSwitch: Switch
    private lateinit var shzAction: TextView
    private lateinit var shzDiag: TextView
    private lateinit var panelButton: TextView

    // daftar & pengaturan
    private lateinit var listGroup: LinearLayout
    private lateinit var loopsValue: TextView
    private lateinit var speedBox: LinearLayout
    private lateinit var intervalValue: TextView
    private lateinit var startValue: TextView
    private lateinit var limitValue: TextView
    private lateinit var jitterPxValue: TextView
    private lateinit var jitterPctValue: TextView
    private lateinit var dimLevelRow: View
    private lateinit var dimLevelValue: TextView

    private var exportId: String? = null
    private var dimDivider: View? = null
    private var suppressSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        k = Kit(this)
        Ui.styleWindow(this, p)
        ShizukuBridge.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(16f), k.dp(28f), k.dp(16f), k.dp(40f))
        }

        // ---- Judul ----
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(k.dp(4f), 0, k.dp(4f), 0)
        }
        titleRow.addView(k.text("NNA Clicker", 32f, 2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(k.textButton("Setup", p.play) { startActivity(Intent(this, SetupActivity::class.java)) })
        titleRow.addView(k.text("v${BuildConfig.VERSION_NAME}", 14f, 1, p.muted).apply { setPadding(k.dp(4f), 0, 0, k.dp(5f)) })
        root.addView(titleRow)
        root.addView(View(this), LinearLayout.LayoutParams(1, k.dp(10f)))

        // ---- Kesiapan ----
        val status = k.group()
        status.addView(statusRow("Accessibility service").also {
            a11yDot = it.dot; a11ySub = it.sub; a11yAction = it.action
            a11yAction.text = "Open"
            a11yAction.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }.view)
        status.addView(k.divider(40f))
        val shz = statusRow("Shizuku").also {
            shzDot = it.dot; shzSub = it.sub; shzAction = it.action
            shzAction.setOnClickListener {
                if (!ShizukuBridge.hasPermission()) ShizukuBridge.requestPermission() else ShizukuBridge.reconnect()
                refresh()
            }
        }
        shzSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, checked ->
                if (suppressSwitch) return@setOnCheckedChangeListener
                storage.useShizuku = checked
                if (checked) {
                    if (ShizukuBridge.hasPermission()) ShizukuBridge.connect() else ShizukuBridge.requestPermission()
                } else {
                    ShizukuBridge.disconnect()
                }
                refresh()
            }
        }
        (shz.view as LinearLayout).addView(shzSwitch)
        status.addView(shz.view)
        shzDiag = k.text("", 12.5f, 0, p.muted).apply {
            setPadding(k.dp(40f), 0, k.dp(16f), k.dp(12f))
            setTextIsSelectable(true)
        }
        status.addView(shzDiag)
        root.addView(status)

        panelButton = k.pillButton("Show panel", p.play) {
            val s = RecorderService.instance ?: run {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return@pillButton
            }
            if (s.isPanelShown) s.hidePanel() else {
                s.showPanel()
                moveTaskToBack(true)
            }
            refresh()
        }
        root.addView(panelButton)

        // ---- Rekaman ----
        root.addView(k.sectionHeader("Recordings", "Import") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQ_IMPORT,
            )
        })
        listGroup = k.group()
        root.addView(listGroup)
        root.addView(k.textButton("Screenshots", p.play) {
            SnapPicker.show(this, k, storage, "Screenshots", null)
        }.apply { setPadding(k.dp(4f), k.dp(10f), k.dp(4f), k.dp(4f)) })

        // ---- Pemutaran ----
        root.addView(k.sectionHeader("Playback"))
        val play = k.group()

        // Ulangi: stepper
        val loopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = k.dp(56f)
            setPadding(k.dp(16f), k.dp(6f), k.dp(8f), k.dp(6f))
        }
        val loopLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        loopLeft.addView(k.text("Repeat", 16f, 1))
        loopRow.addView(loopLeft, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        loopRow.addView(stepButton("−") { storage.loops = (storage.loops - 1).coerceAtLeast(0); refreshSettings() })
        loopsValue = k.text("", 18f, 2).apply {
            gravity = Gravity.CENTER
            minWidth = k.dp(44f)
            fontFeatureSettings = "tnum"
            setOnClickListener {
                Ui.numberDialog(this@MainActivity, p, "Repeat", storage.loops.toString(), false,
                    "0 = infinite") { v -> v.toIntOrNull()?.let { storage.loops = it }; refreshSettings() }
            }
        }
        loopRow.addView(loopsValue)
        loopRow.addView(stepButton("+") { storage.loops = storage.loops + 1; refreshSettings() })
        play.addView(loopRow)
        play.addView(k.divider())

        // Kecepatan: segmen
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(16f), k.dp(14f), k.dp(16f), k.dp(14f))
        }
        speedRow.addView(k.text("Speed", 16f, 1))
        speedBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = k.rounded(p.bg, 10f)
            setPadding(k.dp(3f), k.dp(3f), k.dp(3f), k.dp(3f))
        }
        speedRow.addView(speedBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = k.dp(10f)
        })
        play.addView(speedRow)
        play.addView(k.divider())

        intervalValue = addRow(play, "Delay between loops") {
            Ui.numberDialog(this, p, "Delay between loops (seconds)", (storage.intervalMs / 1000.0).toString(), true) { v ->
                v.toDoubleOrNull()?.let { storage.intervalMs = (it * 1000).toLong() }; refreshSettings()
            }
        }
        startValue = addRow(play, "Start delay") {
            Ui.numberDialog(this, p, "Start delay (seconds)", (storage.startDelayMs / 1000.0).toString(), true) { v ->
                v.toDoubleOrNull()?.let { storage.startDelayMs = (it * 1000).toLong() }; refreshSettings()
            }
        }
        limitValue = addRow(play, "Auto stop") {
            Ui.numberDialog(this, p, "Auto stop after (minutes)", storage.maxMinutes.toString(), false, "0 = off") { v ->
                v.toIntOrNull()?.let { storage.maxMinutes = it }; refreshSettings()
            }
        }
        jitterPxValue = addRow(play, "Randomize position") {
            Ui.numberDialog(this, p, "Randomize position (± px)", trim(storage.jitterPx), true, "0 = off") { v ->
                v.toFloatOrNull()?.let { storage.jitterPx = it }; refreshSettings()
            }
        }
        jitterPctValue = addRow(play, "Randomize timing") {
            Ui.numberDialog(this, p, "Randomize timing (± %)", trim(storage.jitterPct), true, "0 = off") { v ->
                v.toFloatOrNull()?.let { storage.jitterPct = it }; refreshSettings()
            }
        }
        val (dimRow, _) = k.switchRow("Dim screen while playing", storage.dimScreen) { on ->
            storage.dimScreen = on
            refreshSettings()
        }
        play.addView(dimRow)
        k.valueRow("Dim level") {
            Ui.numberDialog(this, p, "Dim level (%)", storage.dimLevel.toString(), false, "10–95") { v ->
                v.toIntOrNull()?.let { storage.dimLevel = it }; refreshSettings()
            }
        }.let { (row, value) ->
            dimLevelRow = row
            dimLevelValue = value
            play.addView(k.divider().also { div -> dimDivider = div })
            play.addView(row)
        }
        root.addView(play)


        // First launch: walk through the switches before anything else.
        if (savedInstanceState == null && !storage.setupSeen && RecorderService.instance == null) {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            isVerticalScrollBarEnabled = false
            addView(root)
        })
    }

    // ---------- komponen ----------

    private class StatusRow(val view: View, val dot: View, val sub: TextView, val action: TextView)

    private fun statusRow(title: String): StatusRow {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = k.dp(64f)
            setPadding(k.dp(16f), k.dp(12f), k.dp(12f), k.dp(12f))
        }
        val dot = View(this).apply { background = k.rounded(p.line, 5f) }
        row.addView(dot, LinearLayout.LayoutParams(k.dp(10f), k.dp(10f)).apply { rightMargin = k.dp(14f) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(k.text(title, 16f, 1))
        val sub = k.text("", 13.5f, 0, p.muted).apply { setPadding(0, k.dp(3f), 0, 0) }
        col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val action = k.textButton("", p.play) {}
        row.addView(action)
        return StatusRow(row, dot, sub, action)
    }

    private fun setDot(v: View, color: Int) {
        v.background = k.rounded(color, 5f)
    }

    private fun stepButton(label: String, onClick: () -> Unit) = k.text(label, 22f, 1, p.ink).apply {
        gravity = Gravity.CENTER
        minWidth = k.dp(44f)
        minHeight = k.dp(44f)
        background = k.ripple(k.rounded(p.bg, 22f), k.dp(22f).toFloat())
        setOnClickListener { onClick() }
    }

    private fun addRow(group: LinearLayout, label: String, sub: String? = null, last: Boolean = false, onClick: () -> Unit): TextView {
        val (row, value) = k.valueRow(label, sub, onClick)
        group.addView(row)
        if (!last) group.addView(k.divider())
        return value
    }

    private fun trim(f: Float): String = if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()

    // ---------- siklus hidup ----------

    override fun onResume() {
        super.onResume()
        RecorderService.onChanged = { runOnUiThread { refresh() } }
        ShizukuBridge.onChanged = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        RecorderService.onChanged = null
        ShizukuBridge.onChanged = null
    }

    private fun refresh() {
        // Aksesibilitas
        val svc = RecorderService.instance
        if (svc != null) {
            setDot(a11yDot, p.play)
            a11ySub.text = "On"
            a11yAction.visibility = View.GONE
        } else {
            setDot(a11yDot, p.rec)
            a11ySub.text = "Off"
            a11yAction.visibility = View.VISIBLE
        }
        panelButton.text = when {
            svc == null -> "Turn on accessibility service"
            svc.isPanelShown -> "Hide panel"
            else -> "Show panel"
        }
        panelButton.background = k.ripple(k.rounded(if (svc?.isPanelShown == true) p.muted else p.play, 26f), k.dp(26f).toFloat())

        // Shizuku
        suppressSwitch = true
        shzSwitch.isChecked = storage.useShizuku
        suppressSwitch = false
        val running = ShizukuBridge.isRunning()
        val perm = ShizukuBridge.hasPermission()
        val connected = ShizukuBridge.isConnected
        val (color, msg) = when {
            !storage.useShizuku -> Pair(p.line, "Off")
            !running -> Pair(p.warn, "Shizuku not running")
            !perm -> Pair(p.warn, "Permission needed")
            connected -> Pair(p.play, "Connected")
            ShizukuBridge.connecting -> Pair(p.warn, "Connecting…")
            else -> Pair(p.rec, "Connection failed")
        }
        setDot(shzDot, color)
        shzSub.text = msg
        shzAction.visibility = if (storage.useShizuku && running && (!perm || (!connected && !ShizukuBridge.connecting))) View.VISIBLE else View.GONE
        shzAction.text = if (!perm) "Allow" else "Retry"
        val diag = if (storage.useShizuku) ShizukuBridge.diagnostics() else ""
        shzDiag.text = diag
        shzDiag.visibility = if (diag.isEmpty()) View.GONE else View.VISIBLE

        refreshSettings()
        renderList()
    }

    private fun refreshSettings() {
        loopsValue.text = if (storage.loops == 0) "∞" else storage.loops.toString()
        intervalValue.text = Ui.fmtSec(storage.intervalMs)
        startValue.text = Ui.fmtSec(storage.startDelayMs)
        limitValue.text = if (storage.maxMinutes == 0) "Off" else "${storage.maxMinutes} min"
        jitterPxValue.text = if (storage.jitterPx == 0f) "Off" else "± ${trim(storage.jitterPx)} px"
        dimLevelValue.text = "${storage.dimLevel}%"
        val dimVis = if (storage.dimScreen) View.VISIBLE else View.GONE
        dimLevelRow.visibility = dimVis
        dimDivider?.visibility = dimVis
        jitterPctValue.text = if (storage.jitterPct == 0f) "Off" else "± ${trim(storage.jitterPct)} %"

        speedBox.removeAllViews()
        for (sp in SPEEDS) {
            val selected = kotlin.math.abs(storage.speed - sp) < 0.01f
            val label = (if (sp == sp.toInt().toFloat()) sp.toInt().toString() else sp.toString().replace('.', ',')) + "×"
            val t = k.text(label, 15f, if (selected) 2 else 1, if (selected) p.onAccent else p.ink).apply {
                gravity = Gravity.CENTER
                setPadding(0, k.dp(9f), 0, k.dp(9f))
                background = if (selected) k.rounded(p.ink.let { if (p.dark) p.play else it }, 8f) else k.ripple(null, k.dp(8f).toFloat())
                setOnClickListener { storage.speed = sp; refreshSettings() }
            }
            speedBox.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun renderList() {
        listGroup.removeAllViews()
        val recs = storage.list()
        if (recs.isEmpty()) {
            listGroup.addView(k.text(
                "No recordings yet",
                14.5f, 0, p.muted,
            ).apply { setPadding(k.dp(16f), k.dp(18f), k.dp(16f), k.dp(18f)) })
            return
        }
        val selected = storage.selected()?.id
        val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.US)
        recs.forEachIndexed { i, r ->
            val isSel = r.id == selected
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = k.dp(64f)
                background = k.ripple(null)
                setOnClickListener { storage.selectedId = r.id; renderList() }
                setOnLongClickListener { menu(it, r); true }
            }
            // Penanda rekaman terpilih: garis merah di kiri.
            row.addView(View(this).apply {
                setBackgroundColor(if (isSel) p.rec else 0)
            }, LinearLayout.LayoutParams(k.dp(3f), k.dp(40f)).apply { rightMargin = k.dp(13f) })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, k.dp(11f), 0, k.dp(11f))
            }
            col.addView(k.text(r.name, 16f, if (isSel) 2 else 1))
                        col.addView(k.text(
                "${r.gestures.size} actions" + (if (r.images.isNotEmpty()) ", ${r.images.size} image" else "") + "  ·  ${Ui.fmtSec(r.length)}  ·  ${fmt.format(Date(r.created))}",
                13f, 0, p.muted,
            ).apply { setPadding(0, k.dp(3f), 0, 0); fontFeatureSettings = "tnum" })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(k.icon(Icon.MORE, p.muted, 22f).apply {
                minimumWidth = k.dp(48f); minimumHeight = k.dp(48f)
                background = k.ripple(null, k.dp(24f).toFloat())
                setOnClickListener { menu(it, r) }
            })
            listGroup.addView(row)
            if (i < recs.size - 1) listGroup.addView(k.divider(16f))
        }
    }

    private fun menu(anchor: View, r: Recording) {
        PopupMenu(this, anchor, Gravity.END).apply {
            menu.add(0, 1, 0, "Select")
            menu.add(0, 2, 1, "Edit")
            menu.add(0, 3, 2, "Rename")
            menu.add(0, 4, 3, "Export")
            menu.add(0, 5, 4, "Delete")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { storage.selectedId = r.id; renderList() }
                    2 -> startActivity(Intent(this@MainActivity, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, r.id))
                    3 -> rename(r)
                    4 -> export(r)
                    5 -> confirmDelete(r)
                }
                true
            }
            show()
        }
    }

    private fun rename(r: Recording) {
        val input = android.widget.EditText(this).apply {
            setText(r.name); setSelection(r.name.length); typeface = Ui.font(this@MainActivity, 1)
        }
        val box = k.frame(input, 22f, 4f)
        AlertDialog.Builder(this, Ui.dialogTheme(p))
            .setTitle("Rename")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) { r.name = n; storage.save(r); renderList() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(r: Recording) {
        AlertDialog.Builder(this, Ui.dialogTheme(p))
            .setTitle("Delete \"${r.name}\"?")
            .setPositiveButton("Delete") { _, _ -> storage.delete(r.id); renderList() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- ekspor / impor ----------

    private fun export(r: Recording) {
        exportId = r.id
        val safe = r.name.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "$safe.json"),
            REQ_EXPORT,
        )
    }

    @Deprecated("Activity API lama, cukup untuk aplikasi tanpa AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                REQ_EXPORT -> {
                    val r = storage.get(exportId) ?: return
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(r.toJson().toString(2).toByteArray()) }
                    toast("Exported: ${r.name}")
                }
                REQ_IMPORT -> {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
                    val r = Recording.fromJson(JSONObject(text))
                    if (r.gestures.isEmpty()) { toast("No actions in this file"); return }
                    val copy = Recording(UUID.randomUUID().toString(), r.name, System.currentTimeMillis(), r.gestures)
                    storage.save(copy)
                    storage.selectedId = copy.id
                    toast("Imported: ${copy.name}")
                    renderList()
                }
            }
        } catch (e: Exception) {
            toast("Couldn't read file: ${e.message}")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
