package ar.despertador.evangelio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

/** Hace sonar la alarma hasta que la pantalla de lectura le diga que pare. */
class AlarmService : Service() {

    companion object {
        private const val CHANNEL = "alarma"
        private const val NOTIF_ID = 7
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"

        @Volatile
        var instance: AlarmService? = null
            private set

        fun start(c: Context) {
            val i = Intent(c, AlarmService::class.java).setAction(ACTION_START)
            c.startForegroundService(i)
        }

        fun stop(c: Context) {
            instance?.stopAll()
        }
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wake: PowerManager.WakeLock? = null
    private var sounding = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        goForeground()
        if (instance == null) {
            instance = this
            val pm = getSystemService(PowerManager::class.java)
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "despertador:alarma").apply {
                acquire(60 * 60 * 1000L)
            }
            raiseVolume()
            ring()
        }
        openScreen()
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Alarma", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            description = "La alarma que se apaga leyendo el Evangelio"
        }
        nm.createNotificationChannel(ch)

        val full = PendingIntent.getActivity(
            this, 3,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Despertador del Evangelio")
            .setContentText("Leé el Evangelio en voz alta para apagar la alarma")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun openScreen() {
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // Queda la notificación de pantalla completa.
            }
        }
    }

    private fun raiseVolume() {
        try {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val min = (max * 0.7f).toInt()
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < min) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, min, 0)
            }
        } catch (e: Exception) {
        }
    }

    /** Suena (o vuelve a sonar). */
    fun ring() {
        sounding = true
        try {
            val p = player ?: createPlayer()
            if (p != null && !p.isPlaying) p.start()
        } catch (e: Exception) {
        }
        try {
            val v = vibrator ?: (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).also { vibrator = it }
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            @Suppress("DEPRECATION")
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 700), 0), attrs)
        } catch (e: Exception) {
        }
    }

    /** Silencio mientras se lee. */
    fun mute() {
        sounding = false
        try {
            player?.let { if (it.isPlaying) it.pause() }
        } catch (e: Exception) {
        }
        vibrator?.cancel()
    }

    val isSounding: Boolean get() = sounding

    private fun createPlayer(): MediaPlayer? {
        val uris = listOfNotNull(
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            Settings.System.DEFAULT_RINGTONE_URI
        )
        for (uri in uris) {
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                player = mp
                return mp
            } catch (e: Exception) {
                // probar el siguiente sonido
            }
        }
        return null
    }

    fun stopAll() {
        sounding = false
        instance = null
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        player?.release()
        player = null
        vibrator?.cancel()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        player?.release()
        player = null
        vibrator?.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
