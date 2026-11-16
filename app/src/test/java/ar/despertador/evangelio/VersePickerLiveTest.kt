package ar.despertador.evangelio

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Prueba con lecturas reales qué versículo elegiría la app cada día.
 * Solo corre si LAZARUS_LIVE=1 (necesita internet).
 */
class VersePickerLiveTest {
    private val base = "https://feed.evangelizo.org/v2/reader.php"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "Lazarus/1.0 (prueba)")
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    private fun html(s: String): String = s
        .replace(Regex("\\s*\r?\n\\s*"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&laquo;", "«").replace("&raquo;", "»")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }

    private fun textOf(raw: String): String {
        var t = html(raw)
        val cut = listOf("Extraído de la Biblia", "Extraido de la Biblia", "evangeliodeldia.org")
            .map { t.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (cut != null) t = t.substring(0, cut)
        return t.lines().map { it.trim().replace(Regex("\\s+"), " ") }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    @Test
    fun eligeVersiculosConLecturasReales() {
        assumeTrue(System.getenv("LAZARUS_LIVE") == "1")
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
        val start = LocalDate.now().minusDays(7)
        for (d in 0 until 14) {
            val date = start.plusDays(d.toLong()).format(fmt)
            val rs = ArrayList<VersePicker.Reading>()
            for (part in listOf("FR", "PS", "SR")) {
                try {
                    val text = textOf(get("$base?date=$date&type=reading&content=$part&lang=SP"))
                    if (text.length < 30) continue
                    val st = html(get("$base?date=$date&type=reading_st&content=$part&lang=SP")).trim()
                    rs.add(VersePicker.Reading(st.substringBefore(' '), st, text, part == "PS"))
                } catch (e: Exception) {
                }
            }
            val ranked = VersePicker.rank(rs)
            println("=== $date · " + rs.joinToString(" | ") { it.ref })
            ranked.take(3).forEachIndexed { i, p ->
                println("  ${i + 1}. [${"%.1f".format(p.score)}] ${p.text.replace("\n", " / ")}  (${p.ref})")
            }
        }
    }
}
