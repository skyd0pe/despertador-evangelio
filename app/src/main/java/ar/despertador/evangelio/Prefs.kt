package ar.despertador.evangelio

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun hour(c: Context) = sp(c).getInt("hour", 7)
    fun minute(c: Context) = sp(c).getInt("minute", 0)
    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)

    /** Porcentaje de palabras que hay que leer bien (60 a 95). */
    fun threshold(c: Context) = sp(c).getInt("threshold", 80)

    /** Segundos de silencio antes de que vuelva a sonar. */
    fun silenceSec(c: Context) = sp(c).getInt("silence", 30)

    fun setTime(c: Context, h: Int, m: Int) =
        sp(c).edit().putInt("hour", h).putInt("minute", m).apply()

    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()
    fun setThreshold(c: Context, v: Int) = sp(c).edit().putInt("threshold", v).apply()
    fun setSilence(c: Context, v: Int) = sp(c).edit().putInt("silence", v).apply()
}
