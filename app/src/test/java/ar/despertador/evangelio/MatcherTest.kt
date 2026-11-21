package ar.despertador.evangelio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {

    private val gospel = """
        En aquel tiempo, Jesús llegó a Cafarnaúm y, apenas entró en la sinagoga, se puso a enseñar.
        Todos estaban asombrados de su enseñanza, porque hablaba con autoridad.
        Había en la sinagoga un hombre poseído por un espíritu impuro, que gritó: «¿Qué tenemos que ver con vos, Jesús de Nazaret?»
    """.trimIndent()

    private fun matcher() = Matcher(gospel)

    @Test fun `norm saca acentos, signos y mayusculas`() {
        assertEquals("cafarnaum", Matcher.norm("Cafarnaúm,"))
        assertEquals("que", Matcher.norm("«¿Qué"))
        assertEquals("", Matcher.norm("—"))
    }

    @Test fun `phonetic unifica b-v, s-z-c, ll-y y h muda`() {
        assertEquals(Matcher.phonetic("bos"), Matcher.phonetic("vos"))
        assertEquals(Matcher.phonetic("casa"), Matcher.phonetic("kasa"))
        assertEquals(Matcher.phonetic("cielo"), Matcher.phonetic("sielo"))
        assertEquals(Matcher.phonetic("calle"), Matcher.phonetic("caye"))
        assertEquals(Matcher.phonetic("hombre"), Matcher.phonetic("ombre"))
    }

    @Test fun `lectura exacta completa llega al 100 por ciento y termina`() {
        val m = matcher()
        m.commit(gospel)
        assertEquals(1f, m.score(), 0.001f)
        assertTrue(m.finished)
    }

    @Test fun `ignora acentos y mayusculas`() {
        val m = matcher()
        m.commit(gospel.lowercase().replace("ú", "u").replace("é", "e").replace("í", "i"))
        assertTrue(m.score() > 0.95f)
    }

    @Test fun `sin leer nada el puntaje es cero y no termina`() {
        val m = matcher()
        assertEquals(0f, m.score(), 0f)
        assertFalse(m.finished)
    }

    @Test fun `texto vacio no rompe`() {
        val m = Matcher("")
        assertEquals(0f, m.score(), 0f)
        assertFalse(m.finished)
        m.commit("hola")
        assertEquals(0f, m.score(), 0f)
    }

    @Test fun `texto sin relacion no suma`() {
        val m = matcher()
        m.commit("el perro corre por el parque mientras llueve fuerte esta tarde")
        assertTrue(m.score() < 0.15f)
        assertFalse(m.finished)
    }

    @Test fun `tolera palabras omitidas pero las marca como salteadas`() {
        val m = matcher()
        m.commit("En aquel tiempo Jesús llegó a Cafarnaúm apenas entró en la sinagoga se puso a enseñar")
        // faltó la "y," del medio: debe quedar marcada como salteada (2) y el resto leído
        val skipped = m.words.count { it.state == 2 }
        assertEquals(1, skipped)
        assertTrue(m.words.count { it.state == 1 } >= 12)
    }

    @Test fun `no salta mas de seis palabras de una`() {
        val m = matcher()
        // "poseído" está más allá de la ventana de búsqueda (6 palabras)
        m.commit("poseído por un espíritu impuro")
        assertEquals(0, m.ptr)
        assertEquals(0f, m.score(), 0f)
    }

    @Test fun `lectura por tramos sigue avanzando`() {
        val m = matcher()
        m.commit("En aquel tiempo Jesús llegó a")
        val p1 = m.ptr
        m.commit("Cafarnaúm y apenas entró en la sinagoga")
        assertTrue(m.ptr > p1)
        assertEquals(0, m.words.count { it.state == 2 })
    }

    @Test fun `tolera confusiones tipicas de b-v y s-z`() {
        val m = Matcher("Vamos a bajar al valle para ver el cielo y la zona")
        m.commit("bamos a vajar al balle para ber el sielo y la sona")
        assertEquals(1f, m.score(), 0.001f)
    }

    @Test fun `nombre propio mal escrito por el reconocedor cuenta como leido`() {
        val m = matcher()
        m.commit("En aquel tiempo Jesús llegó a Cafarnaun y apenas entró en la sinagoga")
        val cafarnaum = m.words.first { it.norm == "cafarnaum" }
        assertEquals(1, cafarnaum.state)
    }

    @Test fun `nombre propio ignorado no se marca como salteado`() {
        val m = matcher()
        // el reconocedor no dijo nada parecido a Cafarnaúm
        m.commit("En aquel tiempo Jesús llegó a y apenas entró en la sinagoga")
        val cafarnaum = m.words.first { it.norm == "cafarnaum" }
        assertEquals(1, cafarnaum.state)
    }

    @Test fun `una palabra comun distinta no se acepta`() {
        val m = Matcher("la sinagoga estaba llena")
        m.commit("la casa estaba llena")
        assertTrue(m.words[1].state != 1)
    }

    @Test fun `provisional no modifica el estado`() {
        val m = matcher()
        val prov = m.provisional("En aquel tiempo Jesús")
        assertEquals(setOf(0, 1, 2, 3), prov)
        assertEquals(0, m.ptr)
        assertEquals(0f, m.score(), 0f)
    }

    @Test fun `reset vuelve a cero`() {
        val m = matcher()
        m.commit(gospel)
        m.reset()
        assertEquals(0, m.ptr)
        assertEquals(0f, m.score(), 0f)
        assertFalse(m.finished)
    }

    @Test fun `el umbral del 80 por ciento se alcanza leyendo con algunos errores`() {
        val m = matcher()
        // se leen todas las palabras salvo cada décima
        val words = gospel.split(Regex("\\s+"))
        m.commit(words.filterIndexed { i, _ -> i % 10 != 5 }.joinToString(" "))
        assertTrue("puntaje=${m.score()}", m.score() >= 0.80f)
        assertTrue(m.finished)
    }
}
