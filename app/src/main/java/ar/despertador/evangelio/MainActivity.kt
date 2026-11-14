package ar.despertador.evangelio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.text.SpannableString
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

    // Alarmas
    private lateinit var tvNextTime: TextView
    private lateinit var tvNextWhen: TextView
    private lateinit var tvNextStreak: TextView
    private lateinit var alarmsBox: LinearLayout

    // Evangelio
    private lateinit var tvGLit: TextView
    private lateinit var tvGRef: TextView
    private lateinit var tvGDate: TextView
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

    private fun hm(h: Int, m: Int) = String.format(Locale.US, "%02d:%02d", h, m)

    // =====================================================================
    // Estructura: tres páginas y la barra de pestañas
    // =====================================================================

    private fun buildUi() {
        pages = listOf(page(alarmsPage()), page(gospelPage()), page(settingsPage()))
        val content = FrameLayout(this)
        pages.forEach {
            content.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val nav = row(this).apply { setPadding(dp(12), 0, dp(12), dp(8)) }
        tabs = listOf("Alarmas", "Evangelio", "Ajustes").mapIndexed { i, name ->
            label(name, 14f, p.muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(12))
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

    private fun pageColumn() = col(this).apply { setPadding(dp(24), dp(40), dp(24), dp(36)) }

    private fun showTab(i: Int) {
        tab = i
        pages.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, t ->
            val on = k == i
            t.setTextColor(if (on) p.accent else p.muted)
            t.typeface = Fonts.ui(this, if (on) 600 else 400)
        }
    }

    // =====================================================================
    // Página 1: alarmas
    // =====================================================================

    private fun alarmsPage(): View {
        val c = pageColumn()
        c.add(label("Lazarus", 36f, p.ink, Fonts.display(this, 500, 72)))
        c.add(label("Levantate y escuchá.", 16f, p.muted, Fonts.display(this, 400, 24)), 2)

        val next = card(p)
        next.add(eyebrow("Próxima alarma", p))
        tvNextTime = label("--:--", 56f, p.ink, Fonts.display(this, 400, 144)).apply {
            includeFontPadding = false
            setLineSpacing(0f, 1f)
        }
        next.add(tvNextTime, 6)
        tvNextWhen = label("", 15f, p.muted)
        next.add(tvNextWhen, 4)
        next.add(TrazoView(this, p.accent, 28), 14)
        tvNextStreak = label("", 14f, p.ink)
        next.add(tvNextStreak, 8)
        c.add(next, 24)

        val head = row(this)
        head.add(eyebrow("Tus alarmas", p, p.muted), weight = 1f)
        head.add(pill("+ Nueva", p.accent, p.accent, outline = true) { addAlarm() }.apply {
            minHeight = dp(40)
            minimumHeight = dp(40)
            textSize = 14f
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }, matchWidth = false)
        c.add(head, 28)

        alarmsBox = col(this)
        c.add(alarmsBox, 4)

        c.add(pill("Probar ahora", p.primaryBg, p.primaryFg) { testNow() }, 20)
        c.add(label("Tocá la hora para cambiarla. Mantené apretada una alarma para borrarla.", 13f, p.muted).apply {
            gravity = Gravity.CENTER
        }, 12)
        return c
    }

    private fun refreshAlarms() {
        val ok = AlarmScheduler.scheduleAll(this)
        alarmsBox.removeAllViews()
        AlarmStore.all(this).forEach { alarmsBox.add(alarmCard(it), 10) }

        val next = AlarmScheduler.next(this)
        when {
            !ok -> {
                tvNextTime.text = "--:--"
                tvNextWhen.text = "Falta un permiso para programar las alarmas. Revisalo en Ajustes."
                tvNextWhen.setTextColor(p.warn)
            }
            next == null -> {
                tvNextTime.text = "--:--"
                tvNextWhen.text = "No hay alarmas activadas."
                tvNextWhen.setTextColor(p.muted)
            }
            else -> {
                val cal = Calendar.getInstance().apply { timeInMillis = next.second }
                tvNextTime.text = hm(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
                tvNextWhen.text = whenText(next.second)
                tvNextWhen.setTextColor(p.muted)
            }
        }
        val n = Streak.current(this)
        tvNextStreak.text = if (n == 0) "Mañana puede empezar tu racha." else Streak.phrase(n)
    }

    /** "Mañana · en 5 h 41 min". */
    private fun whenText(t: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance()
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        fun same(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val day = when {
            same(cal, today) -> "Hoy"
            same(cal, tomorrow) -> "Mañana"
            else -> listOf("El lunes", "El martes", "El miércoles", "El jueves", "El viernes", "El sábado", "El domingo")[Alarm.dayIndex(cal)]
        }
        val mins = ((t - System.currentTimeMillis()) / 60000).toInt()
        return "$day · en ${mins / 60} h ${mins % 60} min"
    }

    private fun alarmCard(a: Alarm): View {
        val c = card(p).apply {
            setOnLongClickListener { confirmDelete(a); true }
        }
        val top = row(this)
        val texts = col(this)
        texts.add(label(hm(a.hour, a.minute), 40f, if (a.enabled) p.ink else p.faint, Fonts.display(this, 400, 96)).apply {
            includeFontPadding = false
            setOnClickListener { editTime(a) }
            setOnLongClickListener { confirmDelete(a); true }
            contentDescription = "Cambiar la hora de la alarma de las ${hm(a.hour, a.minute)}"
        })
        texts.add(label(a.daysText(), 13f, p.muted), 4)
        top.add(texts, weight = 1f)
        val sw = Switch(this).apply {
            isChecked = a.enabled
            contentDescription = "Activar alarma de las ${hm(a.hour, a.minute)}"
            setOnCheckedChangeListener { _, on ->
                AlarmStore.upsert(this@MainActivity, a.copy(enabled = on))
                if (on) prefetch()
                refreshAlarms()
            }
        }
        tintSwitch(p, sw)
        top.add(sw, matchWidth = false)
        c.add(top)

        val days = row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (i in 0..6) {
            val on = a.hasDay(i)
            val dot = dayDot(Alarm.DAY_LETTERS[i], on, p, 34, onFill = p.accentSoft, onText = p.accent).apply {
                background = if (on) rounded(p.accentSoft, 17f) else rounded(android.graphics.Color.TRANSPARENT, 17f, p.rule)
                setTextColor(if (on) p.accent else p.muted)
                contentDescription = Alarm.DAY_NAMES[i] + if (on) ", activado" else ", desactivado"
                setOnClickListener {
                    AlarmStore.upsert(this@MainActivity, a.copy(days = a.days xor (1 shl i)))
                    refreshAlarms()
                }
            }
            (dot.layoutParams as LinearLayout.LayoutParams).leftMargin = if (i > 0) dp(6) else 0
            days.addView(dot)
        }
        c.add(days, 14)
        return c
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
            .setMessage("¿Borrar la alarma de las ${hm(a.hour, a.minute)}?")
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
        tvGLit = eyebrow("Evangelio del día", p)
        c.add(tvGLit)
        tvGRef = label("Cargando…", 32f, p.ink, Fonts.display(this, 400, 72))
        c.add(tvGRef, 6)
        tvGDate = label("", 15f, p.muted)
        c.add(tvGDate, 2)
        c.add(TrazoView(this, p.accent, 32), 18)

        tvGText = label("", Prefs.fontSize(this).toFloat(), p.ink, Fonts.text(this)).apply {
            setLineSpacing(0f, 1.55f)
        }
        c.add(tvGText, 18)

        c.add(textButton("Actualizar", p.accent) { loadGospel(force = true) }, 18)
        c.add(label(
            "Texto bíblico: El Libro del Pueblo de Dios, vía evangeliodeldia.org. " +
                "Se baja la noche anterior, así suena aunque no haya internet.",
            13f, p.muted
        ), 4)
        return c
    }

    private fun showGospel(g: Gospel?) {
        gospel = g
        if (g == null) {
            tvGRef.text = "Sin conexión"
            tvGText.text = "No se pudo bajar el Evangelio de hoy. Revisá la conexión y tocá Actualizar."
            return
        }
        val date = Liturgy.dateOf(g.date)
        tvGDate.text = date.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale("es", "AR")))
            .replaceFirstChar { it.uppercase() }
        tvGRef.text = g.ref.ifBlank { "Evangelio del día" }
        if (ecumenical || g.title.isBlank()) {
            tvGLit.text = "EVANGELIO DEL DÍA"
            tvGLit.setTextColor(p.accent)
        } else {
            val color = p.liturgical(Liturgy.color(date, g.title))
            val sb = SpannableString("●  " + g.title.uppercase())
            sb.setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tvGLit.text = sb
        }
        tvGText.text = g.text
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

    private fun section(c: LinearLayout, title: String) {
        c.add(eyebrow(title, p), 32)
        c.add(hairline(p), 8)
    }

    private fun settingTitle(s: String) = label(s, 16f, p.ink, Fonts.ui(this, 600))
    private fun note(s: String) = label(s, 13f, p.muted)

    private fun settingsPage(): View {
        val c = pageColumn()
        c.add(label("Ajustes", 36f, p.ink, Fonts.display(this, 500, 72)))

        // Apariencia
        section(c, "Apariencia")
        c.add(settingTitle("Tema"), 16)
        c.add(choices(ThemeMode.values().map { it to it.label }, Prefs.theme(this)) {
            Prefs.setTheme(this, it)
            recreate()
        }, 4)
        c.add(note("Sistema sigue el modo nocturno del celular. AMOLED usa negro puro y gasta menos batería."), 2)

        val tvFont = settingTitle("")
        val sample = label("«Habla, Señor, que tu siervo escucha.»", Prefs.fontSize(this).toFloat(), p.ink, Fonts.text(this)).apply {
            setLineSpacing(0f, 1.5f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(p.surface, 16f)
        }
        fun showFont(v: Int) {
            tvFont.text = "Letra del Evangelio: $v"
            sample.textSize = v.toFloat()
        }
        showFont(Prefs.fontSize(this))
        c.add(tvFont, 24)
        c.add(SeekBar(this).apply {
            max = 8
            progress = (Prefs.fontSize(this@MainActivity) - 16) / 2
            tintSeek(p, this)
            setOnSeekBarChangeListener(seek { v ->
                val size = 16 + v * 2
                showFont(size)
                Prefs.setFontSize(this@MainActivity, size)
                tvGText.textSize = size.toFloat()
            })
        }, 6)
        c.add(note("Así se ve al despertarte:"), 8)
        c.add(sample, 6)

        // Tradición
        section(c, "Tradición")
        val ecuRow = row(this)
        ecuRow.add(settingTitle("Modo ecuménico"), weight = 1f)
        val sw = Switch(this).apply {
            isChecked = Prefs.ecumenical(this@MainActivity)
            contentDescription = "Modo ecuménico"
            setOnCheckedChangeListener { _, on ->
                Prefs.setEcumenical(this@MainActivity, on)
                showGospel(gospel)
            }
        }
        tintSwitch(p, sw)
        ecuRow.add(sw, matchWidth = false)
        c.add(ecuRow, 16)
        c.add(note(
            "Oculta el calendario litúrgico, el color de cada tiempo y las fórmulas propias de la misa. " +
                "Quedan solo el texto bíblico y sus citas."
        ), 2)

        // Lectura
        section(c, "Lectura")
        val tvTol = settingTitle("")
        fun showTol(v: Int) { tvTol.text = "Cuánto hay que leer bien: $v %" }
        showTol(Prefs.threshold(this))
        c.add(tvTol, 16)
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
        c.add(settingTitle("Si te quedás callado, vuelve a sonar a los"), 20)
        c.add(choices(listOf(15 to "15 s", 30 to "30 s", 60 to "60 s"), Prefs.silenceSec(this)) {
            Prefs.setSilence(this, it)
        }, 4)

        // Sonido
        section(c, "Sonido")
        c.add(settingTitle("Volumen progresivo"), 16)
        c.add(choices(listOf(0 to "No", 30 to "30 s", 60 to "1 min", 120 to "2 min"), Prefs.rampSec(this)) {
            Prefs.setRamp(this, it)
        }, 4)
        c.add(note("Arranca bajito y sube hasta el máximo. Si vuelve a sonar por silencio, suena fuerte de entrada."), 2)

        // Permisos
        section(c, "Permisos")
        permBox = col(this)
        c.add(permBox, 2)

        // Emergencia
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
                typeface = Fonts.ui(this@MainActivity)
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
        val list = perms()
        val missing = list.count { !it.ok }
        tabs[2].text = if (missing > 0) "Ajustes · $missing" else "Ajustes"
        list.forEach { pr ->
            val r = row(this).apply { setPadding(0, dp(12), 0, dp(12)) }
            val texts = col(this)
            texts.add(label(pr.name, 16f, p.ink, Fonts.ui(this, 600)))
            texts.add(label(if (pr.ok) "Listo. ${pr.why}" else pr.why, 13f, if (pr.ok) p.muted else p.warn), 2)
            r.add(texts, weight = 1f)
            if (!pr.ok && pr.fix != null) {
                r.add(textButton("Activar", p.accent) { pr.fix?.invoke() }, matchWidth = false)
            } else if (pr.ok) {
                r.add(label("✓", 18f, p.accent), matchWidth = false)
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
