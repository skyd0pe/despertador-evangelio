package ar.despertador.evangelio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Una alarma. [days] es una máscara de días: bit 0 = lunes … bit 6 = domingo.
 * Sin días marcados suena una sola vez y se desactiva.
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Int,
    val enabled: Boolean,
) {
    fun hasDay(i: Int) = days and (1 shl i) != 0

    fun timeText(): String = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)

    fun daysText(): String = when (days) {
        0 -> "Una sola vez"
        ALL -> "Todos los días"
        WEEKDAYS -> "De lunes a viernes"
        WEEKEND -> "Sábados y domingos"
        else -> (0..6).filter { hasDay(it) }.joinToString(", ") { DAY_NAMES[it] }
    }

    companion object {
        const val ALL = 0b1111111
        const val WEEKDAYS = 0b0011111
        const val WEEKEND = 0b1100000
        val DAY_LETTERS = listOf("L", "M", "X", "J", "V", "S", "D")
        val DAY_NAMES = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")

        /** Índice 0 = lunes … 6 = domingo, a partir de un Calendar. */
        fun dayIndex(cal: Calendar): Int = when (val d = cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> 6
            else -> d - Calendar.MONDAY
        }
    }
}

object AlarmStore {
    private fun sp(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun all(c: Context): List<Alarm> {
        val raw = sp(c).getString("alarms", null) ?: return migrate(c)
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Alarm(o.getInt("id"), o.getInt("h"), o.getInt("m"), o.getInt("d"), o.getBoolean("on"))
            }.sortedWith(compareBy({ it.hour }, { it.minute }))
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(c: Context, id: Int): Alarm? = all(c).firstOrNull { it.id == id }

    private fun save(c: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("h", it.hour).put("m", it.minute).put("d", it.days).put("on", it.enabled))
        }
        sp(c).edit().putString("alarms", arr.toString()).apply()
    }

    fun upsert(c: Context, a: Alarm) {
        save(c, all(c).filter { it.id != a.id } + a)
    }

    fun delete(c: Context, id: Int) {
        save(c, all(c).filter { it.id != id })
    }

    fun create(c: Context, hour: Int, minute: Int, days: Int = Alarm.ALL): Alarm {
        val list = all(c)
        val a = Alarm((list.maxOfOrNull { it.id } ?: 0) + 1, hour, minute, days, true)
        save(c, list + a)
        return a
    }

    /** Pasa la alarma única de la primera versión a la lista nueva. */
    private fun migrate(c: Context): List<Alarm> {
        val s = sp(c)
        val first = Alarm(
            1,
            s.getInt("hour", 7),
            s.getInt("minute", 0),
            Alarm.ALL,
            s.getBoolean("enabled", false)
        )
        save(c, listOf(first))
        return listOf(first)
    }
}
