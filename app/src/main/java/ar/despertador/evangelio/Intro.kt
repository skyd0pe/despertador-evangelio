package ar.despertador.evangelio

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView

/** Presentación de la primera vez. Al tocar "Empezar" se desvanece y llama a [onDone]. */
fun Activity.buildIntro(p: Palette, onDone: () -> Unit): View {
    val c = col(this).apply { setPadding(dp(28), dp(56), dp(28), dp(36)) }
    c.add(eyebrow("Lazarus", p).apply { letterSpacing = 0.24f })
    c.add(label("Bienvenido", 40f, p.ink, Fonts.display(this, 400, 96)), 18)
    c.add(label("«¡Lázaro, ven afuera!»", 22f, p.ink, Fonts.text(this)), 14)
    c.add(label("Juan 11, 43", 14f, p.accent, Fonts.ui(this, 600)), 4)
    c.add(TrazoView(this, p.accent, 36), 20)

    c.add(label(
        "Lazarus es un despertador que no se apaga hasta que leés en voz alta el Evangelio del día.",
        18f, p.ink
    ).apply { setLineSpacing(0f, 1.4f) }, 20)
    c.add(label(
        "Levantarse temprano es la primera victoria del día. Acá esa victoria empieza escuchando la Palabra, " +
            "como Lázaro, que oyó la voz de Jesús y se levantó.",
        16f, p.muted
    ).apply { setLineSpacing(0f, 1.45f) }, 12)

    val note = card(p)
    note.add(eyebrow("Un mensaje de quien hizo Lazarus", p))
    note.add(label(
        "¡Gracias por probar Lazarus! Espero que te ayude a conquistar un mejor descanso, como me ha " +
            "ayudado también a mí. Cualquier duda o aporte que creas que puede ayudar a que la app mejore, " +
            "me encantaría escucharlo.",
        17f, p.ink, Fonts.text(this)
    ).apply { setLineSpacing(0f, 1.45f) }, 10)
    c.add(note, 28)

    val overlay = ScrollView(this).apply {
        setBackgroundColor(p.paper)
        isClickable = true
        isFillViewport = true
        isVerticalScrollBarEnabled = false
    }
    c.add(pill("Empezar", p.primaryBg, p.primaryFg) {
        Prefs.setIntroSeen(this)
        overlay.animate().alpha(0f).setDuration(220).withEndAction {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            onDone()
        }.start()
    }, 32)
    overlay.addView(c)
    return overlay
}
