package ar.despertador.evangelio

import android.annotation.TargetApi
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale

/**
 * Tope de sueño: si dormís más de las horas elegidas, Lazarus te despierta.
 *
 * El reloj no avisa en el momento cuándo te dormiste, así que la hora se estima con el celular:
 * desde las 21 h mira cuándo apagaste la pantalla por última vez antes de un rato largo sin
 * desbloquearlo (al menos 90 minutos). Ese momento cuenta como la hora en que te dormiste, y la
 * alarma queda para esa hora más el tope. Si una alarma normal suena antes, no hace falta y no suena.
 *
 * Los datos de pantalla salen del "Acceso a datos de uso" de Android: solo se leen los momentos en
 * que la pantalla se prendió, se apagó o se desbloqueó, y nunca salen del celular.
 */
object SleepCap {
    const val ACTION_CHECK = "ar.despertador.evangelio.SLEEP_CHECK"
    const val ACTION_FIRE = "ar.despertador.evangelio.SLEEP_FIRE"

    /** Desde esta hora empieza a mirar si te dormiste. */
    const val NIGHT_START = 21

    /** Si a esta hora de la mañana no detectó nada, se rinde hasta la noche siguiente. */
    private const val NIGHT_END = 6

    /** Tiempo sin desbloquear el celular para dar por hecho que te dormiste. */
    const val MIN_IDLE_MIN = 90

    /** Cada cuánto vuelve a mirar durante la noche. */
    private const val CHECK_EVERY = 20 * 60_000L

    /** Después de que suena, las alarmas normales de las próximas horas no suenan: ya estás despierto. */
    private const val SKIP_REGULAR = 3 * 3600_000L

    private const val HOUR = 3600_000L

    private fun sp(c: Context) = c.getSharedPreferences("sleep", Context.MODE_PRIVATE)

    fun supported() = Build.VERSION.SDK_INT >= 28

    /** ¿Tiene el permiso de "Acceso a datos de uso"? */
    fun hasAccess(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            c.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else mode == AppOpsManager.MODE_ALLOWED
    }

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    private fun pi(c: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        c, code, Intent(c, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun checkPi(c: Context) = pi(c, ACTION_CHECK, 600)
    private fun firePi(c: Context) = pi(c, ACTION_FIRE, 601)

    // ---------------------------------------------------------------------
    // La noche: va de las 21 h de un día a las 6 h del siguiente
    // ---------------------------------------------------------------------

    /** La noche a la que pertenece un momento: la fecha del día en que empezó (antes del mediodía, la de ayer). */
    private fun nightOf(t: Long): LocalDate {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        val d = LocalDate.of(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        return if (cal.get(Calendar.HOUR_OF_DAY) < 12) d.minusDays(1) else d
    }

    private fun at(d: LocalDate, hour: Int): Long =
        d.atTime(LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun windowStart(night: LocalDate) = at(night, NIGHT_START)
    private fun windowEnd(night: LocalDate) = at(night.plusDays(1), NIGHT_END)

    // ---------------------------------------------------------------------
    // Revisión periódica
    // ---------------------------------------------------------------------

    /**
     * Mira si ya te dormiste y, si hace falta, deja programada la alarma del tope. Se puede llamar
     * todas las veces que se quiera: siempre deja todo en el estado correcto.
     */
    fun check(c: Context) {
        if (!Prefs.sleepCap(c) || !supported() || !hasAccess(c)) {
            am(c).cancel(checkPi(c))
            am(c).cancel(firePi(c))
            return
        }
        val now = System.currentTimeMillis()
        val night = nightOf(now)
        val from = windowStart(night)
        val until = windowEnd(night)
        val tomorrow = windowStart(night.plusDays(1))

        if (now < from) {
            disarm(c)
            scheduleCheck(c, from)
            return
        }

        val st = sp(c)
        val key = night.toString()
        if (st.getString("night", null) != key) {
            st.edit().clear().putString("night", key).apply()
        }

        var start = st.getLong("start", 0L)
        if (start == 0L && now <= until) {
            start = findSleepStart(c, from, now) ?: 0L
            if (start > 0L) st.edit().putLong("start", start).apply()
        }

        if (start == 0L) {
            // Todavía no te dormiste (o ya es de día y no se detectó nada).
            disarm(c)
            scheduleCheck(c, if (now < until) now + CHECK_EVERY else tomorrow)
            return
        }

        val cap = start + Prefs.sleepHours(c) * HOUR
        val alreadyFired = st.getBoolean("fired", false)
        val regular = AlarmScheduler.next(c)?.second
        when {
            alreadyFired -> disarm(c)
            regular != null && regular <= cap -> disarm(c) // la alarma de siempre suena antes
            cap < now - 30 * 60_000L -> disarm(c)          // ya pasó hace rato: no tiene sentido sonar
            else -> arm(c, maxOf(cap, now + 60_000L))
        }
        scheduleCheck(c, tomorrow)
    }

    private fun scheduleCheck(c: Context, t: Long) {
        am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, checkPi(c))
    }

    private fun arm(c: Context, t: Long) {
        val show = PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            am(c).setAlarmClock(AlarmManager.AlarmClockInfo(t, show), firePi(c))
        } catch (e: SecurityException) {
            am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, firePi(c))
        }
    }

    private fun disarm(c: Context) = am(c).cancel(firePi(c))

    /**
     * Busca el primer rato largo sin desbloquear el celular que toque la noche. Devuelve el momento
     * en que se apagó la pantalla al empezar ese rato: la hora estimada en que te dormiste.
     *
     * Usa el desbloqueo (y no la pantalla prendida) para cortar el rato, porque una notificación puede
     * prender la pantalla sin que te despiertes. Si el celular no tiene pantalla de bloqueo, usa la pantalla.
     */
    @TargetApi(28)
    private fun findSleepStart(c: Context, from: Long, now: Long): Long? {
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return null
        val minIdle = MIN_IDLE_MIN * 60_000L
        val earliest = from - HOUR // si te acostaste un poco antes de las 21 h, también cuenta

        data class Ev(val t: Long, val type: Int)
        val list = ArrayList<Ev>()
        val q = usm.queryEvents(from - 3 * HOUR, now)
        val e = UsageEvents.Event()
        var keyguard = false
        while (q.hasNextEvent()) {
            q.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE,
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> list.add(Ev(e.timeStamp, e.eventType))
                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    keyguard = true
                    list.add(Ev(e.timeStamp, e.eventType))
                }
            }
        }
        val wake = if (keyguard) UsageEvents.Event.KEYGUARD_HIDDEN else UsageEvents.Event.SCREEN_INTERACTIVE

