package ar.despertador.evangelio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == AlarmScheduler.ACTION_FIRE) {
            val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
            val a = AlarmStore.get(c, id)
            if (a == null || !a.enabled) return
            AlarmService.start(c)
            if (a.days == 0) {
                // Alarma de una sola vez: queda desactivada.
                AlarmStore.upsert(c, a.copy(enabled = false))
                AlarmScheduler.cancel(c, a.id)
            } else {
                AlarmScheduler.schedule(c, a) // la próxima
            }
        } else {
            // Reinicio del celular, actualización de la app o permiso nuevo.
            AlarmScheduler.scheduleAll(c)
            GospelSyncJob.schedule(c)
        }
    }
}
