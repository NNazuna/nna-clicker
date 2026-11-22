package com.nnazuna.nnaclicker

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.widget.Toast

/**
 * English-only UI. [t] keeps call sites uniform and fills the `{}` holes used by
 * translated templates, without a dictionary lookup or regex on every text change.
 */
object L {
    fun load(@Suppress("UNUSED_PARAMETER") c: Context) = Unit

    fun t(s: CharSequence?): String = s?.toString() ?: ""

    fun t(template: String, vararg args: Any?): String {
        if (args.isEmpty()) return template
        return fill(template, args.map { it?.toString() ?: "" })
    }

    /** Replace `{}` holes in order; `{1}`, `{2}` … pick an argument by position. */
    private fun fill(tpl: String, args: List<String>): String {
        if (!tpl.contains('{')) return tpl
        val sb = StringBuilder()
        var next = 0
        var i = 0
        while (i < tpl.length) {
            val c = tpl[i]
            if (c == '{') {
                val end = tpl.indexOf('}', i)
                if (end > i) {
                    val inner = tpl.substring(i + 1, end)
                    val idx = if (inner.isEmpty()) next++ else inner.toIntOrNull()?.minus(1)
                    if (idx != null) {
                        sb.append(args.getOrElse(idx) { "" })
                        i = end + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}

/** AlertDialog.Builder that keeps titles, messages, buttons, and list items as plain strings. */
class TBuilder(context: Context, theme: Int) : AlertDialog.Builder(context, theme) {
    override fun setTitle(title: CharSequence?): AlertDialog.Builder = super.setTitle(L.t(title))
    override fun setMessage(message: CharSequence?): AlertDialog.Builder = super.setMessage(L.t(message))
    override fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setPositiveButton(L.t(text), listener)
    override fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setNegativeButton(L.t(text), listener)
    override fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setNeutralButton(L.t(text), listener)
    override fun setItems(items: Array<out CharSequence>?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setItems(items?.map { L.t(it) }?.toTypedArray(), listener)
    override fun setSingleChoiceItems(
        items: Array<out CharSequence>?, checkedItem: Int, listener: DialogInterface.OnClickListener?,
    ): AlertDialog.Builder = super.setSingleChoiceItems(items?.map { L.t(it) }?.toTypedArray(), checkedItem, listener)
}

/** Toast with plain text. */
object LToast {
    fun makeText(c: Context, text: CharSequence?, duration: Int): Toast = Toast.makeText(c, L.t(text), duration)
}
