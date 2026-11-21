package ar.despertador.evangelio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.Html
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale

data class Gospel(
    val date: String,
    val title: String,
    val ref: String,
    val text: String,
    val offline: Boolean = false,
)

/**
 * Trae el Evangelio del día. La fuente es Evangelizo (evangeliodeldia.org), que en
 * español publica el texto de El Libro del Pueblo de Dios, la traducción de la CEA.
 * Se guarda en el teléfono para que la alarma funcione aunque no haya internet.
 */
object GospelRepo {
    const val BASE = "https://feed.evangelizo.org/v2/reader.php"

    fun key(cal: Calendar): String = String.format(
        Locale.US, "%04d%02d%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
    )

    fun today(): String = key(Calendar.getInstance())

    fun tomorrow(): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, 1)
        return key(c)
    }

    private fun store(c: Context) = c.getSharedPreferences("gospel", Context.MODE_PRIVATE)

    fun cached(c: Context, date: String): Gospel? {
        val sp = store(c)
        val text = sp.getString("t_$date", null) ?: return null
        return Gospel(
            date,
            sp.getString("ti_$date", "") ?: "",
            sp.getString("r_$date", "") ?: "",
            text
        )
    }

    /** Los días que se guardan: hoy y los seis siguientes. */
    fun week(): List<String> = (0..6).map { d ->
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, d)
        key(c)
    }

    /** Borra todo lo que no sea de esta semana. */
    private fun prune(c: Context) {
        val sp = store(c)
        val keep = week().toSet()
        val e = sp.edit()
        for (k in sp.all.keys) if (k.substringAfter("_") !in keep) e.remove(k)
        e.apply()
    }

    private fun save(c: Context, g: Gospel) {
        store(c).edit()
            .putString("t_${g.date}", g.text)
            .putString("ti_${g.date}", g.title)
            .putString("r_${g.date}", g.ref)
            .apply()
        prune(c)
    }

    /** Baja los Evangelios de hoy a seis días que todavía falten y borra los viejos. Bloquea. */
    fun syncWeek(c: Context): Int {
        var ok = 0
        for (d in week()) {
            if (cached(c, d) != null || fetch(c, d) != null) ok++
            VerseRepo.sync(c, d)
        }
        prune(c)
        VerseRepo.prune(c, week().toSet())
        FeedHealth.record(c, cached(c, today()) != null && cached(c, tomorrow()) != null)
        return ok
    }

    /** Bloquea: llamar fuera del hilo principal. */
    fun fetch(c: Context, date: String): Gospel? = fetchOnce(c, date) ?: run {
        try { Thread.sleep(1500) } catch (e: InterruptedException) { }
        fetchOnce(c, date)
    }

    private fun fetchOnce(c: Context, date: String): Gospel? = try {
        val text = clean(get("$BASE?date=$date&type=reading&content=GSP&lang=SP"))
        if (text.length < 40) {
            null
        } else {
            val ref = try {
                expandRef(html(get("$BASE?date=$date&type=reading_st&content=GSP&lang=SP")).trim())
            } catch (e: Exception) { "" }
            val title = try {
                html(get("$BASE?date=$date&type=liturgic_t&lang=SP")).trim()
            } catch (e: Exception) { "" }
            Gospel(date, title, ref, text).also { save(c, it) }
        }
    } catch (e: Exception) {
        null
    }

    /** Bloquea: llamar fuera del hilo principal. */
    fun loadOrFetch(c: Context, date: String): Gospel =
        cached(c, date) ?: fetch(c, date) ?: nearestCached(c, date) ?: fallback(date)

    /**
     * Plan B si no hay internet ni el texto de ese día: el Evangelio guardado más cercano
     * (también de la CEA) en vez de un versículo suelto de otra traducción.
     */
    fun nearestCached(c: Context, date: String): Gospel? {
        val target = Liturgy.dateOf(date).toEpochDay()
        val best = store(c).all.keys
            .filter { it.startsWith("t_") }
            .map { it.removePrefix("t_") }
            .minByOrNull { kotlin.math.abs(Liturgy.dateOf(it).toEpochDay() - target) }
            ?: return null
        return cached(c, best)?.copy(offline = true)
    }

    fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Lazarus/1.0 (Android)")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun html(s: String): String = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()

    fun clean(raw: String): String {
        var t = html(raw)
        val cut = listOf("Extraído de la Biblia", "Extraido de la Biblia", "evangeliodeldia.org")
            .map { t.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        if (cut != null) t = t.substring(0, cut)
        return t.lines()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private val BOOKS = mapOf(
        "Mt" to "Mateo", "Mc" to "Marcos", "Mr" to "Marcos",
        "Lc" to "Lucas", "Jn" to "Juan", "Jo" to "Juan"
    )

    fun expandRef(r: String): String {
        val m = Regex("^(\\S+)\\s+(.*)$").find(r) ?: return r
        val book = BOOKS[m.groupValues[1].trimEnd('.')] ?: m.groupValues[1]
        val rest = m.groupValues[2].replace(Regex(",\\s*"), ", ")
        return "$book $rest"
    }

    /** Texto de reserva si no hay internet ni nada guardado (Reina-Valera 1909, dominio público). */
    fun fallback(date: String) = Gospel(
        date,
        "Sin conexión: no se pudo bajar el Evangelio de hoy",
        "Juan 3, 16-17",
        "Porque de tal manera amó Dios al mundo, que ha dado a su Hijo unigénito, para que " +
            "todo aquel que en él cree, no se pierda, mas tenga vida eterna.\n" +
            "Porque no envió Dios a su Hijo al mundo, para que condene al mundo, mas para que " +
            "el mundo sea salvo por él.",
        offline = true
    )
}

/**
 * Si el Evangelio de hoy o de mañana no se pudo bajar, la alarma tendría que sonar con otro texto.
 * Esto lleva la cuenta de las fallas seguidas para avisar en la app y con una notificación.
 */
object FeedHealth {
    private const val CHANNEL = "avisos"
    private const val NOTIF = 23
    private const val ALERT_AFTER = 2

    private fun sp(c: Context) = c.getSharedPreferences("feed_health", Context.MODE_PRIVATE)

    fun record(c: Context, ok: Boolean) {
        val e = sp(c).edit()
        if (ok) {
            e.putInt("fails", 0).putBoolean("alerted", false).putLong("ok_at", System.currentTimeMillis())
        } else {
            e.putInt("fails", sp(c).getInt("fails", 0) + 1)
        }
        e.apply()
        if (ok) c.getSystemService(NotificationManager::class.java).cancel(NOTIF)
        else if (sp(c).getInt("fails", 0) >= ALERT_AFTER && !sp(c).getBoolean("alerted", false)) alert(c)
    }

    /** Mensaje para mostrar en la app mientras falte el Evangelio de hoy o de mañana; null si todo está bien. */
    fun problem(c: Context): String? {
        if (sp(c).getInt("fails", 0) < 1) return null
        return when {
            GospelRepo.cached(c, GospelRepo.today()) == null ->
                "No se pudo bajar el Evangelio de hoy. Si suena la alarma, va a mostrar otro texto guardado."
            GospelRepo.cached(c, GospelRepo.tomorrow()) == null ->
                "No se pudo bajar el Evangelio de mañana. Conectate a internet antes de dormir para que la alarma tenga su texto."
            else -> null
        }
    }

    private fun alert(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Avisos de Lazarus", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Cuando no se puede bajar el Evangelio del día"
            }
        )
        val open = PendingIntent.getActivity(
            c, 503, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("No pude bajar el Evangelio")
            .setContentText("Conectate a internet para que la alarma de mañana tenga su texto.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF, n)
        sp(c).edit().putBoolean("alerted", true).apply()
    }
}
