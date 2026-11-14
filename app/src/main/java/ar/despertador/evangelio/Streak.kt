package ar.despertador.evangelio

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate

/** La racha: días seguidos en que se apagó la alarma leyendo. */
object Streak {
    private fun sp(c: Context) = c.getSharedPreferences("streak", Context.MODE_PRIVATE)

    /** Días leídos, guardados como "2026-11-14" (solo los últimos 60). */
    private fun days(c: Context): Set<LocalDate> =
        (sp(c).getStringSet("days", emptySet()) ?: emptySet()).mapNotNull {
            try { LocalDate.parse(it) } catch (e: Exception) { null }
        }.toSet()

    fun markToday(c: Context) {
        val today = LocalDate.now()
        val keep = (days(c) + today).filter { !it.isBefore(today.minusDays(60)) }
        sp(c).edit().putStringSet("days", keep.map { it.toString() }.toSet()).apply()
    }

    /** Días seguidos hasta hoy (o hasta ayer, si hoy todavía no leyó). */
    fun current(c: Context): Int {
        val d = days(c)
        var day = LocalDate.now()
        if (day !in d) day = day.minusDays(1)
        var n = 0
        while (day in d) {
            n++
            day = day.minusDays(1)
        }
        return n
    }

    /** Lunes a domingo de esta semana: true si ese día leyó. */
    fun thisWeek(c: Context): List<Boolean> {
        val d = days(c)
        val monday = LocalDate.now().with(DayOfWeek.MONDAY)
        return (0..6).map { monday.plusDays(it.toLong()) in d }
    }

    /** Frase de la racha, con el tono de la marca. */
    fun phrase(n: Int): String = when {
        n <= 0 -> "Hoy empieza la racha."
        n == 1 -> "Primer día. Todo empieza así."
        n < 7 -> "$n días seguidos escuchando."
        n == 7 -> "Siete mañanas seguidas escuchando. Eso ya es constancia."
        else -> "$n mañanas seguidas. Eso ya es constancia."
    }

    fun short(n: Int): String = when (n) {
        0 -> "Racha actual: todavía ninguna"
        1 -> "Racha actual: 1 día"
        else -> "Racha actual: $n días"
    }
}
