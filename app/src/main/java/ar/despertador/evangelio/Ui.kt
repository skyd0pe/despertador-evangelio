package ar.despertador.evangelio

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/* =========================================================================
 * Tema "Misal": papel, tinta y rúbricas en rojo, como un libro litúrgico.
 * ========================================================================= */

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "Sistema"),
    LIGHT("light", "Claro"),
    DARK("dark", "Oscuro"),
    AMOLED("amoled", "AMOLED");

    companion object {
        fun of(key: String?) = values().firstOrNull { it.key == key } ?: SYSTEM
    }
}

class Palette(c: Context) {
    val mode: ThemeMode = Prefs.theme(c)
    val dark: Boolean = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.SYSTEM ->
            (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    private val amoled = mode == ThemeMode.AMOLED

    val paper = when { amoled -> 0xFF000000.toInt(); dark -> 0xFF16140F.toInt(); else -> 0xFFFAF8F3.toInt() }
    val surface = when { amoled -> 0xFF0C0B0A.toInt(); dark -> 0xFF1F1C16.toInt(); else -> 0xFFF2EEE5.toInt() }
    val ink = when { amoled -> 0xFFE8E2D6.toInt(); dark -> 0xFFECE5D8.toInt(); else -> 0xFF1C1A17.toInt() }
    val muted = when { amoled -> 0xFF9C958A.toInt(); dark -> 0xFFA79F92.toInt(); else -> 0xFF6B655C.toInt() }
    val faint = when { amoled -> 0xFF4E4A43.toInt(); dark -> 0xFF5E584E.toInt(); else -> 0xFFB9B2A6.toInt() }
    val rule = when { amoled -> 0xFF22201C.toInt(); dark -> 0xFF2E2A22.toInt(); else -> 0xFFE3DDD2.toInt() }
    val rubric = if (dark) 0xFFE07A6E.toInt() else 0xFFA3221B.toInt()
    val rubricSoft = if (dark) 0x33E07A6E else 0x1FA3221B
    val onRubric = paper

    fun liturgical(color: Liturgy.Color): Int = when (color) {
        Liturgy.Color.GREEN -> if (dark) 0xFF5FA27C.toInt() else 0xFF2F6B4A.toInt()
        Liturgy.Color.VIOLET -> if (dark) 0xFF9C7BC2.toInt() else 0xFF5B3A7A.toInt()
        Liturgy.Color.WHITE -> if (dark) 0xFFD6B35A.toInt() else 0xFFB8912F.toInt()
        Liturgy.Color.RED -> rubric
        Liturgy.Color.ROSE -> if (dark) 0xFFD99AB0.toInt() else 0xFFC0687F.toInt()
    }

    /** Pinta la ventana y las barras del sistema con el papel del tema. */
    fun applyTo(a: Activity) {
        a.window.decorView.setBackgroundColor(paper)
        @Suppress("DEPRECATION")
        a.window.statusBarColor = paper
        @Suppress("DEPRECATION")
        a.window.navigationBarColor = paper
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            a.window.insetsController?.setSystemBarsAppearance(if (dark) 0 else flags, flags)
        } else {
            @Suppress("DEPRECATION")
            a.window.decorView.systemUiVisibility = if (dark) 0 else
                (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        }
    }

    /** Tema de los diálogos del sistema (hora, confirmaciones). */
    val dialogTheme: Int
        get() = if (dark) android.R.style.Theme_Material_Dialog_Alert
        else android.R.style.Theme_Material_Light_Dialog_Alert

    val activityTheme: Int
        get() = if (dark) R.style.AppThemeDark else R.style.AppThemeLight
}

/** Tipografías: Cormorant Garamond para títulos y horas, Source Serif para el texto. */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    private fun load(c: Context, file: String, weight: Int, italic: Boolean, fallback: Typeface): Typeface {
        val key = "$file/$weight/$italic"
        cache[key]?.let { return it }
        val tf = try {
            Typeface.Builder(c.assets, "fonts/$file")
                .setFontVariationSettings("'wght' $weight")
                .setWeight(weight)
                .setItalic(italic)
                .build()
        } catch (e: Exception) {
            null
        } ?: fallback
        val styled = if (italic && !tf.isItalic) Typeface.create(tf, Typeface.ITALIC) else tf
        cache[key] = styled
        return styled
    }

    fun display(c: Context, weight: Int = 600) =
        load(c, "display.ttf", weight, false, Typeface.create(Typeface.SERIF, Typeface.BOLD))

    fun text(c: Context, weight: Int = 400) =
        load(c, "text.ttf", weight, false, if (weight >= 600) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.SERIF)

    fun italic(c: Context) =
        load(c, "text.ttf", 400, true, Typeface.create(Typeface.SERIF, Typeface.ITALIC))
}

/* ---------------- helpers de vistas ---------------- */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.label(s: String, sp: Float, color: Int, face: Typeface? = null): TextView =
    TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = face ?: Fonts.text(this@label)
        setLineSpacing(0f, 1.18f)
    }

/** Línea en rojo y versalitas, como las rúbricas del misal. */
fun Context.rubric(s: String, p: Palette): TextView =
    label(s.uppercase(), 12f, p.rubric, Fonts.text(this, 600)).apply { letterSpacing = 0.14f }

