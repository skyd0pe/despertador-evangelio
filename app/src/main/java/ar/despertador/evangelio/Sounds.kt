package ar.despertador.evangelio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

/** Sonidos propios de Lazarus (en res/raw) más la opción de usar el del celular. */
object Sounds {
    class Sound(val key: String, val label: String, val res: Int?)

    val all = listOf(
        Sound("campanas", "Campanas", R.raw.campanas),
        Sound("amanecer", "Amanecer", R.raw.amanecer),
        Sound("arpa", "Arpa", R.raw.arpa),
        Sound("clasica", "Clásica", R.raw.clasica),
        Sound("system", "La del celular", null),
    )

    fun uriFor(c: Context, key: String): Uri? {
        val s = all.firstOrNull { it.key == key } ?: return null
        return s.res?.let { Uri.parse("android.resource://${c.packageName}/$it") }
    }

    private var preview: MediaPlayer? = null

    /** Escuchar un sonido unos segundos, al volumen de la alarma. */
    fun preview(c: Context, key: String) {
        stopPreview()
        val uri = uriFor(c, key) ?: android.media.RingtoneManager.getActualDefaultRingtoneUri(c, android.media.RingtoneManager.TYPE_ALARM) ?: return
        try {
            preview = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                setDataSource(c, uri)
                prepare()
                start()
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopPreview() }, 4000)
        } catch (e: Exception) {
            stopPreview()
        }
    }

    fun stopPreview() {
        try { preview?.stop() } catch (e: Exception) {}
        preview?.release()
        preview = null
    }
}
