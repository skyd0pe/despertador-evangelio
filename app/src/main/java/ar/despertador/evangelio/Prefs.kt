package ar.despertador.evangelio

import android.content.Context
import android.content.SharedPreferences

/** Ajustes generales (las alarmas están en [AlarmStore]). */
object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Porcentaje de palabras que hay que leer bien (60 a 95). */
    fun threshold(c: Context) = sp(c).getInt("threshold", 80)

    /** Segundos de silencio antes de que vuelva a sonar. */
    fun silenceSec(c: Context) = sp(c).getInt("silence", 30)

    /** Segundos que tarda el volumen en llegar al máximo (0 = de golpe). */
    fun rampSec(c: Context) = sp(c).getInt("ramp", 30)

    /** Tamaño de la letra del Evangelio, en sp. */
    fun fontSize(c: Context) = sp(c).getInt("font", 20)

    fun setThreshold(c: Context, v: Int) = sp(c).edit().putInt("threshold", v).apply()
    fun setSilence(c: Context, v: Int) = sp(c).edit().putInt("silence", v).apply()
    fun setRamp(c: Context, v: Int) = sp(c).edit().putInt("ramp", v).apply()
    fun setFontSize(c: Context, v: Int) = sp(c).edit().putInt("font", v).apply()
}
