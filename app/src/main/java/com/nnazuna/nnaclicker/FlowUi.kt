package com.nnazuna.nnaclicker

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.util.TypedValue
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Dialogs and texts shared by the screens that edit flow steps (labels, jumps, waits). */
object FlowUi {

    /** "250 ms" below a second, otherwise seconds with up to two decimals: "1.5 s", "2 s". */
    fun sec(ms: Long): String = if (ms in 1..999) "$ms ms" else "${secText(ms)} s"

    fun parseSec(v: String): Long? = v.trim().replace(',', '.').toDoubleOrNull()?.let { (it * 1000).toLong() }

    /** A name for a new label that isn't used yet: "Label 1", "Label 2", … */
    fun newLabelName(rec: Recording): String {
        val used = rec.flows.filter { it.kind == FlowStep.LABEL }.map { it.name }.toSet()
        var n = 1
        while ("Label $n" in used) n++
        return "Label $n"
    }

    /** Pick a label of [rec]; shows how to add one when there are none. */
    fun pickLabel(a: Activity, p: Pal, rec: Recording, current: String?, title: String, onPick: (String) -> Unit) {
        val steps = rec.steps()
        val labels = rec.labels()
        if (labels.isEmpty()) {
            AlertDialog.Builder(a, Ui.dialogTheme(p))
                .setTitle("No labels yet")
                .setMessage("Add a Label step where the jump should land. Use Add step in the editor, then choose Label.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val names = labels.map { "${it.name}  ·  step ${steps.indexOf(it) + 1}" }.toTypedArray()
        AlertDialog.Builder(a, Ui.dialogTheme(p))
            .setTitle(title)
            .setSingleChoiceItems(names, labels.indexOfFirst { it.ref == current }) { d, which ->
                d.dismiss()
                onPick(labels[which].ref)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Dialog with one text field. */
    fun textDialog(a: Activity, p: Pal, title: String, value: String, onOk: (String) -> Unit) {
        val input = field(a, value, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        AlertDialog.Builder(a, Ui.dialogTheme(p))
            .setTitle(title)
            .setView(box(a, p, null, listOf(null to input)))
            .setPositiveButton("Save") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Dialog with several labeled number fields; [onOk] gets the values in order. */
    fun numbersDialog(
        a: Activity, p: Pal, title: String, help: String?, fields: List<Pair<String, String>>, decimal: Boolean,
        onOk: (List<String>) -> Unit,
    ) {
        val type = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
        val inputs = fields.map { (label, v) -> label to field(a, v, type) }
        AlertDialog.Builder(a, Ui.dialogTheme(p))
            .setTitle(title)
            .setView(box(a, p, help, inputs))
            .setPositiveButton("Save") { _, _ -> onOk(inputs.map { it.second.text.toString().trim().replace(',', '.') }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Edit a wait step: a random time from [FlowStep.minMs] to [FlowStep.maxMs]. */
    fun editWait(a: Activity, p: Pal, st: FlowStep, onDone: () -> Unit) =
        numbersDialog(
            a, p, "Wait (seconds)",
            "Waits a random time between the two values. Use the same value for a fixed wait.",
            listOf("From" to secText(st.minMs), "To" to secText(st.maxMs)), true,
        ) { v ->
            val lo = parseSec(v[0]) ?: return@numbersDialog
            val hi = parseSec(v[1]) ?: lo
            st.minMs = minOf(lo, hi).coerceIn(0, 3_600_000)
            st.maxMs = maxOf(lo, hi).coerceIn(0, 3_600_000)
            onDone()
        }

    private fun secText(ms: Long): String =
        java.math.BigDecimal(ms).movePointLeft(3).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** Pick an installed app that has a launcher icon; [onPick] gets (package, name). */
    fun pickApp(a: Activity, p: Pal, onPick: (String, String) -> Unit) {
        val pm = a.packageManager
        val apps = pm.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0,
        ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != a.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) return
        AlertDialog.Builder(a, Ui.dialogTheme(p))
            .setTitle("Open app")
            .setItems(apps.map { it.second }.toTypedArray()) { _, which -> onPick(apps[which].first, apps[which].second) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Pick a system button for a KEY step. */
    fun pickKey(a: Activity, p: Pal, onPick: (String) -> Unit) {
        val keys = listOf(FlowStep.KEY_BACK, FlowStep.KEY_HOME, FlowStep.KEY_RECENTS)
        AlertDialog.Builder(a, Ui.dialogTheme(p))
            .setTitle("System button")
            .setItems(keys.map { FlowStep.keyName(it) }.toTypedArray()) { _, which -> onPick(keys[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun field(a: Activity, value: String, type: Int) = EditText(a).apply {
        inputType = type
        setText(value)
        setSelection(text.length)
        typeface = Ui.font(a, 1)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
    }

    private fun box(a: Activity, p: Pal, help: String?, inputs: List<Pair<String?, EditText>>) =
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 22f), Ui.dp(a, 8f), Ui.dp(a, 22f), 0)
            if (help != null) addView(note(a, p, help))
            inputs.forEach { (label, input) ->
                if (label != null) addView(note(a, p, label).apply { setPadding(0, Ui.dp(a, 10f), 0, 0) })
                addView(input)
            }
        }

    private fun note(a: Activity, p: Pal, s: String) = TextView(a).apply {
        text = s
        typeface = Ui.font(a)
        setTextColor(p.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }
}