fun Context.hairline(p: Palette, color: Int = p.rule, thicknessDp: Int = 1): View = View(this).apply {
    setBackgroundColor(color)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(thicknessDp))
}

/** Botón rectangular de esquinas casi rectas. [filled] = fondo de color; si no, contorno. */
fun Context.bookButton(text: String, color: Int, textColor: Int, filled: Boolean, onTap: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 17f
        typeface = Fonts.text(this@bookButton, 600)
        setTextColor(textColor)
        stateListAnimator = null
        minHeight = dp(54)
        minimumHeight = dp(54)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dpf(3f)
            if (filled) setColor(color) else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), color)
            }
        }
        setOnClickListener { onTap() }
    }

fun Context.textButton(text: String, color: Int, onTap: () -> Unit): TextView =
    label(text, 15f, color, Fonts.text(this, 600)).apply {
        setPadding(dp(4), dp(10), dp(4), dp(10))
        setOnClickListener { onTap() }
        isClickable = true
        isFocusable = true
    }

fun row(c: Context): LinearLayout = LinearLayout(c).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun col(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

/** Agrega una vista con un margen arriba (separación entre bloques). */
fun LinearLayout.add(v: View, topDp: Int = 0, weight: Float = 0f, matchWidth: Boolean = true) {
    val horizontal = orientation == LinearLayout.HORIZONTAL
    val w = when {
        weight > 0f && horizontal -> 0
        matchWidth && !horizontal -> ViewGroup.LayoutParams.MATCH_PARENT
        else -> ViewGroup.LayoutParams.WRAP_CONTENT
    }
    val fixedH = v.layoutParams?.height?.takeIf { it > 0 }
    val lp = LinearLayout.LayoutParams(w, fixedH ?: ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    lp.topMargin = context.dp(topDp)
    addView(v, lp)
}

fun tintControls(p: Palette, sw: android.widget.Switch) {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    sw.thumbTintList = ColorStateList(states, intArrayOf(p.rubric, p.muted))
    val trackOn = (p.rubric and 0x00FFFFFF) or 0x66000000
    sw.trackTintList = ColorStateList(states, intArrayOf(trackOn, p.faint))
}

fun tintSeek(p: Palette, sb: android.widget.SeekBar) {
    sb.progressTintList = ColorStateList.valueOf(p.rubric)
    sb.thumbTintList = ColorStateList.valueOf(p.rubric)
    sb.progressBackgroundTintList = ColorStateList.valueOf(p.faint)
}

fun tintRadio(p: Palette, rb: android.widget.RadioButton) {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    rb.buttonTintList = ColorStateList(states, intArrayOf(p.rubric, p.muted))
}

/** Texto con letra capital roja, para pantallas sin seguimiento de lectura. */
fun Context.withDropCap(text: String, p: Palette, textPx: Float): CharSequence {
    if (text.isEmpty() || !text[0].isLetter()) return text
    val body = text.substring(1)
    val sb = android.text.SpannableString(body)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.display(this@withDropCap, 700)
        color = p.rubric
        textSize = textPx * 3.1f
    }
    val end = body.indexOf('\n').let { if (it < 0) body.length else it }
    if (end > 0) sb.setSpan(DropCapSpan(text.substring(0, 1), paint, dpf(6f)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return sb
}

/* ---------------- piezas dibujadas ---------------- */

/** La cinta del misal, con la punta en forma de cola de golondrina. */
class RibbonView(c: Context) : View(c) {
    var color: Int = Color.GRAY
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        path.reset()
        path.moveTo(0f, 0f)
        path.lineTo(w, 0f)
        path.lineTo(w, h)
        path.lineTo(w / 2f, h * 0.86f)
        path.lineTo(0f, h)
        path.close()
        paint.color = color
        canvas.drawPath(path, paint)
    }
}

/** Raya de progreso fina con una marca en la meta. */
class RuleProgress(c: Context, private val p: Palette) : View(c) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var goal = 0.8f
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(16))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val mid = height / 2f
        val t = context.dpf(2f)
        paint.color = p.rule
        canvas.drawRect(0f, mid - t, w, mid + t, paint)
        paint.color = p.rubric
        canvas.drawRect(0f, mid - t, w * fraction, mid + t, paint)
        paint.color = p.ink
        val x = w * goal
        canvas.drawRect(x - context.dpf(0.75f), 0f, x + context.dpf(0.75f), height.toFloat(), paint)
    }
}

/** Letra capital: la primera letra grande, en rojo, ocupando dos renglones. */
class DropCapSpan(
    private val letter: String,
    private val paint: Paint,
    private val gap: Float,
) : LeadingMarginSpan.LeadingMarginSpan2 {
    private val width = (paint.measureText(letter) + gap).toInt()

    override fun getLeadingMarginLineCount() = 2
    override fun getLeadingMargin(first: Boolean) = if (first) width else 0

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
    ) {
        val spanStart = (text as? Spanned)?.getSpanStart(this) ?: return
        if (start != spanStart || layout == null) return
        val line = layout.getLineForOffset(start)
        val target = if (line + 1 < layout.lineCount) layout.getLineBaseline(line + 1) else baseline
        c.drawText(letter, x.toFloat(), target.toFloat(), paint)
    }
}
