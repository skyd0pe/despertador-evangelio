package ar.despertador.evangelio

import java.text.Normalizer
import java.util.Locale

/**
 * Sigue la lectura: compara lo que reconoce el micrófono con el texto, palabra por
 * palabra, con tolerancia (acentos, una letra de diferencia, palabras salteadas).
 */
class Matcher(text: String) {

    class Word(val start: Int, val end: Int, val norm: String) {
        /** 0 = sin leer, 1 = leída, 2 = salteada */
        var state = 0
    }

    val words = ArrayList<Word>()

    /** Próxima palabra esperada. */
    var ptr = 0
        private set

    init {
        Regex("\\S+").findAll(text).forEach { m ->
            val n = norm(m.value)
            if (n.isNotEmpty()) words.add(Word(m.range.first, m.range.last + 1, n))
        }
    }

    val finished: Boolean get() = words.isNotEmpty() && ptr >= words.size - 1

    fun score(): Float =
        if (words.isEmpty()) 0f else words.count { it.state == 1 }.toFloat() / words.size

    fun reset() {
        ptr = 0
        words.forEach { it.state = 0 }
    }

    /** Aplica un tramo ya confirmado por el reconocedor. */
    fun commit(heard: String) {
        val (p, marks) = align(split(heard), ptr)
        for ((i, s) in marks) words[i].state = s
        ptr = p
    }

    /** Palabras que se marcarían con un resultado parcial, sin aplicarlo. */
    fun provisional(heard: String): Set<Int> =
        align(split(heard), ptr).second.filter { it.second == 1 }.map { it.first }.toSet()

    private fun split(t: String): List<String> =
        t.split(Regex("\\s+")).map { norm(it) }.filter { it.isNotEmpty() }

    private fun align(heard: List<String>, start: Int): Pair<Int, List<Pair<Int, Int>>> {
        var p = start
        val marks = ArrayList<Pair<Int, Int>>()
        for (h in heard) {
            val end = minOf(p + LOOK, words.size)
            for (k in p until end) {
                if (same(words[k].norm, h)) {
                    for (s in p until k) marks.add(s to 2)
                    marks.add(k to 1)
                    p = k + 1
                    break
                }
            }
        }
        return p to marks
    }

    companion object {
        private const val LOOK = 6

        fun norm(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]"), "")

        private fun same(exp: String, heard: String): Boolean {
            if (exp == heard) return true
            if (exp.length >= 5 && lev(exp, heard) <= 1) return true
            if (exp.length >= 8 && lev(exp, heard) <= 2) return true
            return false
        }

        private fun lev(a: String, b: String): Int {
            if (kotlin.math.abs(a.length - b.length) > 2) return 9
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]
                d[0] = i
                for (j in 1..b.length) {
                    val t = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = t
                }
            }
            return d[b.length]
        }
    }
}
