package ar.despertador.evangelio

import android.content.Context
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
    private const val BASE = "https://feed.evangelizo.org/v2/reader.php"

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

    private fun save(c: Context, g: Gospel) {
        val sp = store(c)
        val e = sp.edit()
        val keep = setOf(today(), tomorrow(), g.date)
        for (k in sp.all.keys) {
            val d = k.substringAfter("_")
            if (d !in keep) e.remove(k)
        }
        e.putString("t_${g.date}", g.text)
            .putString("ti_${g.date}", g.title)
            .putString("r_${g.date}", g.ref)
            .apply()
    }

    /** Bloquea: llamar fuera del hilo principal. */
    fun fetch(c: Context, date: String): Gospel? = try {
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
        cached(c, date) ?: fetch(c, date) ?: fallback(date)

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "DespertadorEvangelio/1.0 (Android)")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun html(s: String): String = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()

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
