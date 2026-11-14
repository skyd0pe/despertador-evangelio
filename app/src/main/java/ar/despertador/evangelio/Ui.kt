package ar.despertador.evangelio

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/* =========================================================================
 * Identidad de Lazarus
 * Vigilia #12203A · Medianoche #1F3557 · Oro viejo #C9A06A · Bronce #7A5528
 * Lino #EDE4D3 · Alba #FAF7F1. Proporción 70 % azul · 20 % claros · 10 % oro.
 * Fraunces (marca y títulos) · Inter (interfaz) · Literata (lectura).
 * ========================================================================= */

object Brand {
    val VIGILIA = 0xFF12203A.toInt()
    val MEDIANOCHE = 0xFF1F3557.toInt()
    val ORO = 0xFFC9A06A.toInt()
    val BRONCE = 0xFF7A5528.toInt()
    val LINO = 0xFFEDE4D3.toInt()
    val ALBA = 0xFFFAF7F1.toInt()
    val HORIZONTE = 0xFF2C4470.toInt()
}

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

    /** Fondo de la pantalla. */
    val paper = when { amoled -> 0xFF000000.toInt(); dark -> Brand.VIGILIA; else -> Brand.ALBA }
    /** Tarjetas y superficies. */
    val surface = when { amoled -> 0xFF0D1626.toInt(); dark -> Brand.MEDIANOCHE; else -> Brand.LINO }
    val ink = if (dark) Brand.ALBA else Brand.VIGILIA
    val muted = if (dark) 0xFFAEB7CA.toInt() else 0xFF5A6275.toInt()
    val faint = when { amoled -> 0xFF3E4A62.toInt(); dark -> 0xFF4A5B7E.toInt(); else -> 0xFFB9B4A8.toInt() }
    val rule = when { amoled -> 0xFF1A2740.toInt(); dark -> Brand.HORIZONTE; else -> 0xFFE3DACA.toInt() }

    /** Oro viejo sobre oscuro, bronce sobre claro. */
    val accent = if (dark) Brand.ORO else Brand.BRONCE
    val onAccent = if (dark) Brand.VIGILIA else Brand.ALBA
    val accentSoft = if (dark) 0x33C9A06A else 0x267A5528

    /** Botón principal: oro sobre oscuro; azul Vigilia sobre claro. */
    val primaryBg = if (dark) Brand.ORO else Brand.VIGILIA
    val primaryFg = if (dark) Brand.VIGILIA else Brand.ALBA

    val warn = if (dark) 0xFFE8A27E.toInt() else 0xFF9C3D1C.toInt()

    fun liturgical(color: Liturgy.Color): Int = when (color) {
        Liturgy.Color.GREEN -> if (dark) 0xFF6FB48B.toInt() else 0xFF2F6B4A.toInt()
        Liturgy.Color.VIOLET -> if (dark) 0xFFA88BD0.toInt() else 0xFF5B3A7A.toInt()
        Liturgy.Color.WHITE -> if (dark) Brand.ALBA else 0xFFB8B0A0.toInt()
        Liturgy.Color.RED -> if (dark) 0xFFE07A6E.toInt() else 0xFFA3221B.toInt()
        Liturgy.Color.ROSE -> if (dark) 0xFFE0A0B6.toInt() else 0xFFC0687F.toInt()
    }

    /** Pinta la ventana y las barras del sistema. */
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

    val dialogTheme: Int
        get() = if (dark) android.R.style.Theme_Material_Dialog_Alert
        else android.R.style.Theme_Material_Light_Dialog_Alert

    val activityTheme: Int
        get() = if (dark) R.style.AppThemeDark else R.style.AppThemeLight
}

