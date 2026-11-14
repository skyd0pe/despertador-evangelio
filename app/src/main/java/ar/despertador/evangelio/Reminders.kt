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

/** Todas las noches, cerca de las 20 h, pregunta a qué hora te querés despertar mañana. */
object Night {
    private const val CHANNEL = "recordatorio"
    private const val NOTIF = 22
    const val HOUR = 20

    private fun pi(c: Context) = PendingIntent.getBroadcast(
        c, 500, Intent(c, NightReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (!Prefs.nightReminder(c)) {
            am.cancel(pi(c))
            return
        }
        val t = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 60_000) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.timeInMillis, pi(c))
    }

    private val ASK = listOf(
        "¿A qué hora te despertarías mañana?" to "Elegí la hora y dejá que la Palabra te despierte.",
        "Mañana hay otra batalla" to "¿A qué hora querés ganar la primera?",
        "Lázaro no eligió la hora. Vos sí." to "Poné tu alarma para mañana.",
        "Antes de que se haga tarde" to "¿A qué hora te levantás mañana?",
        "Mañana hay una Palabra para vos" to "¿A qué hora querés escucharla?",
    )

    fun post(c: Context) {
        if (!Prefs.nightReminder(c)) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recordatorio a la noche", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Cerca de las 20 h, para poner la alarma de mañana"
            }
        )
        val open = PendingIntent.getActivity(
            c, 501,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val newAlarm = PendingIntent.getActivity(
            c, 502,
            Intent(c, MainActivity::class.java).putExtra("new_alarm", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val next = AlarmScheduler.next(c)
        val soon = next != null && next.second - System.currentTimeMillis() < 20L * 3600 * 1000
        val b = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (soon && next != null) {
            val cal = Calendar.getInstance().apply { timeInMillis = next.second }
            val hm = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            b.setContentTitle("Mañana te despierto a las $hm")
                .setContentText("Si querés otra hora, cambiala ahora.")
        } else {
            val (title, text) = ASK[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % ASK.size]
            b.setContentTitle(title)
                .setContentText(text)
                .addAction(Notification.Action.Builder(null, "Poner alarma", newAlarm).build())
        }
        nm.notify(NOTIF, b.build())
    }
}

/** Privado: solo lo llama el aviso de las 20 h que programó Lazarus. */
class NightReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Night.post(c)
        Night.schedule(c)
    }
}
