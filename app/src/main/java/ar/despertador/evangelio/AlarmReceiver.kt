package ar.despertador.evangelio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/** Dispara la alarma. Es privado: solo lo puede llamar el sistema con el aviso que programó Lazarus. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            Awake.ACTION_FALLBACK -> { Awake.fallback(c); return }
            Awake.ACTION_DISMISS -> { Awake.answered(c); return }
            AlarmScheduler.ACTION_FIRE -> {}
            else -> return
        }
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        val a = AlarmStore.get(c, id)
        if (a == null || !a.enabled) return

        // Mantiene el procesador despierto hasta que el servicio tome su propio WakeLock.
        val wl = c.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "despertador:disparo")
        try {
            wl.acquire(15_000L)
        } catch (e: Exception) {
        }

        // Primero deja programada la próxima: si algo falla al sonar, la de mañana no se pierde.
        if (a.days == 0) {
            // Alarma de una sola vez: queda desactivada.
            AlarmStore.upsert(c, a.copy(enabled = false))
            AlarmScheduler.cancel(c, a.id)
        } else {
            AlarmScheduler.schedule(c, a)
        }
        try {
            Night.schedule(c)
        } catch (e: Exception) {
        }

        try {
            if (Prefs.skipIfAwake(c) && Awake.inUse(c)) {
                // Ya está despierto y usando el celular: avisa en vez de sonar.
                Awake.notify(c)
            } else {
                AlarmService.start(c)
            }
        } catch (e: Exception) {
            // Si el servicio no pudo arrancar, abre la pantalla de la alarma directamente.
            try {
                c.startActivity(
                    Intent(c, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
            }
        }
    }
}

/**
 * Vuelve a programar las alarmas cuando el celular se reinicia, cuando se actualiza la app o cuando
 * cambia el permiso de alarmas exactas. Está abierto al sistema, pero solo reprograma: nunca hace sonar nada.
 */
class BootReceiver : BroadcastReceiver() {
    private val allowed = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIMEZONE_CHANGED,
        Intent.ACTION_TIME_CHANGED,
        "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
    )

    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action !in allowed) return
        AlarmScheduler.scheduleAll(c)
        GospelSyncJob.schedule(c)
        LockVerse.schedule(c)
        Night.schedule(c)
    }
}
