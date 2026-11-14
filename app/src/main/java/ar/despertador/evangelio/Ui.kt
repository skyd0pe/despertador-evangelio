package ar.despertador.evangelio

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Colores de la app, leídos de los recursos (cambian solos con el modo oscuro). */
class Palette(c: Context) {
    val bg = c.getColor(R.color.bg)
    val surface = c.getColor(R.color.surface)
    val surface2 = c.getColor(R.color.surface2)
    val fg = c.getColor(R.color.fg)
    val muted = c.getColor(R.color.muted)
    val line = c.getColor(R.color.line)
    val accent = c.getColor(R.color.accent)
    val accentSoft = c.getColor(R.color.accent_soft)
    val onAccent = c.getColor(R.color.on_accent)
    val alarm = c.getColor(R.color.alarm)
    val alarmSoft = c.getColor(R.color.alarm_soft)
    val miss = c.getColor(R.color.miss)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.label(
    s: String,
    sp: Float,
    color: Int,
    bold: Boolean = false,
    serif: Boolean = false,
): TextView = TextView(this).apply {
    text = s
    textSize = sp
    setTextColor(color)
    typeface = when {
        serif -> Typeface.SERIF
        bold -> Typeface.DEFAULT_BOLD
        else -> Typeface.DEFAULT
    }
    setLineSpacing(0f, 1.15f)
}

fun Context.eyebrow(s: String, p: Palette): TextView =
    label(s.uppercase(), 12f, p.muted, bold = true).apply { letterSpacing = 0.08f }

fun Context.pillButton(text: String, fill: Int, textColor: Int, stroke: Int? = null, onTap: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(textColor)
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        minHeight = dp(52)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }
        setOnClickListener { onTap() }
    }

fun Context.card(p: Palette, fill: Int = p.surface): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(18), dp(16), dp(18), dp(16))
    background = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(fill)
        setStroke(dp(1), p.line)
    }
}

fun column(c: Context, gapDp: Int = 12): LinearLayout = LinearLayout(c).apply {
    orientation = LinearLayout.VERTICAL
    tag = gapDp
}

fun row(c: Context): LinearLayout = LinearLayout(c).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** Agrega una vista con un margen arriba (separación entre bloques). */
fun LinearLayout.add(v: android.view.View, topDp: Int = 0, weight: Float = 0f, matchWidth: Boolean = true) {
    val lp = LinearLayout.LayoutParams(
        if (weight > 0f) 0 else if (matchWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        weight
    )
    lp.topMargin = context.dp(topDp)
    addView(v, lp)
}