        var offAt: Long? = null
        for (ev in list.sortedBy { it.t }) {
            when (ev.type) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> if (offAt == null) offAt = ev.t
                wake -> {
                    val off = offAt
                    if (off != null && off >= earliest && ev.t > from && ev.t - off >= minIdle) return off
                    offAt = null
                }
            }
        }
        val off = offAt
        return if (off != null && off >= earliest && now - off >= minIdle) off else null
    }

    // ---------------------------------------------------------------------
    // Cuando llega la hora
    // ---------------------------------------------------------------------

    fun fire(c: Context) {
        val st = sp(c)
        if (st.getBoolean("fired", false) || !Prefs.sleepCap(c)) return
        st.edit()
            .putBoolean("fired", true)
            .putLong("skip_until", System.currentTimeMillis() + SKIP_REGULAR)
            .apply()
        if (Prefs.skipIfAwake(c) && Awake.inUse(c)) Awake.notify(c) else AlarmService.start(c)
    }

    /** Ya te despertó el tope de sueño hace poco: la alarma normal no hace falta. */
    fun skipRegular(c: Context): Boolean =
        System.currentTimeMillis() < sp(c).getLong("skip_until", 0L)

    // ---------------------------------------------------------------------
    // Para mostrar en Ajustes
    // ---------------------------------------------------------------------

    private fun hm(t: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        return String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    /** Cómo viene la noche, en una frase. */
    fun status(c: Context): String {
        if (!supported()) return "Hace falta Android 9 o más nuevo."
        if (!hasAccess(c)) return "Falta el permiso de acceso a datos de uso. Sin él no puede saber cuándo dejaste el celular."
        val now = System.currentTimeMillis()
        val night = nightOf(now)
        val st = sp(c)
        val start = if (st.getString("night", null) == night.toString()) st.getLong("start", 0L) else 0L
        if (now < windowStart(night) || start == 0L) {
            return if (now < windowStart(night) || now > windowEnd(night)) {
                "Desde las $NIGHT_START h mira cuándo dejás el celular para dormir."
            } else {
                "Todavía no detectó que te dormiste esta noche."
            }
        }
        val cap = start + Prefs.sleepHours(c) * HOUR
        val regular = AlarmScheduler.next(c)?.second
        val slept = "Esta noche dejaste el celular a las ${hm(start)}."
        return when {
            st.getBoolean("fired", false) -> "$slept Ya sonó a las ${hm(cap)}."
            regular != null && regular <= cap -> "$slept Tu alarma de las ${hm(regular)} suena antes, así que no hace falta."
            cap < now -> "$slept El tope era a las ${hm(cap)}."
            else -> "$slept Si no te despertás antes, suena a las ${hm(cap)}."
        }
    }
}
