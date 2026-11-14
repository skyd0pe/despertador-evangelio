package ar.despertador.evangelio

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Busca versiones nuevas en las publicaciones de GitHub del proyecto y las instala desde la app.
 * El instalador está firmado siempre con la misma clave, así que se instala encima sin perder nada.
 */
object Updater {
    private const val REPO = "skyd0pe/lazarus-app"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    class Info(val tag: String, val code: Long, val url: String, val size: Long, val notes: String)

    fun currentCode(c: Context): Long {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    fun currentName(c: Context): String =
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""

    /** Bloquea. Devuelve la versión nueva, o null si ya está al día o no hay conexión. */
    fun check(c: Context): Info? = try {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Lazarus-Android")
        val body = try {
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        body?.let { parse(it) }?.takeIf { it.code > currentCode(c) }
    } catch (e: Exception) {
        null
    }

    private fun parse(json: String): Info? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        val code = tag.substringAfterLast('.').toLongOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return Info(tag, code, a.getString("browser_download_url"), a.optLong("size"), o.optString("body"))
            }
        }
        return null
    }

    /** Android pide permiso una vez para que Lazarus pueda instalar sus propias versiones. */
    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    fun askInstallPermission(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
    }

    /** Bloquea: baja el instalador (avisando el porcentaje) y abre la confirmación de Android. */
    fun downloadAndInstall(c: Context, info: Info, progress: (Int) -> Unit) {
        val file = File(c.cacheDir, "Lazarus-update.apk")
        val conn = URL(info.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Lazarus-Android")
        try {
            val total = if (info.size > 0) info.size else conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; progress(pct) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("lazarus.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(c, 77, Intent(c, UpdateReceiver::class.java), flags)
            session.commit(pi.intentSender)
        }
        file.delete()
    }
}

/** Recibe el resultado de la instalación; si Android pide confirmación, la muestra. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                c.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> android.widget.Toast.makeText(
                c, "No se pudo instalar la versión nueva. Probá de nuevo más tarde.", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