/** Tipografías de la marca (se bajan al compilar; si faltan, usa las del sistema). */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    private fun load(c: Context, file: String, axes: String, weight: Int, fallback: Typeface): Typeface {
        val key = "$file/$axes"
        cache[key]?.let { return it }
        val tf = try {
            Typeface.Builder(c.assets, "fonts/$file")
                .setFontVariationSettings(axes)
                .setWeight(weight)
                .build()
        } catch (e: Exception) {
            null
        } ?: fallback
        cache[key] = tf
        return tf
    }

    /** Fraunces: marca, horas y títulos. [opsz] grande para tamaños grandes. */
    fun display(c: Context, weight: Int = 400, opsz: Int = 72) =
        load(c, "display.ttf", "'wght' $weight, 'opsz' $opsz", weight,
            Typeface.create(Typeface.SERIF, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL))

    /** Inter: interfaz. */
    fun ui(c: Context, weight: Int = 400) =
        load(c, "ui.ttf", "'wght' $weight", weight,
            Typeface.create(Typeface.SANS_SERIF, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL))

    /** Literata: lectura del Evangelio. */
    fun text(c: Context, weight: Int = 400) =
        load(c, "text.ttf", "'wght' $weight", weight,
            Typeface.create(Typeface.SERIF, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL))
}

/* ---------------- helpers de vistas ---------------- */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.label(s: String, sp: Float, color: Int, face: Typeface? = null): TextView =
    TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = face ?: Fonts.ui(this@label)
        setLineSpacing(0f, 1.2f)
    }

/** Etiqueta chica en mayúsculas con espaciado, en el color de acento. */
fun Context.eyebrow(s: String, p: Palette, color: Int = p.accent): TextView =
    label(s.uppercase(), 12f, color, Fonts.ui(this, 600)).apply { letterSpacing = 0.16f }

fun Context.rounded(fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = dpf(radiusDp)
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

fun Context.card(p: Palette): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(20), dp(18), dp(20), dp(18))
    background = rounded(p.surface, 20f)
}

/** Botón en forma de píldora. Si [outline], solo contorno. */
fun Context.pill(text: String, bg: Int, fg: Int, outline: Boolean = false, onTap: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 17f
        typeface = Fonts.ui(this@pill, 600)
        setTextColor(fg)
        stateListAnimator = null
        minHeight = dp(56)
        minimumHeight = dp(56)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        background = if (outline) rounded(Color.TRANSPARENT, 28f, bg) else rounded(bg, 28f)
        setOnClickListener { onTap() }
    }

fun Context.textButton(text: String, color: Int, onTap: () -> Unit): TextView =
    label(text, 15f, color, Fonts.ui(this, 600)).apply {
        setPadding(dp(6), dp(12), dp(6), dp(12))
        setOnClickListener { onTap() }
        isClickable = true
        isFocusable = true
    }

fun Context.hairline(p: Palette, color: Int = p.rule): View = View(this).apply {
    setBackgroundColor(color)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
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
    val fixedW = v.layoutParams?.width?.takeIf { it > 0 }
    val fixedH = v.layoutParams?.height?.takeIf { it > 0 }
    val lp = LinearLayout.LayoutParams(fixedW ?: w, fixedH ?: ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    lp.topMargin = context.dp(topDp)
    addView(v, lp)
}

fun tintSwitch(p: Palette, sw: android.widget.Switch) {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    sw.thumbTintList = ColorStateList(states, intArrayOf(p.accent, p.muted))
    sw.trackTintList = ColorStateList(states, intArrayOf((p.accent and 0x00FFFFFF) or 0x66000000, p.faint))
}

fun tintSeek(p: Palette, sb: android.widget.SeekBar) {
    sb.progressTintList = ColorStateList.valueOf(p.accent)
    sb.thumbTintList = ColorStateList.valueOf(p.accent)
    sb.progressBackgroundTintList = ColorStateList.valueOf(p.faint)
}

fun tintRadio(p: Palette, rb: android.widget.RadioButton) {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    rb.buttonTintList = ColorStateList(states, intArrayOf(p.accent, p.muted))
}

/* ---------------- piezas dibujadas ---------------- */

/** "El trazo que se levanta": una línea que se eleva como el horizonte al amanecer. */
class TrazoView(c: Context, private val color: Int, private val heightDp: Int = 40) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = c.dpf(2.5f)
    }
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(heightDp))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = paint.strokeWidth
        val low = h * 0.8f
        val high = h * 0.2f
        val mid = w / 2f
        val half = w * 0.12f
        path.reset()
        path.moveTo(pad, low)
        path.lineTo(mid - half, low)
        path.cubicTo(mid - half * 0.35f, low, mid - half * 0.3f, high, mid, high)
        path.cubicTo(mid + half * 0.3f, high, mid + half * 0.35f, low, mid + half, low)
        path.lineTo(w - pad, low)
        paint.color = color
        canvas.drawPath(path, paint)
    }
}

