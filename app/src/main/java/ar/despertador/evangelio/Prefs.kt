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

    fun theme(c: Context) = ThemeMode.of(sp(c).getString("theme", null))

    /** Modo ecuménico: oculta las referencias católicas y deja solo las bíblicas. */
    fun ecumenical(c: Context) = sp(c).getBoolean("ecumenical", false)

    /** Sonido de la alarma: "system" o uno de [Sounds]. */
    fun sound(c: Context) = sp(c).getString("sound", "campanas") ?: "campanas"

    /** La bienvenida ya se cerró con la X. */
    fun welcomeDismissed(c: Context) = sp(c).getBoolean("welcome_done", false)

    /** Ya probó la alarma al menos una vez. */
    fun tested(c: Context) = sp(c).getBoolean("tested", false)

    /** Ya vio la presentación de la primera vez. */
    fun introSeen(c: Context) = sp(c).getBoolean("intro_seen", false)

    /** Pregunta de las 22 h: a qué hora te despertás mañana. */
    fun nightReminder(c: Context) = sp(c).getBoolean("night_reminder", true)

    /** No sonar si el celular ya está en uso a la hora de la alarma. */
    fun skipIfAwake(c: Context) = sp(c).getBoolean("skip_if_awake", true)

    /** Tope de sueño: despertar si dormís más de [sleepHours] horas. */
    fun sleepCap(c: Context) = sp(c).getBoolean("sleep_cap", false)

    /** Horas de sueño a partir de las cuales suena el tope (7, 8 o 9). */
    fun sleepHours(c: Context) = sp(c).getInt("sleep_hours", 8)

    fun setIntroSeen(c: Context) = sp(c).edit().putBoolean("intro_seen", true).apply()
    fun setNightReminder(c: Context, v: Boolean) = sp(c).edit().putBoolean("night_reminder", v).apply()
    fun setSkipIfAwake(c: Context, v: Boolean) = sp(c).edit().putBoolean("skip_if_awake", v).apply()

    fun setSleepCap(c: Context, v: Boolean) = sp(c).edit().putBoolean("sleep_cap", v).apply()
    fun setSleepHours(c: Context, v: Int) = sp(c).edit().putInt("sleep_hours", v).apply()

    fun setSound(c: Context, v: String) = sp(c).edit().putString("sound", v).apply()
    fun setWelcomeDismissed(c: Context) = sp(c).edit().putBoolean("welcome_done", true).apply()
    fun setTested(c: Context) = sp(c).edit().putBoolean("tested", true).apply()

    fun setThreshold(c: Context, v: Int) = sp(c).edit().putInt("threshold", v).apply()
    fun setSilence(c: Context, v: Int) = sp(c).edit().putInt("silence", v).apply()
    fun setRamp(c: Context, v: Int) = sp(c).edit().putInt("ramp", v).apply()
    fun setFontSize(c: Context, v: Int) = sp(c).edit().putInt("font", v).apply()
    fun setTheme(c: Context, m: ThemeMode) = sp(c).edit().putString("theme", m.key).apply()
    fun setEcumenical(c: Context, v: Boolean) = sp(c).edit().putBoolean("ecumenical", v).apply()
}
