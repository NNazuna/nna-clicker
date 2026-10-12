package com.nnazuna.nnaclicker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Palet "panel instrumen": abu-abu dingin, merah = rekam, teal = putar. */
class Pal(
    val bg: Int,
    val surface: Int,
    val ink: Int,
    val muted: Int,
    val line: Int,
    val rec: Int,
    val play: Int,
    val warn: Int,
    val onAccent: Int,
    val dark: Boolean,
)

object Ui {
    private val light = Pal(
        bg = 0xFFE7EAE6.toInt(),
        surface = 0xFFF8F9F7.toInt(),
        ink = 0xFF1C2220.toInt(),
        muted = 0xFF5D6763.toInt(),
        line = 0xFFD5DAD5.toInt(),
        rec = 0xFFD12E3A.toInt(),
        play = 0xFF0E7564.toInt(),
        warn = 0xFFB7791F.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        dark = false,
    )
    private val dark = Pal(
        bg = 0xFF141817.toInt(),
        surface = 0xFF1E2321.toInt(),
        ink = 0xFFE4E9E6.toInt(),
        muted = 0xFF97A29D.toInt(),
        line = 0xFF2F3633.toInt(),
        rec = 0xFFF2545F.toInt(),
        play = 0xFF34B39B.toInt(),
        warn = 0xFFE0A54A.toInt(),
        onAccent = 0xFF0F1312.toInt(),
        dark = true,
    )

    fun pal(ctx: Context): Pal {
        val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) dark else light
    }

    private val fonts = HashMap<String, Typeface>()

    /** 0 = Regular, 1 = Medium, 2 = SemiBold (Barlow). */
    fun font(ctx: Context, weight: Int = 0): Typeface {
        val name = when (weight) { 1 -> "Medium"; 2 -> "SemiBold"; else -> "Regular" }
        return fonts.getOrPut(name) {
            try {
                Typeface.createFromAsset(ctx.assets, "fonts/Barlow-$name.ttf")
            } catch (e: Exception) {
                if (weight >= 2) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
    }

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    /** Status bar & navigation bar mengikuti warna latar. */
    @Suppress("DEPRECATION")
    fun styleWindow(a: Activity, p: Pal) {
        a.window.statusBarColor = p.bg
        a.window.navigationBarColor = p.bg
        a.window.decorView.setBackgroundColor(p.bg)
        if (!p.dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var f = a.window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            a.window.decorView.systemUiVisibility = f
        }
    }

    fun dialogTheme(p: Pal): Int =
        if (p.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert

    /** Dialog isian angka. */
    fun numberDialog(
        a: Activity, p: Pal, title: String, value: String, decimal: Boolean, help: String? = null,
        onOk: (String) -> Unit,
    ) {
        val input = EditText(a).apply {
            inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else InputType.TYPE_CLASS_NUMBER
            setText(value)
            setSelection(text.length)
            typeface = font(a, 1)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 22f), dp(a, 8f), dp(a, 22f), 0)
            if (help != null) addView(TextView(a).apply {
                text = help
                typeface = font(a)
                setTextColor(p.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            addView(input)
        }
        AlertDialog.Builder(a, dialogTheme(p))
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ -> onOk(input.text.toString().trim().replace(',', '.')) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun fmtSec(ms: Long): String = String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
}

/** Pembuat view kecil dengan gaya yang seragam. */
class Kit(private val a: Activity) {
    val p: Pal = Ui.pal(a)
    fun dp(v: Float) = Ui.dp(a, v)

    fun text(s: String, sizeSp: Float = 15f, weight: Int = 0, color: Int = p.ink) = TextView(a).apply {
        text = s
        typeface = Ui.font(a, weight)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        includeFontPadding = false
        setLineSpacing(0f, 1.15f)
    }

    fun ripple(base: Drawable?, radius: Float = 0f): Drawable {
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(0xFFFFFFFF.toInt()) }
        val rc = if (p.dark) 0x22FFFFFF else 0x1A1C2220
        return RippleDrawable(ColorStateList.valueOf(rc), base, mask)
    }

    fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (strokeColor != null) setStroke(dp(1f), strokeColor)
    }

    /** Kelompok baris dengan latar permukaan. */
    fun group(): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(p.surface, 14f)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8f) }
    }

    fun divider(insetDp: Float = 16f) = View(a).apply {
        setBackgroundColor(p.line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            leftMargin = dp(insetDp)
        }
    }

    /** Judul bagian, dengan aksi teks opsional di kanan. */
    fun sectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(28f), 0, dp(2f))
        }
        row.addView(text(title, 17f, 2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) row.addView(textButton(action, p.play) { onAction?.invoke() })
        return row
    }

    fun textButton(label: String, color: Int, onClick: () -> Unit) = text(label, 15f, 2, color).apply {
        setPadding(dp(12f), dp(8f), dp(4f), dp(8f))
        background = ripple(null, dp(8f).toFloat())
        setOnClickListener { onClick() }
    }

    /** Tombol utama berbentuk pil. */
    fun pillButton(label: String, color: Int, onClick: () -> Unit) = text(label, 16f, 2, p.onAccent).apply {
        gravity = Gravity.CENTER
        setPadding(dp(20f), dp(15f), dp(20f), dp(15f))
        background = ripple(rounded(color, 26f), dp(26f).toFloat())
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14f) }
    }

    fun icon(kind: Icon, color: Int, sizeDp: Float = 22f) = ImageView(a).apply {
        setImageDrawable(IconDrawable(kind, color, dp(sizeDp)))
        scaleType = ImageView.ScaleType.CENTER
    }

    /**
     * Baris pengaturan: label kiri (dan keterangan opsional), nilai di kanan.
     * Mengembalikan pasangan (baris, TextView nilai).
     */
    fun valueRow(label: String, sub: String? = null, onClick: (() -> Unit)? = null): Pair<View, TextView> {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            if (onClick != null) {
                background = ripple(null)
                setOnClickListener { onClick() }
            }
        }
        val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(label, 16f, 1))
        if (sub != null) left.addView(text(sub, 13f, 0, p.muted).apply { setPadding(0, dp(3f), 0, 0) })
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = text("", 16f, 1, p.muted).apply {
            fontFeatureSettings = "tnum"
            setPadding(dp(12f), 0, 0, 0)
        }
        row.addView(value)
        return Pair(row, value)
    }

    /** Baris dengan saklar on/off di kanan. */
    fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Pair<View, android.widget.Switch> {
        val sw = android.widget.Switch(a).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f)
            setPadding(dp(16f), dp(8f), dp(12f), dp(8f))
            background = ripple(null)
            setOnClickListener { sw.toggle() }
        }
        row.addView(text(label, 16f, 1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        return Pair(row, sw)
    }

    fun frame(child: View, padH: Float = 16f, padV: Float = 0f) = FrameLayout(a).apply {
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
        addView(child)
    }
}