/** Sol que asoma sobre el horizonte, con tres rayos. */
class SunriseView(c: Context, private val sun: Int, private val horizon: Int) : View(c) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(110))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val base = height * 0.86f
        val r = context.dpf(48f)
        val cx = w / 2f
        fill.color = sun
        canvas.drawArc(RectF(cx - r, base - r, cx + r, base + r), 180f, 180f, true, fill)
        stroke.color = sun
        stroke.strokeWidth = context.dpf(3f)
        val ray = { ang: Double ->
            val a = Math.toRadians(ang)
            val r1 = r + context.dpf(12f)
            val r2 = r + context.dpf(24f)
            canvas.drawLine(
                cx + (r1 * Math.cos(a)).toFloat(), base - (r1 * Math.sin(a)).toFloat(),
                cx + (r2 * Math.cos(a)).toFloat(), base - (r2 * Math.sin(a)).toFloat(), stroke
            )
        }
        ray(90.0); ray(135.0); ray(45.0)
        stroke.color = horizon
        stroke.strokeWidth = context.dpf(2.5f)
        canvas.drawLine(stroke.strokeWidth, base, w - stroke.strokeWidth, base, stroke)
    }
}

/** Raya de progreso redondeada con una marca en la meta. */
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
        val t = context.dpf(3f)
        paint.color = p.rule
        canvas.drawRoundRect(0f, mid - t, w, mid + t, t, t, paint)
        paint.color = p.accent
        if (fraction > 0f) canvas.drawRoundRect(0f, mid - t, maxOf(w * fraction, 2 * t), mid + t, t, t, paint)
        paint.color = p.ink
        val x = w * goal
        canvas.drawRoundRect(x - context.dpf(1f), 0f, x + context.dpf(1f), height.toFloat(), 2f, 2f, paint)
    }
}

/** Cuatro barritas que se mueven con la voz. */
class LevelBars(c: Context, private val color: Int) : View(c) {
    var level = 0f
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = floatArrayOf(0.45f, 1f, 0.7f, 0.85f)

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        val bw = width / 7f
        for (i in 0 until 4) {
            val hgt = height * (0.25f + 0.75f * level * shape[i])
            val x = i * bw * 2f
            canvas.drawRoundRect(x, (height - hgt) / 2f, x + bw, (height + hgt) / 2f, bw / 2f, bw / 2f, paint)
        }
    }
}

/** Ícono de micrófono dibujado con trazo. */
class MicIcon(c: Context, private val color: Int) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val s = width / 24f
        paint.color = color
        paint.strokeWidth = 2f * s
        canvas.drawRoundRect(9 * s, 3 * s, 15 * s, 14 * s, 3 * s, 3 * s, paint)
        canvas.drawArc(RectF(5 * s, 4 * s, 19 * s, 18 * s), 0f, 180f, false, paint)
        canvas.drawLine(12 * s, 18 * s, 12 * s, 21 * s, paint)
    }
}

/** Un círculo con una letra: los días de la racha o de una alarma. */
fun Context.dayDot(letter: String, on: Boolean, p: Palette, sizeDp: Int = 36, onFill: Int = p.accent, onText: Int = p.onAccent): TextView =
    label(letter, 13f, if (on) onText else p.muted, Fonts.ui(this, 600)).apply {
        gravity = Gravity.CENTER
        background = if (on) rounded(onFill, sizeDp / 2f) else rounded(Color.TRANSPARENT, sizeDp / 2f, p.rule)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }
