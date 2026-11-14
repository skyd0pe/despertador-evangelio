package ar.despertador.evangelio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {
    const val ACTION_FIRE = "ar.despertador.evangelio.FIRE"

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    fun nextTrigger(c: Context): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, Prefs.hour(c))
        cal.set(Calendar.MINUTE, Prefs.minute(c))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun canExact(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) am(c).canScheduleExactAlarms() else true

    private fun firePi(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 1,
        Intent(c, AlarmReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Programa (o cancela) la próxima alarma según los ajustes. Devuelve false si no pudo. */
    fun schedule(c: Context): Boolean {
        if (!Prefs.enabled(c)) {
            cancel(c)
            return true
        }
        if (!canExact(c)) return false
        val show = PendingIntent.getActivity(
            c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            am(c).setAlarmClock(AlarmManager.AlarmClockInfo(nextTrigger(c), show), firePi(c))
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun cancel(c: Context) {
        am(c).cancel(firePi(c))
    }
}
