package ar.despertador.evangelio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var p: Palette
    private var tab = 0

    private lateinit var pages: List<ScrollView>
    private lateinit var tabs: List<TextView>
    private lateinit var ribbon: RibbonView

    // Alarmas
    private lateinit var tvNext: TextView
    private lateinit var alarmsBox: LinearLayout
    private lateinit var tvPreviewRef: TextView
    private lateinit var tvPreviewTitle: TextView

    // Evangelio
    private lateinit var tvGRubric: TextView
    private lateinit var tvGDate: TextView
    private lateinit var tvGHeading: TextView
    private lateinit var tvGRefNums: TextView
    private lateinit var tvGText: TextView
    private var gospel: Gospel? = null

    // Ajustes
    private lateinit var permBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        p = Palette(this)
        setTheme(p.activityTheme)
        super.onCreate(savedInstanceState)
        p.applyTo(this)
        tab = savedInstanceState?.getInt("tab") ?: 0
        buildUi()
        showTab(tab)
        loadGospel(force = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        refreshAlarms()
        refreshPermissions()
    }

    private val ecumenical get() = Prefs.ecumenical(this)

    // =====================================================================
    // Estructura: tres páginas y una barra de pestañas abajo
    // =====================================================================

    private fun buildUi() {
        pages = listOf(page(alarmsPage()), page(gospelPage()), page(settingsPage()))
        val content = FrameLayout(this)
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }

        ribbon = RibbonView(this).apply { color = p.rubric }
        content.addView(ribbon, FrameLayout.LayoutParams(dp(22), dp(96), Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(32)
        })

        val nav = row(this).apply { setPadding(dp(12), dp(4), dp(12), dp(10)) }
        tabs = listOf("Alarmas", "Evangelio", "Ajustes").mapIndexed { i, name ->
            label(name, 15f, p.muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(10))
                setOnClickListener { showTab(i) }
            }
        }
        tabs.forEach { nav.add(it, weight = 1f) }

        val root = col(this).apply { setBackgroundColor(p.paper) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(hairline(p))
        root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun page(inner: View) = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(inner)
    }

    private fun pageColumn() = col(this).apply { setPadding(dp(28), dp(40), dp(28), dp(36)) }

    private fun showTab(i: Int) {
        tab = i
        pages.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, t ->
            val on = k == i
            t.setTextColor(if (on) p.ink else p.muted)
            t.typeface = if (on) Fonts.text(this, 600) else Fonts.text(this)
            val text = t.text.toString()
            val sb = SpannableStringBuilder(text)
            if (on) sb.setSpan(android.text.style.UnderlineSpan(), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            t.text = sb
        }
        ribbon.visibility = if (i == 2) View.GONE else View.VISIBLE
    }

    // =====================================================================
    // Página 1: alarmas
    // =====================================================================

    private fun alarmsPage(): View {
        val c = pageColumn()
        c.add(label("LAZARUS", 40f, p.ink, Fonts.display(this, 600)).apply { letterSpacing = 0.08f })
        c.add(label("«Lázaro, sal fuera» · Jn 11, 43", 15f, p.rubric, Fonts.italic(this)), 2)

        tvNext = label("", 15f, p.muted)
        c.add(tvNext, 24)

        val head = row(this)
        head.add(rubric("Alarmas", p), weight = 1f)
        head.add(textButton("+ Agregar", p.rubric) { addAlarm() }, matchWidth = false)
        c.add(head, 22)
        c.add(hairline(p, p.ink))

        alarmsBox = col(this)
        c.add(alarmsBox)

        c.add(label("Tocá la hora para cambiarla. Mantené apretada una alarma para borrarla.", 13f, p.muted, Fonts.italic(this)), 12)
        c.add(bookButton("Probar ahora", p.ink, p.ink, filled = false) { testNow() }, 18)

        val preview = col(this).apply {
            setOnClickListener { showTab(1) }
            isClickable = true
        }
        tvPreviewTitle = rubric("Evangelio de hoy", p)
        tvPreviewRef = label("Cargando…", 18f, p.ink, Fonts.text(this, 600))
        preview.add(tvPreviewTitle)
        preview.add(tvPreviewRef, 4)
        preview.add(label("Leer ahora →", 14f, p.muted, Fonts.italic(this)), 4)
        c.add(preview, 30)
        return c
    }

    private fun refreshAlarms() {
        val ok = AlarmScheduler.scheduleAll(this)
        alarmsBox.removeAllViews()
        AlarmStore.all(this).forEach { alarmsBox.add(alarmEntry(it)) }

        val next = AlarmScheduler.next(this)
        when {
            !ok -> {
                tvNext.text = "No se pueden programar las alarmas: falta un permiso (en Ajustes)."
                tvNext.setTextColor(p.rubric)
            }
            next == null -> {
                tvNext.text = "No hay alarmas activadas."
                tvNext.setTextColor(p.muted)
            }
            else -> {
                val (day, rest) = whenText(next.second)
                val sb = SpannableStringBuilder("Próxima: ")
                val st = sb.length
                sb.append(day)
                sb.setSpan(ForegroundColorSpan(p.ink), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(", $rest")
                tvNext.text = sb
                tvNext.setTextColor(p.muted)
            }
        }
    }

    /** "mañana a las 6:30" y "en 5 h 41 min". */
    private fun whenText(t: Long): Pair<String, String> {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance()
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        fun same(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val day = when {
            same(cal, today) -> "hoy"
            same(cal, tomorrow) -> "mañana"
            else -> "el " + listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")[Alarm.dayIndex(cal)]
        }
        val hm = "${cal.get(Calendar.HOUR_OF_DAY)}:${String.format(Locale.US, "%02d", cal.get(Calendar.MINUTE))}"
        val mins = ((t - System.currentTimeMillis()) / 60000).toInt()
        return "$day a las $hm" to "en ${mins / 60} h ${mins % 60} min"
    }

    private fun alarmEntry(a: Alarm): View {
        val e = col(this).apply {
            setPadding(0, dp(14), 0, dp(14))
            setOnLongClickListener { confirmDelete(a); true }
        }
        val top = row(this)
        val time = "${a.hour}:${String.format(Locale.US, "%02d", a.minute)}"
        top.add(label(time, 48f, if (a.enabled) p.ink else p.faint, Fonts.display(this, 600)).apply {
            includeFontPadding = false
            setOnClickListener { editTime(a) }
            setOnLongClickListener { confirmDelete(a); true }
        }, weight = 1f)
        val sw = Switch(this).apply {
            isChecked = a.enabled
            contentDescription = "Activar alarma de las $time"
            setOnCheckedChangeListener { _, on ->
                AlarmStore.upsert(this@MainActivity, a.copy(enabled = on))
                if (on) prefetch()
                refreshAlarms()
            }
        }
        tintControls(p, sw)
        top.add(sw, matchWidth = false)
        e.add(top)

        val days = row(this)
        for (i in 0..6) {
            val on = a.hasDay(i)
            days.add(label(Alarm.DAY_LETTERS[i], 15f, if (on) p.rubric else p.faint, Fonts.text(this, if (on) 600 else 400)).apply {
                setPadding(0, dp(8), dp(14), dp(8))
                contentDescription = Alarm.DAY_NAMES[i] + if (on) ", activado" else ", desactivado"
                setOnClickListener {
                    AlarmStore.upsert(this@MainActivity, a.copy(days = a.days xor (1 shl i)))
                    refreshAlarms()
                }
            }, matchWidth = false)
        }
        days.add(label(a.daysText().replaceFirstChar { it.lowercase() }, 14f, p.muted, Fonts.italic(this)).apply {
            gravity = Gravity.END
        }, weight = 1f)
        e.add(days, 4)

        val wrap = col(this)
        wrap.add(e)
        wrap.add(hairline(p))
        return wrap
    }

    private fun editTime(a: Alarm) {
        TimePickerDialog(this, p.dialogTheme, { _, h, m ->
            AlarmStore.upsert(this, a.copy(hour = h, minute = m, enabled = true))
            prefetch()
            refreshAlarms()
        }, a.hour, a.minute, true).show()
    }

    private fun addAlarm() {
        TimePickerDialog(this, p.dialogTheme, { _, h, m ->
            AlarmStore.create(this, h, m)
            prefetch()
            refreshAlarms()
        }, 7, 0, true).show()
    }

    private fun confirmDelete(a: Alarm) {
        AlertDialog.Builder(this, p.dialogTheme)
            .setMessage("¿Borrar la alarma de las ${a.hour}:${String.format(Locale.US, "%02d", a.minute)}?")
            .setPositiveButton("Borrar") { _, _ ->
                AlarmScheduler.cancel(this, a.id)
                AlarmStore.delete(this, a.id)
                refreshAlarms()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun testNow() {
        AlarmService.start(this)
        startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // =====================================================================
    // Página 2: Evangelio de hoy
    // =====================================================================

    private fun gospelPage(): View {
        val c = pageColumn()
        tvGRubric = rubric("Evangelio del día", p).apply { setPadding(0, 0, dp(44), 0) }
        tvGDate = label("", 14f, p.muted, Fonts.italic(this))
        c.add(tvGRubric)
        c.add(tvGDate, 2)
        c.add(hairline(p), 18)

        val head = row(this).apply { gravity = Gravity.BOTTOM }
        tvGHeading = label("", 15f, p.rubric, Fonts.italic(this))
        tvGRefNums = label("", 15f, p.rubric, Fonts.text(this, 600))
        head.add(tvGHeading, weight = 1f)
        head.add(tvGRefNums, matchWidth = false)
        c.add(head, 14)

        tvGText = label("Cargando…", Prefs.fontSize(this).toFloat(), p.ink).apply { setLineSpacing(0f, 1.5f) }
        c.add(tvGText, 12)

        c.add(textButton("Actualizar", p.rubric) { loadGospel(force = true) }, 18)
        c.add(label(
            "Texto bíblico: El Libro del Pueblo de Dios, vía evangeliodeldia.org. " +
                "Se baja la noche anterior, así suena aunque no haya internet.",
            13f, p.muted, Fonts.italic(this)
        ), 6)
        return c
    }

    private fun showGospel(g: Gospel?) {
        gospel = g
        if (g == null) {
            tvGText.text = "No se pudo bajar. Revisá la conexión y tocá Actualizar."
            tvPreviewRef.text = "Sin conexión"
            return
        }
        val date = Liturgy.dateOf(g.date)
        val dateText = date.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale("es", "AR")))
            .replaceFirstChar { it.uppercase() }
        val book = g.ref.substringBefore(' ')
        tvGRefNums.text = g.ref.substringAfter(' ', "")
        tvPreviewRef.text = g.ref
        if (ecumenical) {
            ribbon.color = p.rubric
            tvGRubric.text = dateText.uppercase()
            tvGDate.visibility = View.GONE
            tvGHeading.text = "Evangelio de $book"
        } else {
            ribbon.color = p.liturgical(Liturgy.color(date, g.title))
            tvGRubric.text = g.title.ifBlank { "Evangelio del día" }.uppercase()
            tvGDate.visibility = View.VISIBLE
            tvGDate.text = dateText
            tvGHeading.text = "✠  Lectura del santo Evangelio según san $book"
        }
        tvGText.text = withDropCap(g.text, p, tvGText.textSize)
    }

    private fun prefetch() {
        Thread {
            GospelRepo.fetch(this, GospelRepo.today())
            GospelRepo.fetch(this, GospelRepo.tomorrow())
        }.start()
    }

    private fun loadGospel(force: Boolean) {
        Thread {
            val date = GospelRepo.today()
            val g = (if (force) null else GospelRepo.cached(this, date)) ?: GospelRepo.fetch(this, date)
            GospelRepo.fetch(this, GospelRepo.tomorrow())
            runOnUiThread { showGospel(g) }
        }.start()
    }

    // =====================================================================
    // Página 3: ajustes
    // =====================================================================

    private fun section(c: LinearLayout, title: String, topDp: Int = 30) {
        c.add(rubric(title, p), topDp)
        c.add(hairline(p, p.ink), 6)
    }

    private fun settingsPage(): View {
        val c = pageColumn()
        c.add(label("Ajustes", 40f, p.ink, Fonts.display(this, 600)))

        // Apariencia
        section(c, "Apariencia")
        c.add(label("Tema", 16f, p.ink, Fonts.text(this, 600)), 14)
        c.add(choices(ThemeMode.values().map { it to it.label }, Prefs.theme(this)) {
            Prefs.setTheme(this, it)
            recreate()
        }, 4)
        c.add(label("Sistema sigue el modo nocturno del celular. AMOLED usa negro puro y gasta menos batería.", 13f, p.muted, Fonts.italic(this)), 2)

        val tvFont = label("", 16f, p.ink, Fonts.text(this, 600))
        val sample = label("", Prefs.fontSize(this).toFloat(), p.ink).apply {
            setLineSpacing(0f, 1.5f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(p.surface)
                cornerRadius = dpf(3f)
            }
        }
        val sampleText = "Bienaventurados los pobres en espíritu: porque de ellos es el reino de los cielos."
        fun showFont(v: Int) {
            tvFont.text = "Letra del Evangelio: $v"
            sample.textSize = v.toFloat()
            sample.text = withDropCap(sampleText, p, sample.textSize)
        }
        showFont(Prefs.fontSize(this))
        c.add(tvFont, 22)
        c.add(SeekBar(this).apply {
            max = 8
            progress = (Prefs.fontSize(this@MainActivity) - 16) / 2
            tintSeek(p, this)
            setOnSeekBarChangeListener(seek { v ->
                val size = 16 + v * 2
                showFont(size)
                Prefs.setFontSize(this@MainActivity, size)
                tvGText.textSize = size.toFloat()
                gospel?.let { tvGText.text = withDropCap(it.text, p, tvGText.textSize) }
            })
        }, 6)
        c.add(label("Así se ve al despertarte:", 13f, p.muted, Fonts.italic(this)), 8)
        c.add(sample, 6)

        // Fe
        section(c, "Tradición")
        val ecuRow = row(this)
        ecuRow.add(label("Modo ecuménico", 16f, p.ink, Fonts.text(this, 600)), weight = 1f)
        val sw = Switch(this).apply {
            isChecked = Prefs.ecumenical(this@MainActivity)
            contentDescription = "Modo ecuménico"
            setOnCheckedChangeListener { _, on ->
                Prefs.setEcumenical(this@MainActivity, on)
                showGospel(gospel)
            }
        }
        tintControls(p, sw)
        ecuRow.add(sw, matchWidth = false)
        c.add(ecuRow, 14)
        c.add(label(
            "Oculta el calendario litúrgico, el color de cada tiempo y las fórmulas propias de la misa. " +
                "Quedan solo el texto bíblico y sus citas.",
            13f, p.muted, Fonts.italic(this)
        ), 2)

        // Lectura
        section(c, "Lectura")
        val tvTol = label("", 16f, p.ink, Fonts.text(this, 600))
        fun showTol(v: Int) { tvTol.text = "Cuánto hay que leer bien: $v %" }
        showTol(Prefs.threshold(this))
        c.add(tvTol, 14)
        c.add(SeekBar(this).apply {
            max = 7
            progress = (Prefs.threshold(this@MainActivity) - 60) / 5
            tintSeek(p, this)
            setOnSeekBarChangeListener(seek { v ->
                val pct = 60 + v * 5
                showTol(pct)
                Prefs.setThreshold(this@MainActivity, pct)
            })
        }, 6)
        c.add(label("Si te quedás callado, vuelve a sonar a los", 16f, p.ink, Fonts.text(this, 600)), 18)
        c.add(choices(listOf(15 to "15 s", 30 to "30 s", 60 to "60 s"), Prefs.silenceSec(this)) {
            Prefs.setSilence(this, it)
        }, 4)

        // Sonido
        section(c, "Sonido")
        c.add(label("Volumen progresivo", 16f, p.ink, Fonts.text(this, 600)), 14)
        c.add(choices(listOf(0 to "No", 30 to "30 s", 60 to "1 min", 120 to "2 min"), Prefs.rampSec(this)) {
            Prefs.setRamp(this, it)
        }, 4)
        c.add(label("Arranca bajito y sube hasta el máximo. Si vuelve a sonar por silencio, suena fuerte de entrada.", 13f, p.muted, Fonts.italic(this)), 2)

        // Permisos
        section(c, "Permisos")
        permBox = col(this)
        c.add(permBox, 4)

        // Acerca de
        section(c, "Salida de emergencia")
        c.add(label(
            "Si el micrófono falla o tenés una urgencia: en la pantalla de la alarma, mantené apretado " +
                "“Emergencia” durante 8 segundos.",
            14f, p.muted
        ), 12)
        return c
    }

    private fun seek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
            if (fromUser) onChange(v)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun <T> choices(opts: List<Pair<T, String>>, current: T, onPick: (T) -> Unit): RadioGroup {
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val ids = opts.map { View.generateViewId() }
        opts.forEachIndexed { i, (value, text) ->
            rg.addView(RadioButton(this).apply {
                id = ids[i]
                this.text = text
                textSize = 15f
                typeface = Fonts.text(this@MainActivity)
                setTextColor(p.ink)
                isChecked = value == current
                setPadding(dp(2), 0, dp(10), 0)
                tintRadio(p, this)
            })
        }
        rg.setOnCheckedChangeListener { _, checked ->
            val i = ids.indexOf(checked)
            if (i >= 0) onPick(opts[i].first)
        }
        return rg
    }

    // ---------- permisos ----------

    private class Perm(val name: String, val why: String, val ok: Boolean, val fix: (() -> Unit)?)

    private fun perms(): List<Perm> {
        val list = ArrayList<Perm>()
        val pkg = Uri.parse("package:$packageName")
        list.add(Perm(
            "Micrófono", "Para escuchar tu lectura.",
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) })
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Perm(
                "Notificaciones", "Para mostrar la alarma.",
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) })
        }
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Perm("Alarmas exactas", "Para sonar a la hora justa.", AlarmScheduler.canExact(this)) {
                open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            })
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            list.add(Perm("Pantalla completa", "Para aparecer con el celular bloqueado.", nm.canUseFullScreenIntent()) {
                open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            })
        }
        list.add(Perm("Mostrar sobre otras apps", "Para abrir la lectura sola al sonar.", Settings.canDrawOverlays(this)) {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
        })
        val pm = getSystemService(PowerManager::class.java)
        list.add(Perm("Batería sin restricciones", "Para que el sistema no la duerma.", pm.isIgnoringBatteryOptimizations(packageName)) {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
        })
        list.add(Perm(
            "Reconocimiento de voz", "Lo da la app de Google. Si falta, instalala o actualizala.",
            SpeechRecognizer.isRecognitionAvailable(this), null
        ))
        return list
    }

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun refreshPermissions() {
        permBox.removeAllViews()
        val missing = perms().count { !it.ok }
        tabs.getOrNull(2)?.let { t ->
            val base = "Ajustes"
            val label = if (missing > 0) "$base · $missing" else base
            if (t.text.toString() != label) {
                t.text = label
                showTab(tab)
            }
        }
        perms().forEach { pr ->
            val r = row(this).apply { setPadding(0, dp(10), 0, dp(10)) }
            val texts = col(this)
            texts.add(label(pr.name, 16f, if (pr.ok) p.ink else p.rubric, Fonts.text(this, 600)))
            texts.add(label(if (pr.ok) "Listo. ${pr.why}" else pr.why, 13f, p.muted, Fonts.italic(this)), 2)
            r.add(texts, weight = 1f)
            if (!pr.ok && pr.fix != null) {
                r.add(textButton("Activar", p.rubric) { pr.fix?.invoke() }, matchWidth = false)
            } else if (pr.ok) {
                r.add(label("✓", 18f, p.muted), matchWidth = false)
            }
            permBox.add(r)
            permBox.add(hairline(p))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }
}
