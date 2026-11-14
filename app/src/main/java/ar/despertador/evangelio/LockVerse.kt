package ar.despertador.evangelio

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.random.Random

/** Un versículo para la pantalla de bloqueo: el texto y de dónde sale. */
data class Verse(val text: String, val ref: String)

/**
 * Las otras lecturas de cada día (primera lectura, salmo y segunda lectura), de la misma fuente
 * que el Evangelio: El Libro del Pueblo de Dios, vía evangeliodeldia.org. A lo largo del año
 * recorren casi toda la Biblia, así que el versículo de cada día sale de un libro distinto.
 */
object VerseRepo {
    private val PARTS = listOf("FR", "PS", "SR")

    private fun store(c: Context) = c.getSharedPreferences("verses", Context.MODE_PRIVATE)

    /** Libros que no están en las Biblias protestantes: en modo ecuménico no se usan. */
    private val DEUTERO = setOf("Tb", "Jdt", "1_M", "2_M", "Sg", "Sb", "Si", "Ba")

    private class Reading(val book: String, val ref: String, val text: String, val psalm: Boolean)

    private fun readings(c: Context, date: String): List<Reading> {
        val raw = store(c).getString("v_$date", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Reading(o.getString("b"), o.getString("r"), o.getString("t"), o.optBoolean("p"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Baja las lecturas del día si no están guardadas. Bloquea. */
    fun sync(c: Context, date: String) {
        if (store(c).contains("v_$date")) return
        val arr = JSONArray()
        for (part in PARTS) {
            try {
                val text = textOf(GospelRepo.get("${GospelRepo.BASE}?date=$date&type=reading&content=$part&lang=SP"))
                if (text.length < 30) continue
                val st = GospelRepo.html(GospelRepo.get("${GospelRepo.BASE}?date=$date&type=reading_st&content=$part&lang=SP")).trim()
                val book = st.substringBefore(' ').trimEnd('.')
                arr.put(JSONObject().put("b", book).put("r", Books.ref(st)).put("t", text).put("p", part == "PS"))
            } catch (e: Exception) {
                // Si falla una parte (por ejemplo, no hay segunda lectura), sigue con las otras.
            }
        }
        if (arr.length() > 0) store(c).edit().putString("v_$date", arr.toString()).apply()
    }

    fun prune(c: Context, keep: Set<String>) {
        val sp = store(c)
        val e = sp.edit()
        for (k in sp.all.keys) if (k.substringAfter("_") !in keep) e.remove(k)
        e.apply()
    }

    /** Texto limpio que conserva los saltos de estrofa (línea vacía entre estrofas). */
    private fun textOf(raw: String): String {
        var t = GospelRepo.html(raw)
        val cut = listOf("Extraído de la Biblia", "Extraido de la Biblia", "evangeliodeldia.org")
            .map { t.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (cut != null) t = t.substring(0, cut)
        return t.lines().map { it.trim().replace(Regex("\\s+"), " ") }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Fragmentos que sirven: una estrofa del salmo, o una oración completa de las otras lecturas. */
    private fun candidates(r: Reading): List<String> {
        val out = ArrayList<String>()
        if (r.psalm) {
            for (stanza in r.text.split(Regex("\n\\s*\n"))) {
                val lines = stanza.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty() || lines.size > 4) continue
                val s = lines.joinToString("\n")
                if (s.length in 30..230) out.add(s)
            }
        } else {
            val flat = r.text.replace(Regex("\\s*\n\\s*"), " ")
            for (s in flat.split(Regex("(?<=[.!?»])\\s+(?=[«¿¡A-ZÁÉÍÓÚÑ])"))) {
                val v = s.trim().trimStart('-', '—', '–').trim()
                if (v.length !in 40..200) continue
                if (!v.first().let { it.isUpperCase() || it == '«' || it == '¿' || it == '¡' }) continue
                out.add(v)
            }
        }
        return out
    }

    /**
     * El versículo de [date]. Es al azar, pero el mismo durante todo el día; [offset] elige otro
     * (para el botón "Otro versículo").
     */
    fun pick(c: Context, date: String, ecumenical: Boolean, offset: Int): Verse? {
        var rs = readings(c, date)
        if (ecumenical) rs = rs.filter { it.book !in DEUTERO }
        val pool = rs.flatMap { r -> candidates(r).map { Verse(it, r.ref) } }
            .ifEmpty {
                // Sin otras lecturas: una frase del Evangelio del día.
                val g = GospelRepo.cached(c, date) ?: return null
                g.text.replace("\n", " ").split(Regex("(?<=[.!?»])\\s+"))
                    .map { it.trim() }.filter { it.length in 40..200 }
                    .map { Verse(it, g.ref) }
            }
        if (pool.isEmpty()) return null
        val order = pool.indices.shuffled(Random(date.hashCode()))
        return pool[order[Math.floorMod(offset, pool.size)]]
    }
}

/** Nombres de los libros en castellano, a partir de las abreviaturas de la fuente. */
object Books {
    private val NAMES = mapOf(
        "Gn" to "Génesis", "Ex" to "Éxodo", "Lv" to "Levítico", "Nb" to "Números", "Nm" to "Números",
        "Dt" to "Deuteronomio", "Jos" to "Josué", "Jg" to "Jueces", "Jc" to "Santiago", "Rt" to "Rut",
        "1_S" to "1 Samuel", "2_S" to "2 Samuel", "1_R" to "1 Reyes", "2_R" to "2 Reyes",
        "1_Ch" to "1 Crónicas", "2_Ch" to "2 Crónicas", "Esd" to "Esdras", "Ne" to "Nehemías",
        "Tb" to "Tobías", "Jdt" to "Judit", "Est" to "Ester", "1_M" to "1 Macabeos", "2_M" to "2 Macabeos",
        "Jb" to "Job", "Ps" to "Salmo", "Pr" to "Proverbios", "Qo" to "Eclesiastés", "Ct" to "Cantar de los Cantares",
        "Sg" to "Sabiduría", "Sb" to "Sabiduría", "Si" to "Eclesiástico", "Is" to "Isaías", "Jr" to "Jeremías",
        "Lm" to "Lamentaciones", "Ba" to "Baruc", "Ez" to "Ezequiel", "Dn" to "Daniel", "Os" to "Oseas",
        "Jl" to "Joel", "Am" to "Amós", "Ab" to "Abdías", "Jon" to "Jonás", "Mi" to "Miqueas", "Na" to "Nahún",
        "Ha" to "Habacuc", "So" to "Sofonías", "Ag" to "Ageo", "Za" to "Zacarías", "Ml" to "Malaquías",
        "Mt" to "Mateo", "Mc" to "Marcos", "Lc" to "Lucas", "Jn" to "Juan", "Ac" to "Hechos",
        "Rm" to "Romanos", "1_Co" to "1 Corintios", "2_Co" to "2 Corintios", "Ga" to "Gálatas",
        "Ep" to "Efesios", "Ph" to "Filipenses", "Col" to "Colosenses", "1_Th" to "1 Tesalonicenses",
        "2_Th" to "2 Tesalonicenses", "1_Tm" to "1 Timoteo", "2_Tm" to "2 Timoteo", "Tt" to "Tito",
        "Phm" to "Filemón", "He" to "Hebreos", "1_P" to "1 Pedro", "2_P" to "2 Pedro",
        "1_Jn" to "1 Juan", "2_Jn" to "2 Juan", "3_Jn" to "3 Juan", "Jude" to "Judas", "Jd" to "Judas",
        "Ap" to "Apocalipsis",
    )

    /** "Ps 112(111),1-2.3-4." → "Salmo 112" · "3_Jn 1,5-8." → "3 Juan 1, 5-8" */
    fun ref(st: String): String {
        val s = st.trim().trimEnd('.')
        val abbr = s.substringBefore(' ')
        val rest = s.substringAfter(' ', "")
        val name = NAMES[abbr] ?: abbr.replace('_', ' ')
        if (abbr == "Ps") return "$name " + rest.substringBefore('(').substringBefore(',').trim()
        return "$name " + rest.replace(Regex(",\\s*"), ", ").trim()
    }
}

/**
 * El modo "Versículo del día": cada día pone en la pantalla de bloqueo un fondo con un versículo
 * distinto. Al desactivarlo, la pantalla de bloqueo vuelve a mostrar el fondo de inicio.
 */
object LockVerse {
    private fun sp(c: Context) = c.getSharedPreferences("lock_verse", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("on", false)

    /** Si este celular deja que una app cambie el fondo de la pantalla de bloqueo. */
    fun supported(c: Context): Boolean {
        val wm = WallpaperManager.getInstance(c)
        return wm.isWallpaperSupported && wm.isSetWallpaperAllowed
    }

    private fun offset(c: Context, date: String) = sp(c).getInt("offset_$date", 0)

    /** El versículo de hoy (bloquea: puede bajar las lecturas). */
    fun today(c: Context): Verse? {
        val date = GospelRepo.today()
        VerseRepo.sync(c, date)
        return VerseRepo.pick(c, date, Prefs.ecumenical(c), offset(c, date))
    }

    /** Activa o desactiva el modo. Bloquea. */
    fun setEnabled(c: Context, on: Boolean): Boolean {
        sp(c).edit().putBoolean("on", on).remove("applied").apply()
        return if (on) {
            schedule(c)
            update(c, force = true)
        } else {
            cancel(c)
            try {
                WallpaperManager.getInstance(c).clear(WallpaperManager.FLAG_LOCK)
            } catch (e: Exception) {
            }
            true
        }
    }

    /** Pasa al siguiente versículo del día. Bloquea. */
    fun next(c: Context): Boolean {
        val date = GospelRepo.today()
        sp(c).edit().putInt("offset_$date", offset(c, date) + 1).apply()
        return update(c, force = true)
    }

    /**
     * Pone el fondo de hoy si todavía no está puesto (o siempre, con [force]).
     * Devuelve false si no pudo (sin lecturas guardadas ni internet, o el celular no lo permite).
     */
    fun update(c: Context, force: Boolean = false): Boolean {
        if (!enabled(c)) return true
        val date = GospelRepo.today()
        val stamp = "$date/${offset(c, date)}/${Prefs.ecumenical(c)}/${Prefs.theme(c).key}"
        if (!force && sp(c).getString("applied", null) == stamp) return true
        if (!supported(c)) return false
        val v = today(c) ?: return false
        val (w, h) = screenSize(c)
        return try {
            val bmp = render(c, v, w, h)
            WallpaperManager.getInstance(c).setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
            bmp.recycle()
            // Limpia los desplazamientos de días anteriores.
            val e = sp(c).edit().putString("applied", stamp)
            for (k in sp(c).all.keys) if (k.startsWith("offset_") && k != "offset_$date") e.remove(k)
            e.apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun screenSize(c: Context): Pair<Int, Int> {
        val wm = c.getSystemService(WindowManager::class.java)
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
        return minOf(w, h) to maxOf(w, h)
    }

    /**
     * Dibuja el fondo: azul Vigilia (negro en AMOLED), el versículo en Literata, el trazo dorado
     * y la cita. Va por debajo del centro para no chocar con el reloj de la pantalla de bloqueo.
     */
    fun render(c: Context, v: Verse, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val amoled = Prefs.theme(c) == ThemeMode.AMOLED
        cv.drawColor(if (amoled) 0xFF000000.toInt() else Brand.VIGILIA)

        val maxW = (w * 0.8f).toInt()
        val text = "«${v.text}»"
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Brand.ALBA
            typeface = Fonts.text(c, 400)
        }
        // Achica la letra hasta que el versículo entre en un tercio de la pantalla.
        var size = w * 0.06f
        var layout: StaticLayout
        while (true) {
            tp.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.32f)
                .setIncludePad(false)
                .build()
            if (layout.height <= h * 0.32f || size <= w * 0.038f) break
            size *= 0.92f
        }

        val ref = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Brand.ORO
            typeface = Fonts.ui(c, 600)
            textSize = w * 0.03f
            letterSpacing = 0.16f
            textAlign = Paint.Align.CENTER
        }
        val gap = w * 0.05f
        val trazoH = w * 0.07f
        val block = layout.height + gap + trazoH + gap + ref.textSize
        val top = h * 0.62f - block / 2f

        cv.save()
        cv.translate((w - maxW) / 2f, top)
        layout.draw(cv)
        cv.restore()

        // El trazo que se levanta, corto y centrado.
        val y0 = top + layout.height + gap
        val tw = w * 0.42f
        val x0 = (w - tw) / 2f
        val low = y0 + trazoH * 0.8f
        val high = y0 + trazoH * 0.2f
        val mid = w / 2f
        val half = tw * 0.16f
        val path = Path().apply {
            moveTo(x0, low)
            lineTo(mid - half, low)
            cubicTo(mid - half * 0.35f, low, mid - half * 0.3f, high, mid, high)
            cubicTo(mid + half * 0.3f, high, mid + half * 0.35f, low, mid + half, low)
            lineTo(x0 + tw, low)
        }
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = w * 0.005f
            color = Brand.ORO
        })

        cv.drawText(v.ref.uppercase(), w / 2f, y0 + trazoH + gap + ref.textSize * 0.8f, ref)
        return bmp
    }

    // ---------- todos los días, pasada la medianoche ----------

    private fun pi(c: Context) = PendingIntent.getBroadcast(
        c, 300, Intent(c, LockVerseReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(c: Context) {
        if (!enabled(c)) return
        val t = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
        }
        c.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.timeInMillis, pi(c))
    }

    fun cancel(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(pi(c))
    }
}

/** Cambia el versículo a la medianoche. Privado: solo lo llama el aviso que programó Lazarus. */
class LockVerseReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val done = goAsync()
        Thread {
            try {
                LockVerse.update(c)
                LockVerse.schedule(c)
            } finally {
                done.finish()
            }
        }.start()
    }
}
