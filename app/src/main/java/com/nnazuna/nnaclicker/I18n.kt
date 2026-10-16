package com.nnazuna.nnaclicker

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.widget.Toast

/**
 * App texts are written in English in the code; [t] turns them into Indonesian when that
 * language is chosen (or the phone uses it). Lookup order:
 * 1. the whole text,
 * 2. a pattern with `{}` holes (numbers, names) such as "Delay before step {} (ms)",
 * 3. each part of a text joined with " · ", translated on its own.
 * Anything not in the dictionary stays as it is.
 */
object L {
    @Volatile var indonesian = false
        private set

    fun load(c: Context) {
        val pref = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", "auto")
        indonesian = when (pref) {
            "id" -> true
            "en" -> false
            else -> java.util.Locale.getDefault().language.let { it == "in" || it == "id" }
        }
    }

    /** Translate a whole text. */
    fun t(s: CharSequence?): String {
        val text = s?.toString() ?: return ""
        if (!indonesian || text.isBlank()) return text
        return translate(text)
    }

    /** Translate a template and fill its `{}` holes with [args] in order. */
    fun t(template: String, vararg args: Any?): String {
        // A plain L.t("text") call lands here too (String beats CharSequence), so treat it as a whole text.
        if (args.isEmpty()) return t(template as CharSequence)
        val tpl = if (indonesian) I18nData.ID[template] ?: template else template
        return fill(tpl, args.map { it?.toString() ?: "" })
    }

    // ---------- internals ----------

    /** [joined]: the key itself contains " · ", so it may match a text that has one. */
    private class Pattern(val regex: Regex, val target: String, val joined: Boolean)

    private val patterns: List<Pattern> by lazy {
        I18nData.ID.filterKeys { it.contains("{}") }.map { (k, v) ->
            val parts = k.split("{}")
            val re = parts.joinToString("(.+?)") { Regex.escape(it) }
            Pattern(Regex("^$re$", RegexOption.DOT_MATCHES_ALL), v, k.contains('·'))
        }.sortedByDescending { it.regex.pattern.length }
    }

    private val cache = HashMap<String, String>()

    private fun translate(text: String): String = synchronized(cache) {
        if (cache.size > 3000) cache.clear()
        cache.getOrPut(text) { whole(text) ?: bySegments(text) ?: text }
    }

    private fun whole(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val lead = text.substring(0, text.indexOf(trimmed))
        val trail = text.substring(text.indexOf(trimmed) + trimmed.length)
        I18nData.ID[trimmed]?.let { return lead + it + trail }
        // A hole must not swallow a " · " separator; such texts are translated part by part.
        val joined = trimmed.contains('·')
        for (p in patterns) {
            if (joined && !p.joined) continue
            val m = p.regex.matchEntire(trimmed) ?: continue
            return lead + fill(p.target, m.groupValues.drop(1).map { translate(it) }) + trail
        }
        return null
    }

    private val SEP = Regex("\\s*·\\s*")

    private fun bySegments(text: String): String? {
        if (!text.contains('·')) return null
        val seps = SEP.findAll(text).map { it.value }.toList()
        val parts = text.split(SEP)
        if (parts.size < 2) return null
        val sb = StringBuilder()
        parts.forEachIndexed { i, part ->
            sb.append(whole(part) ?: part)
            if (i < seps.size) sb.append(seps[i])
        }
        return sb.toString()
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

/** AlertDialog.Builder that translates its title, message, buttons, and list items. */
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

/** Toast with translated text. */
object LToast {
    fun makeText(c: Context, text: CharSequence?, duration: Int): Toast = Toast.makeText(c, L.t(text), duration)
}
