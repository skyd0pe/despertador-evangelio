package ar.despertador.evangelio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {
    const val ACTION_FIRE = "ar.despertador.evangelio.FIRE"
    const val EXTRA_ID = "id"

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    /** Próximo momento en que suena esta alarma, respetando los días elegidos. */
    fun nextTrigger(a: Alarm): Long {
        val now = System.currentTimeMillis()
        for (d in 0..7) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, d)
            cal.set(Calendar.HOUR_OF_DAY, a.hour)
            cal.set(Calendar.MINUTE, a.minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= now + 1000) continue
            if (a.days == 0 || a.hasDay(Alarm.dayIndex(cal))) return cal.timeInMillis
        }
        return now + 24L * 3600 * 1000
    }

    /** La próxima alarma activa, si hay. */
    fun next(c: Context): Pair<Alarm, Long>? =
        AlarmStore.all(c).filter { it.enabled }.map { it to nextTrigger(it) }.minByOrNull { it.second }

    fun canExact(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) am(c).canScheduleExactAlarms() else true

    private fun firePi(c: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        c, 100 + id,
        Intent(c, AlarmReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Programa o cancela una alarma. Devuelve false si falta el permiso. */
    fun schedule(c: Context, a: Alarm): Boolean {
        if (!a.enabled) {
            cancel(c, a.id)
            return true
        }
        if (!canExact(c)) return false
        val show = PendingIntent.getActivity(
            c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            am(c).setAlarmClock(AlarmManager.AlarmClockInfo(nextTrigger(a), show), firePi(c, a.id))
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Vuelve a programar todas. Devuelve false si alguna no se pudo. */
    fun scheduleAll(c: Context): Boolean {
        var ok = true
        AlarmStore.all(c).forEach { if (!schedule(c, it)) ok = false }
        return ok
    }

    fun cancel(c: Context, id: Int) {
        am(c).cancel(firePi(c, id))
    }
}
