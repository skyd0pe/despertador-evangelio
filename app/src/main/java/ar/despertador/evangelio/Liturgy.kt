package ar.despertador.evangelio

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Color litúrgico del día: por el tiempo del año y, si el día lo indica, por la fiesta. */
object Liturgy {
    enum class Color { GREEN, VIOLET, WHITE, RED, ROSE }

    fun easter(y: Int): LocalDate {
        val a = y % 19
        val b = y / 100
        val c = y % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(y, month, day)
    }

    /** Primer domingo de Adviento: el domingo entre el 27 de noviembre y el 3 de diciembre. */
    fun adventStart(y: Int): LocalDate =
        LocalDate.of(y, 12, 25).minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).minusWeeks(3)

    /** Bautismo del Señor: el domingo siguiente al 6 de enero (fin del tiempo de Navidad). */
    fun baptism(y: Int): LocalDate =
        LocalDate.of(y, 1, 6).with(TemporalAdjusters.next(DayOfWeek.SUNDAY))

    private val RED_WORDS = listOf(
        "mártir", "martir", "apóstol", "apostol", "evangelista", "pentecostés", "pentecostes",
        "pasión", "pasion", "domingo de ramos", "viernes santo", "exaltación de la santa cruz"
    )
    private val WHITE_WORDS = listOf(
        "virgen", "santa maría", "inmaculada", "asunción", "navidad", "epifanía", "todos los santos",
        "cristo rey", "jesucristo, rey", "san josé", "natividad", "transfiguración", "anunciación",
        "presentación del señor", "nuestra señora", "sagrado corazón", "santísima trinidad",
        "cuerpo y la sangre", "ascensión", "jueves santo", "octava de pascua", "bautismo del señor"
    )

    fun color(date: LocalDate, title: String): Color {
        val t = title.lowercase()
        if (RED_WORDS.any { it in t }) return Color.RED
        if (WHITE_WORDS.any { it in t }) return Color.WHITE

        val y = date.year
        val easter = easter(y)
        val ash = easter.minusDays(46)
        val palm = easter.minusDays(7)
        val pentecost = easter.plusDays(49)
        val advent = adventStart(y)

        return when {
            !date.isBefore(advent) && date.isBefore(LocalDate.of(y, 12, 25)) ->
                if (date == advent.plusWeeks(2)) Color.ROSE else Color.VIOLET
            !date.isBefore(LocalDate.of(y, 12, 25)) -> Color.WHITE
            !date.isAfter(baptism(y)) -> Color.WHITE
            !date.isBefore(ash) && date.isBefore(palm) ->
                if (date == easter.minusWeeks(3)) Color.ROSE else Color.VIOLET
            date == palm || date == easter.minusDays(2) -> Color.RED
            date == easter.minusDays(3) -> Color.WHITE
            date.isAfter(palm) && date.isBefore(easter) -> Color.VIOLET
            date == pentecost -> Color.RED
            !date.isBefore(easter) && date.isBefore(pentecost) -> Color.WHITE
            else -> Color.GREEN
        }
    }

    fun colorName(c: Color) = when (c) {
        Color.GREEN -> "verde"
        Color.VIOLET -> "morado"
        Color.WHITE -> "blanco"
        Color.RED -> "rojo"
        Color.ROSE -> "rosa"
    }

    fun dateOf(key: String): LocalDate = try {
        LocalDate.of(key.substring(0, 4).toInt(), key.substring(4, 6).toInt(), key.substring(6, 8).toInt())
    } catch (e: Exception) {
        LocalDate.now()
    }
}
