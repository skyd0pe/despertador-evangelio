package ar.despertador.evangelio

/**
 * Elige el pasaje más significativo de las lecturas del día (sin el Evangelio).
 *
 * La fuente no trae el estribillo del salmo, así que se puntúa cada fragmento:
 * gana lo que se entiende solo (no arranca con "Y", "Entonces", "Él"…), habla de Dios o a la
 * persona, y no es narración ("dijo", "llegó", nombres de lugares). Las palabras de Dios dichas
 * en primera persona y las estrofas completas del salmo suman.
 *
 * No usa nada de Android, así se puede probar con lecturas reales fuera del teléfono.
 */
object VersePicker {

    data class Reading(val book: String, val ref: String, val text: String, val psalm: Boolean)
    data class Pick(val text: String, val ref: String, val score: Double)

    /** Ordenados del más significativo al menos. */
    fun rank(readings: List<Reading>): List<Pick> {
        val all = ArrayList<Pick>()
        for (r in readings) {
            for (cand in candidates(r)) {
                val clean = tidy(cand.text)
                if (clean.length !in MIN..MAX) continue
                all.add(Pick(clean, r.ref, score(clean, r.psalm, cand.quote)))
            }
        }
        // Sin repetidos (un fragmento puede salir como oración y como cita).
        return all.sortedByDescending { it.score }.distinctBy { norm(it.text) }
    }

    private const val MIN = 35
    private const val MAX = 210

    private class Cand(val text: String, val quote: Boolean)

    private fun candidates(r: Reading): List<Cand> {
        val out = ArrayList<Cand>()
        if (r.psalm) {
            val stanzas = r.text.split(Regex("\n\\s*\n")).map { s -> s.lines().map { it.trim() }.filter { it.isNotEmpty() } }
            for (lines in stanzas) {
                if (lines.isEmpty()) continue
                // La estrofa entera, si es una idea completa y no muy larga.
                if (lines.size <= 5) out.add(Cand(lines.joinToString("\n"), false))
                // Y cada oración de la estrofa, conservando los cortes de verso.
                out.addAll(sentences(lines.joinToString("\n")).map { Cand(it, false) })
            }
        } else {
            val flat = r.text.replace(Regex("\\s*\n\\s*"), " ")
            val ss = sentences(flat)
            for ((i, s) in ss.withIndex()) {
                out.add(Cand(s, false))
                // Una oración corta junto con la siguiente suele ser la idea completa.
                if (s.length < 80 && i + 1 < ss.size) out.add(Cand("$s ${ss[i + 1]}", false))
            }
            // Lo que Dios (o Jesús) dice entre comillas, sin la narración de alrededor.
            for (m in Regex("«([^«»]{30,220})»").findAll(flat)) {
                out.addAll(sentences(m.groupValues[1]).map { Cand(it, true) })
                out.add(Cand(m.groupValues[1].trim(), true))
            }
        }
        return out
    }

