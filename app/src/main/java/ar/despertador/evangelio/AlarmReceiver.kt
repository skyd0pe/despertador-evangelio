package ar.despertador.evangelio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> {
                AlarmService.start(c)
                AlarmScheduler.schedule(c) // la de mañana
            }
            else -> AlarmScheduler.schedule(c) // reinicio, actualización o permiso nuevo
        }
    }
}
