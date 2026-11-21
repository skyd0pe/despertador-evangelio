package ar.despertador.evangelio

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer

/** Los permisos y ajustes del sistema que necesita Lazarus para sonar a tiempo y escuchar la lectura. */
class Perm(val name: String, val why: String, val ok: Boolean, val fix: (() -> Unit)?)

fun Activity.appPerms(): List<Perm> {
    val list = ArrayList<Perm>()
    val pkg = Uri.parse("package:$packageName")
    list.add(Perm(
        "Micrófono", "Para escuchar tu lectura.",
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    ) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) })
    if (Build.VERSION.SDK_INT >= 33) {
        list.add(Perm(
            "Notificaciones", "Para mostrar la alarma.",
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) })
    }
    if (Build.VERSION.SDK_INT >= 31) {
        list.add(Perm("Alarmas exactas", "Para sonar a la hora justa.", AlarmScheduler.canExact(this)) {
            open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
        })
    }
    if (Build.VERSION.SDK_INT >= 34) {
        val nm = getSystemService(NotificationManager::class.java)
        list.add(Perm("Pantalla completa", "Para aparecer con el celular bloqueado.", nm.canUseFullScreenIntent()) {
            open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
        })
    }
    list.add(Perm("Mostrar sobre otras apps", "Para abrir la lectura sola al sonar.", Settings.canDrawOverlays(this)) {
        open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
    })
    val pm = getSystemService(PowerManager::class.java)
    list.add(Perm("Batería sin restricciones", "Para que el sistema no la duerma.", pm.isIgnoringBatteryOptimizations(packageName)) {
        open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
    })
    list.add(Perm(
        "Reconocimiento de voz", "Lo da la app de Google. Si falta, instalala o actualizala.",
        SpeechRecognizer.isRecognitionAvailable(this), null
    ))
    return list
}

fun Activity.open(i: Intent) {
    try {
        startActivity(i)
    } catch (e: Exception) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}