    /** Corta en oraciones; respeta los saltos de línea que haya dentro. */
    private fun sentences(t: String): List<String> =
        t.split(Regex("(?<=[.!?])[»”\"]?\\s+(?=[«¿¡\"“]?[A-ZÁÉÍÓÚÑ])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Saca encabezados de carta ("Hermanos:") y comillas sueltas. */
    private fun tidy(s: String): String {
        var t = s.trim()
        t = t.replace(Regex("^(Queridos hermanos|Hermanos|Queridos|Carísimos|Hijos míos|Hijitos|Amados)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        t = t.trim('«', '»', '"', '“', '”', ' ', '—', '–', '-')
        // Comillas desparejas dentro: mejor sacarlas.
        if (t.count { it == '«' } != t.count { it == '»' }) t = t.replace("«", "").replace("»", "")
        if (t.isNotEmpty() && t[0].isLowerCase()) t = t[0].uppercase() + t.substring(1)
        return t.trim()
    }

    private val CONNECTOR = Regex(
        "^(Y|E|Pero|Mas|Entonces|Por eso|Por esto|Porque|Pues|Así|Asimismo|Luego|Después|Cuando|Mientras|" +
            "Ellos|Ellas|Él|El que|Ella|Este|Esta|Estos|Estas|Esto|Eso|Aquel|Aquella|Aquellos|Allí|Ahí|Aquí|" +
            "De modo|De manera|De este modo|Por lo tanto|Sin embargo|Además|También|Al ver|Al oír|Al llegar|" +
            "En aquel|En aquellos|En ese|En esos|Dicho esto|Habiendo|Tras|Apenas|Inmediatamente|Enseguida|Les|Le|Lo|La|Los|Las|Se)\\b",
        RegexOption.IGNORE_CASE
    )

    private val NARRATIVE = Regex(
        "\\b(dijo|dijeron|respondió|respondieron|contestó|preguntó|preguntaron|fue|fueron|llegó|llegaron|salió|" +
            "salieron|entró|entraron|vino|vinieron|había|habían|era|eran|estaba|estaban|se acercó|tomó|tomaron|" +
            "partió|subió|bajó|envió|mandó|ordenó|reinó|reinado|año|años|hijo de|hija de)\\b",
        RegexOption.IGNORE_CASE
    )

    private val HOLY = Regex(
        "\\b(Señor|Dios|Jesús|Cristo|Jesucristo|Espíritu|amor|ama|aman|amó|fe|gracia|paz|vida|salva|salvación|" +
            "salvador|corazón|misericordia|esperanza|luz|alegría|alégrense|gozo|justicia|perdón|perdona|Palabra|" +
            "Reino|verdad|fiel|fidelidad|bendito|bendice|bendición|feliz|felices|eterno|eterna|gloria|santo|" +
            "confía|confíen|confianza|refugio|roca|pastor|fuerza|consuelo|libera|cuida|guarda|ayuda|camino|" +
            "bondad|ternura|compasión|clemente|promesa|resurrección|cielo)\\b",
        RegexOption.IGNORE_CASE
    )

    private val STRONG_START = Regex(
        "^(El Señor|Dios|Feliz|Felices|Bendito|Bendice|Alaben|Alabado|Den gracias|Canten|Aclamen|Yo soy|Yo estoy|" +
            "No teman|No temas|No tengas miedo|Confía|Confíen|Busquen|Vuelvan|Escuchen|Escucha|Alégrense|Amen|Ámense|" +
            "Tengan|Sean|Permanezcan|Velen|Ten|Mi alma|Señor|Tú eres|Tu palabra|Tus palabras|Grande es|Qué bueno|" +
            "Cuán|Gusten|Lávense|Purifícame|Crea en mí|Todo lo puedo|Nada podrá|Si Dios)\\b",
        RegexOption.IGNORE_CASE
    )

    private val YOU = Regex("\\b(tú|te|ti|tu|tus|contigo|vos|ustedes|les|su corazón|ustedes mismos)\\b", RegexOption.IGNORE_CASE)

    private val ALLOWED_CAPS = setOf(
        "Dios", "Señor", "Jesús", "Cristo", "Jesucristo", "Espíritu", "Santo", "Padre", "Hijo", "Reino", "Palabra",
        "Altísimo", "Dios,", "Señor,", "Ley", "Él", "Tú", "Yo", "Israel", "Sión", "Jerusalén",
    )

    private fun score(t: String, psalm: Boolean, quote: Boolean): Double {
        var s = 0.0
        if (CONNECTOR.containsMatchIn(t)) s -= 7
        if (STRONG_START.containsMatchIn(t)) s += 3
        s += minOf(HOLY.findAll(t).count(), 4) * 1.6
        if (YOU.containsMatchIn(t)) s += 1.0
        if (quote) s += 2.0
        if (psalm) s += 1.2
        s -= minOf(NARRATIVE.findAll(t).count(), 3) * 2.2
        if (Regex("^Yo,\\s+\\p{Lu}").containsMatchIn(t)) s -= 5 // "Yo, Pablo…"
        // Nombres propios en medio de la frase (personas, lugares): piden contexto.
        // No cuenta la mayúscula al empezar oración o verso.
        var names = 0
        var prevEnd = -1
        var prevTok = ""
        for (m in Regex("\\S+").findAll(t)) {
            if (prevEnd >= 0) {
                val gap = t.substring(prevEnd, m.range.first)
                val startOfLine = gap.contains('\n') || prevTok.last() in ".!?:«"
                val c = m.value.trim('«', '»', ',', '.', ';', ':', '¡', '!', '¿', '?', '"', '(', ')')
                if (!startOfLine && c.isNotEmpty() && c[0].isUpperCase() && c !in ALLOWED_CAPS) names++
            }
            prevEnd = m.range.last + 1
            prevTok = m.value
        }
        s -= minOf(names, 4) * 1.5
        // Tiene que cerrar como oración completa.
        if (!Regex("[.!?»]$").containsMatchIn(t)) s -= 4
        if (t.contains(':') && !quote) s -= 1.0
        // Largo cómodo para leer en la pantalla de bloqueo.
        s -= when {
            t.length < 50 -> 1.5
            t.length <= 170 -> 0.0
            else -> (t.length - 170) / 20.0
        }
        if (t.contains('?')) s -= 0.5
        return s
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-záéíóúñ]"), "")
}
