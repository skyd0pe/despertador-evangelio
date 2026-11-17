package ar.despertador.evangelio

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import java.util.Calendar
import java.util.Locale

/**
 * "Ya estás despierto": si a la hora de la alarma el celular está prendido y desbloqueado,
 * en lugar de sonar avisa con una notificación. Si nadie responde en 5 minutos (por ejemplo,
 * porque te dormiste mirando un video), la alarma suena igual.
 */
object Awake {
    private const val CHANNEL = "despierto"
    private const val NOTIF = 21
    const val ACTION_FALLBACK = "ar.despertador.evangelio.AWAKE_FALLBACK"
    const val ACTION_DISMISS = "ar.despertador.evangelio.AWAKE_DISMISS"
    const val GRACE_MIN = 5

    /** El celular está en uso: pantalla prendida y sin bloqueo. */
    fun inUse(c: Context): Boolean {
        val pm = c.getSystemService(PowerManager::class.java)
        val km = c.getSystemService(KeyguardManager::class.java)
        return pm.isInteractive && !km.isKeyguardLocked
    }

    private fun broadcast(c: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        c, code, Intent(c, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun notify(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Alarma con el celular en uso", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Cuando ya estás usando el celular a la hora de la alarma"
            }
        )
        val read = PendingIntent.getActivity(
            c, 400,
            Intent(c, AlarmActivity::class.java).putExtra("quiet", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = "Lazarus no sonó porque estás usando el celular. Tocá para leer el Evangelio de hoy " +
            "y sumar el día a tu racha. Si en $GRACE_MIN minutos no respondés, suena igual."
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Ya estás despierto")
            .setContentText("Tocá para leer el Evangelio de hoy.")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(read)
            .setDeleteIntent(broadcast(c, ACTION_DISMISS, 402))
            .addAction(Notification.Action.Builder(null, "Leer ahora", read).build())
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF, n)

        val am = c.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + GRACE_MIN * 60_000L
        val pi = broadcast(c, ACTION_FALLBACK, 401)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Respondió (abrió la lectura o descartó el aviso): ya no hace falta sonar. */
    fun answered(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(broadcast(c, ACTION_FALLBACK, 401))
        c.getSystemService(NotificationManager::class.java).cancel(NOTIF)
    }

    /** Pasaron los 5 minutos sin respuesta. */
    fun fallback(c: Context) {
        c.getSystemService(NotificationManager::class.java).cancel(NOTIF)
        AlarmService.start(c)
    }
}

/**
 * Todas las noches a las 22 h pregunta a qué hora te despertás mañana. Siempre pregunta, aunque
 * ya haya una alarma: en ese caso la menciona, para dejarla o elegir otra.
 */
object Night {
    private const val CHANNEL = "recordatorio"
    private const val NOTIF = 22
    const val HOUR = 22

    private fun pi(c: Context) = PendingIntent.getBroadcast(
        c, 500, Intent(c, NightReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun sp(c: Context) = c.getSharedPreferences("night", Context.MODE_PRIVATE)

    /** Cuándo está programada la próxima pregunta y cuándo salió la última (0 = nunca). */
    fun nextAt(c: Context) = sp(c).getLong("next", 0)
    fun lastAt(c: Context) = sp(c).getLong("last", 0)

    fun ensureChannel(c: Context) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pregunta de la noche", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A las $HOUR h, para elegir a qué hora te despertás mañana"
            }
        )
    }

    /** Si Android deja mostrar la pregunta (notificaciones de la app y de este canal activadas). */
    fun canNotify(c: Context): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        val ch = nm.getNotificationChannel(CHANNEL) ?: return true
        return ch.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Abre los ajustes de Android de esta notificación. */
    fun openSettings(c: Context) {
        ensureChannel(c)
        val i = if (c.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
            Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL)
        else Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, c.packageName)
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Programa la próxima pregunta a las 22 h en punto. Usa un aviso exacto, que Android respeta
     * aunque el celular esté quieto o ahorrando batería (el aproximado lo podía postergar o saltear).
     * Se llama seguido (al abrir la app, a la medianoche, al sonar una alarma): si ya está, la reemplaza.
     */
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (!Prefs.nightReminder(c)) {
            am.cancel(pi(c))
            sp(c).edit().putLong("next", 0).apply()
            return
        }
        ensureChannel(c)
        val t = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 60_000) add(Calendar.DAY_OF_YEAR, 1)
        }
        try {
            if (AlarmScheduler.canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.timeInMillis, pi(c))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.timeInMillis, pi(c))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.timeInMillis, pi(c))
        }
        sp(c).edit().putLong("next", t.timeInMillis).apply()
    }

    /** La pregunta va cambiando cada noche. */
    private val ASK = listOf(
        "¿A qué hora te despertás mañana?" to "Elegí la hora y dejá que la Palabra te despierte.",
        "¿A qué hora querés ganar la primera batalla de mañana?" to "Elegí la hora antes de dormirte.",
        "Lázaro no eligió la hora. Vos sí." to "¿A qué hora te levantás mañana?",
        "¿A qué hora te despertarías mañana?" to "Mañana hay una Palabra para vos.",
        "Antes de dormir: ¿a qué hora te levantás?" to "Elegí la hora de mañana.",
    )

    /** Muestra la pregunta. [test]: desde el botón "Probar ahora" (no cuenta como la de la noche). */
    fun post(c: Context, test: Boolean = false) {
        if (!test && !Prefs.nightReminder(c)) return
        val nm = c.getSystemService(NotificationManager::class.java)
        ensureChannel(c)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pick = PendingIntent.getActivity(
            c, 502,
            Intent(c, MainActivity::class.java).putExtra("new_alarm", true).addFlags(flags),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, text) = ASK[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % ASK.size]
        val next = AlarmScheduler.next(c)
        val body = if (next != null && next.second - System.currentTimeMillis() < 20L * 3600 * 1000) {
            val cal = Calendar.getInstance().apply { timeInMillis = next.second }
            val hm = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            "Ahora tenés una a las $hm. ¿La dejás así o elegís otra?"
        } else text

        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pick)
            .addAction(Notification.Action.Builder(null, "Elegir hora", pick).build())
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF, n)
        if (!test) sp(c).edit().putLong("last", System.currentTimeMillis()).apply()
    }
}

/** Privado: solo lo llama el aviso de las 22 h que programó Lazarus. */
class NightReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Night.post(c)
        Night.schedule(c)
    }
}