enum class Icon { RECORD, STOP, PLAY, LIST, CLOSE, GRIP, MORE, BACK, CAMERA, IMAGE }

/** Ikon digambar langsung di canvas supaya tajam & konsisten di semua ROM (tanpa emoji). */
class IconDrawable(private val kind: Icon, color: Int, private val size: Int) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = size * 0.09f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        when (kind) {
            Icon.RECORD -> c.drawCircle(cx, cy, s * 0.30f, fill)
            Icon.STOP -> {
                val h = s * 0.26f
                c.drawRoundRect(RectF(cx - h, cy - h, cx + h, cy + h), s * 0.06f, s * 0.06f, fill)
            }
            Icon.PLAY -> {
                val path = Path().apply {
                    moveTo(cx - s * 0.20f, cy - s * 0.30f)
                    lineTo(cx + s * 0.30f, cy)
                    lineTo(cx - s * 0.20f, cy + s * 0.30f)
                    close()
                }
                fill.pathEffect = android.graphics.CornerPathEffect(s * 0.06f)
                c.drawPath(path, fill)
            }
            Icon.LIST -> for (i in -1..1) {
                val y = cy + i * s * 0.22f
                c.drawLine(cx - s * 0.28f, y, cx + s * 0.28f, y, stroke)
            }
            Icon.CLOSE -> {
                val h = s * 0.22f
                c.drawLine(cx - h, cy - h, cx + h, cy + h, stroke)
                c.drawLine(cx + h, cy - h, cx - h, cy + h, stroke)
            }
            Icon.GRIP -> for (i in -1..1) for (j in 0..1) {
                c.drawCircle(cx + (j - 0.5f) * s * 0.24f, cy + i * s * 0.24f, s * 0.065f, fill)
            }
            Icon.MORE -> for (i in -1..1) c.drawCircle(cx, cy + i * s * 0.24f, s * 0.07f, fill)
            Icon.CAMERA -> {
                // body
                c.drawRoundRect(RectF(cx - s * 0.32f, cy - s * 0.18f, cx + s * 0.32f, cy + s * 0.24f), s * 0.07f, s * 0.07f, stroke)
                // top bump
                c.drawLine(cx - s * 0.10f, cy - s * 0.26f, cx + s * 0.10f, cy - s * 0.26f, stroke)
                // lens
                c.drawCircle(cx, cy + s * 0.03f, s * 0.10f, stroke)
            }
            Icon.IMAGE -> {
                c.drawRoundRect(RectF(cx - s * 0.30f, cy - s * 0.24f, cx + s * 0.30f, cy + s * 0.24f), s * 0.06f, s * 0.06f, stroke)
                val path = Path().apply {
                    moveTo(cx - s * 0.22f, cy + s * 0.14f)
                    lineTo(cx - s * 0.04f, cy - s * 0.04f)
                    lineTo(cx + s * 0.08f, cy + s * 0.08f)
                    lineTo(cx + s * 0.14f, cy + s * 0.02f)
                    lineTo(cx + s * 0.22f, cy + s * 0.14f)
                }
                c.drawPath(path, stroke)
            }
            Icon.BACK -> {
                val path = Path().apply {
                    moveTo(cx + s * 0.10f, cy - s * 0.26f)
                    lineTo(cx - s * 0.16f, cy)
                    lineTo(cx + s * 0.10f, cy + s * 0.26f)
                }
                c.drawPath(path, stroke)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha; stroke.alpha = alpha
    }

    override fun setColorFilter(cf: ColorFilter?) {
        fill.colorFilter = cf; stroke.colorFilter = cf
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
