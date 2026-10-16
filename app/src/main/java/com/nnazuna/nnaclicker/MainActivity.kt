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

    /** Playback options being shown: the selected recording's own, or the global ones. */
    private var cfg = PlaySettings()
    private var cfgRec: Recording? = null
    private lateinit var scopeTitle: TextView
    private lateinit var scopeSub: TextView
    private lateinit var scopeSwitch: Switch
    private var suppressScope = false
    private lateinit var langValue: TextView

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
            a11yAction.text = L.t("Open")
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
        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        links.addView(k.textButton("Auto clicker", p.play) { newAutoClicker() }.apply {
            setPadding(k.dp(4f), k.dp(10f), k.dp(14f), k.dp(4f))
        })
        links.addView(k.textButton("Screenshots", p.play) {
            SnapPicker.show(this, k, storage, "Screenshots", null)
        }.apply { setPadding(k.dp(4f), k.dp(10f), k.dp(14f), k.dp(4f)) })
        links.addView(k.textButton("Run log", p.play) {
            startActivity(Intent(this, RunLogActivity::class.java))
        }.apply { setPadding(k.dp(4f), k.dp(10f), k.dp(4f), k.dp(4f)) })
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(links)
        })

        // ---- Pemutaran ----
        root.addView(k.sectionHeader("Playback"))
        val play = k.group()

        // Which recording these options are for.
        val scopeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = k.dp(64f)
            setPadding(k.dp(16f), k.dp(10f), k.dp(12f), k.dp(10f))
        }
        val scopeCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scopeTitle = k.text("", 16f, 1)
        scopeSub = k.text("", 13f, 0, p.muted).apply { setPadding(0, k.dp(3f), 0, 0) }
        scopeCol.addView(scopeTitle)
        scopeCol.addView(scopeSub)
        scopeRow.addView(scopeCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        scopeSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, on -> if (!suppressScope) setOwnSettings(on) }
        }
        scopeRow.addView(scopeSwitch)
        scopeRow.setOnClickListener { scopeSwitch.toggle() }
        play.addView(scopeRow)
        play.addView(k.divider())

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
        loopRow.addView(stepButton("−") { cfg.loops = (cfg.loops - 1).coerceAtLeast(0); saveCfg() })
        loopsValue = k.text("", 18f, 2).apply {
            gravity = Gravity.CENTER
            minWidth = k.dp(44f)
            fontFeatureSettings = "tnum"
            setOnClickListener {
                Ui.numberDialog(this@MainActivity, p, "Repeat", cfg.loops.toString(), false,
                    "0 = infinite") { v -> v.toIntOrNull()?.let { cfg.loops = it }; saveCfg() }
            }
        }
        loopRow.addView(loopsValue)
        loopRow.addView(stepButton("+") { cfg.loops = cfg.loops + 1; saveCfg() })
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
            Ui.numberDialog(this, p, "Delay between loops (seconds)", (cfg.intervalMs / 1000.0).toString(), true) { v ->
                v.toDoubleOrNull()?.let { cfg.intervalMs = (it * 1000).toLong() }; saveCfg()
            }
        }
        startValue = addRow(play, "Start delay") {
            Ui.numberDialog(this, p, "Start delay (seconds)", (cfg.startDelayMs / 1000.0).toString(), true) { v ->
                v.toDoubleOrNull()?.let { cfg.startDelayMs = (it * 1000).toLong() }; saveCfg()
            }
        }
        limitValue = addRow(play, "Auto stop") {
            Ui.numberDialog(this, p, "Auto stop after (minutes)", cfg.maxMinutes.toString(), false, "0 = off") { v ->
                v.toIntOrNull()?.let { cfg.maxMinutes = it }; saveCfg()
            }
        }
        jitterPxValue = addRow(play, "Randomize position") {
            Ui.numberDialog(this, p, "Randomize position (± px)", trim(cfg.jitterPx), true, "0 = off") { v ->
                v.toFloatOrNull()?.let { cfg.jitterPx = it }; saveCfg()
            }
        }
        jitterPctValue = addRow(play, "Randomize timing") {
            Ui.numberDialog(this, p, "Randomize timing (± %)", trim(cfg.jitterPct), true, "0 = off") { v ->
                v.toFloatOrNull()?.let { cfg.jitterPct = it }; saveCfg()
            }
        }
        val (alertRow, _) = k.switchRow("Alert when finished", storage.alertDone) { on -> storage.alertDone = on }
        play.addView(alertRow)
        play.addView(k.divider())
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

        // ---- App ----
        root.addView(k.sectionHeader("App"))
        val app = k.group()
        langValue = addRow(app, "Language") { pickLanguage() }
        addRow(app, "Tutorial", "How to record, play, and edit", last = true) {
            startActivity(Intent(this, TutorialActivity::class.java))
        }
        root.addView(app)


        // First launch: walk through the switches before anything else (the tutorial follows, see onResume).
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

    /** Simple auto clicker: one tap or swipe repeated until stopped. */
    private fun newAutoClicker() {
        TBuilder(this, Ui.dialogTheme(p))
            .setTitle("Auto clicker")
            .setItems(arrayOf("Tap one spot", "Swipe")) { _, which ->
                startActivity(
                    Intent(this, PointActivity::class.java)
                        .putExtra(PointActivity.EXTRA_AUTO, true)
                        .putExtra(PointActivity.EXTRA_MODE, if (which == 0) PointActivity.MODE_TAP else PointActivity.MODE_SWIPE)
                )
            }
            .show()
    }

    private fun pickLanguage() {
        val codes = arrayOf("auto", "en", "id")
        val names = arrayOf(L.t("Same as phone"), "English", "Bahasa Indonesia")
        TBuilder(this, Ui.dialogTheme(p))
            .setTitle(L.t("Language"))
            .setSingleChoiceItems(names, codes.indexOf(storage.language).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                storage.language = codes[which]
                L.load(this)
                recreate()
            }
            .show()
    }

    private fun langName(): String = when (storage.language) {
        "en" -> "English"
        "id" -> "Bahasa Indonesia"
        else -> L.t("Same as phone")
    }

    private fun trim(f: Float): String = if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()

    // ---------- siklus hidup ----------

    override fun onResume() {
        super.onResume()
        RecorderService.onChanged = { runOnUiThread { refresh() } }
        ShizukuBridge.onChanged = { runOnUiThread { refresh() } }
        refresh()
        // Show the short tutorial once, after the setup guide.
        if ((storage.setupSeen || RecorderService.instance != null) && !storage.tutorialSeen) {
            storage.tutorialSeen = true
            startActivity(Intent(this, TutorialActivity::class.java))
        }
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
            a11ySub.text = L.t("On")
            a11yAction.visibility = View.GONE
        } else {
            setDot(a11yDot, p.rec)
            a11ySub.text = L.t("Off")
            a11yAction.visibility = View.VISIBLE
        }
        panelButton.text = L.t(when {
            svc == null -> "Turn on accessibility service"
            svc.isPanelShown -> "Hide panel"
            else -> "Show panel"
        })
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
        shzSub.text = L.t(msg)
        shzAction.visibility = if (storage.useShizuku && running && (!perm || (!connected && !ShizukuBridge.connecting))) View.VISIBLE else View.GONE
        shzAction.text = L.t(if (!perm) "Allow" else "Retry")
        val diag = if (storage.useShizuku) ShizukuBridge.diagnostics() else ""
        shzDiag.text = diag
        shzDiag.visibility = if (diag.isEmpty()) View.GONE else View.VISIBLE

        refreshSettings()
        renderList()
    }

    private fun loadCfg() {
        val sel = storage.selected()
        cfgRec = sel?.takeIf { it.settings != null }
        cfg = cfgRec?.settings ?: storage.globalSettings()
        suppressScope = true
        scopeSwitch.isChecked = cfgRec != null
        scopeSwitch.isEnabled = sel != null
        suppressScope = false
        scopeTitle.text = L.t("Only for this recording")
        scopeSub.text = when {
            sel == null -> L.t("Record something first")
            cfgRec != null -> L.t("Used only by “{}”", sel.name)
            else -> L.t("Off: these settings are used by all recordings")
        }
    }

    /** Save the shown options where they belong, then show them again. */
    private fun saveCfg() {
        val r = cfgRec
        if (r != null) storage.save(r) else storage.saveGlobal(cfg)
        refreshSettings()
    }

    /** Give the selected recording its own copy of the options, or go back to the global ones. */
    private fun setOwnSettings(on: Boolean) {
        val sel = storage.selected() ?: return
        sel.settings = if (on) storage.globalSettings() else null
        storage.save(sel)
        refreshSettings()
        renderList()
    }

    private fun refreshSettings() {
        loadCfg()
        langValue.text = langName()
        loopsValue.text = if (cfg.loops == 0) "∞" else cfg.loops.toString()
        intervalValue.text = Ui.fmtSec(cfg.intervalMs)
        startValue.text = Ui.fmtSec(cfg.startDelayMs)
        limitValue.text = if (cfg.maxMinutes == 0) L.t("Off") else L.t("{} min", cfg.maxMinutes)
        jitterPxValue.text = if (cfg.jitterPx == 0f) L.t("Off") else "± ${trim(cfg.jitterPx)} px"
        dimLevelValue.text = "${storage.dimLevel}%"
        val dimVis = if (storage.dimScreen) View.VISIBLE else View.GONE
        dimLevelRow.visibility = dimVis
        dimDivider?.visibility = dimVis
        jitterPctValue.text = if (cfg.jitterPct == 0f) L.t("Off") else "± ${trim(cfg.jitterPct)} %"

        speedBox.removeAllViews()
        for (sp in SPEEDS) {
            val selected = kotlin.math.abs(cfg.speed - sp) < 0.01f
            val label = (if (sp == sp.toInt().toFloat()) sp.toInt().toString() else sp.toString().replace('.', ',')) + "×"
            val t = k.text(label, 15f, if (selected) 2 else 1, if (selected) p.onAccent else p.ink).apply {
                gravity = Gravity.CENTER
                setPadding(0, k.dp(9f), 0, k.dp(9f))
                background = if (selected) k.rounded(p.ink.let { if (p.dark) p.play else it }, 8f) else k.ripple(null, k.dp(8f).toFloat())
                setOnClickListener { cfg.speed = sp; saveCfg() }
            }
            speedBox.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun renderList() {
        listGroup.removeAllViews()
        val recs = storage.list()
        if (recs.isEmpty()) {
            listGroup.addView(k.text("No recordings yet", 16f, 1).apply {
                setPadding(k.dp(16f), k.dp(18f), k.dp(16f), 0)
            })
            listGroup.addView(k.text(
                "Tap Show panel, open a game, and press the red button to record what you do. " +
                    "Or use Auto clicker to repeat a tap without recording.",
                14f, 0, p.muted,
            ).apply { setPadding(k.dp(16f), k.dp(6f), k.dp(16f), k.dp(18f)) })
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
                setOnClickListener { storage.selectedId = r.id; refreshSettings(); renderList() }
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
            val loops = r.flows.any { it.kind == FlowStep.GOTO }
            col.addView(k.text(
                "${r.steps().size} steps" + (if (r.images.isNotEmpty()) " · ${r.images.size} image" else "") +
                    (if (loops) " · repeats" else "") + (if (r.settings != null) " · own settings" else "") + "  ·  ${Ui.fmtSec(r.length)}  ·  ${fmt.format(Date(r.created))}",
                13f, 0, p.muted,
            ).apply { setPadding(0, k.dp(3f), 0, 0); fontFeatureSettings = "tnum" })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(k.textButton("Edit", p.play) { openEditor(r) }.apply {
                setPadding(k.dp(12f), k.dp(12f), k.dp(4f), k.dp(12f))
            })
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
            menu.add(0, 1, 0, L.t("Select"))
            menu.add(0, 2, 1, L.t("Edit"))
            menu.add(0, 3, 2, L.t("Rename"))
            menu.add(0, 6, 3, L.t("Duplicate"))
            menu.add(0, 4, 3, L.t("Export"))
            menu.add(0, 5, 4, L.t("Delete"))
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { storage.selectedId = r.id; refreshSettings(); renderList() }
                    2 -> openEditor(r)
                    6 -> duplicate(r)
                    3 -> rename(r)
                    4 -> export(r)
                    5 -> confirmDelete(r)
                }
                true
            }
            show()
        }
    }

    private fun openEditor(r: Recording) =
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, r.id))

    /** Save a copy, to try changes without touching the original. */
    private fun duplicate(r: Recording) {
        val c = Recording.fromJson(r.toJson())
        val copy = Recording(UUID.randomUUID().toString(), "${r.name} (copy)", System.currentTimeMillis(), c.gestures, c.images, c.flows)
        storage.save(copy)
        storage.selectedId = copy.id
        renderList()
        toast("Copied: ${copy.name}")
    }

    private fun rename(r: Recording) {
        val input = android.widget.EditText(this).apply {
            setText(r.name); setSelection(r.name.length); typeface = Ui.font(this@MainActivity, 1)
        }
        val box = k.frame(input, 22f, 4f)
        TBuilder(this, Ui.dialogTheme(p))
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
        TBuilder(this, Ui.dialogTheme(p))
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
                    if (r.steps().isEmpty()) { toast("No actions in this file"); return }
                    val copy = Recording(UUID.randomUUID().toString(), r.name, System.currentTimeMillis(), r.gestures, r.images, r.flows)
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

    private fun toast(s: String) = LToast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
