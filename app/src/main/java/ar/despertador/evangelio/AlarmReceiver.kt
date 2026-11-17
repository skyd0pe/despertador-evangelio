package ar.despertador.evangelio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
        Night.schedule(c)
        if (Prefs.skipIfAwake(c) && Awake.inUse(c)) {
            // Ya está despierto y usando el celular: avisa en vez de sonar.
            Awake.notify(c)
        } else {
            AlarmService.start(c)
        }
        if (a.days == 0) {
            // Alarma de una sola vez: queda desactivada.
            AlarmStore.upsert(c, a.copy(enabled = false))
            AlarmScheduler.cancel(c, a.id)
        } else {
            AlarmScheduler.schedule(c, a) // la próxima
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
