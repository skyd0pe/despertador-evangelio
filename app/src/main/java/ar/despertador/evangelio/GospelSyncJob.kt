package ar.despertador.evangelio

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Una vez por día, cuando hay internet, baja los Evangelios de la semana (hoy y los seis
 * siguientes) y borra los anteriores. Así la alarma tiene su texto aunque pasen días sin conexión.
 */
class GospelSyncJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val ok = GospelRepo.syncWeek(this)
            jobFinished(params, ok < 7) // si faltó alguno, Android lo reintenta más tarde
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 42

        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(ID) != null) return
            val job = JobInfo.Builder(ID, ComponentName(c, GospelSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(24L * 60 * 60 * 1000)
                .setPersisted(true)
                .build()
            try {
                js.schedule(job)
            } catch (e: Exception) {
            }
        }
    }
}
