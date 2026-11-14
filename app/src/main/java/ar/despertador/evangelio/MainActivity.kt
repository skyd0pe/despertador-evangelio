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
import android.graphics.Rect
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
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
    private var showPermDetail = false

    // Bienvenida, versión nueva y avisos
    private lateinit var welcomeBox: LinearLayout
    private lateinit var updateBox: LinearLayout
    private lateinit var toast: LinearLayout
    private lateinit var tvToastTitle: TextView
    private lateinit var tvToastBody: TextView
    private lateinit var tvVersion: TextView
    private var update: Updater.Info? = null
    private var updating = false

    // Gestos para pasar de pestaña
    private lateinit var swipe: GestureDetector
    private var swipeBlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        p = Palette(this)
        setTheme(p.activityTheme)
        super.onCreate(savedInstanceState)
        p.applyTo(this)
        tab = savedInstanceState?.getInt("tab") ?: 0
        buildUi()
        setupSwipe()
        showTab(tab, animate = false)
        loadGospel(force = false)
        GospelSyncJob.schedule(this)
        Thread { GospelRepo.syncWeek(this) }.start()
        checkUpdate(manual = false)
    }

    override fun onPause() {
        super.onPause()
        Sounds.stopPreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        refreshAlarms()
        refreshPermissions()
        refreshWelcome()
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

        toast = col(this).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(p.surface, 16f, p.rule)
            elevation = dpf(6f)
            visibility = View.GONE
        }
        tvToastTitle = label("", 16f, p.ink, Fonts.ui(this, 600))
        tvToastBody = label("", 14f, p.muted)
        toast.add(tvToastTitle)
        toast.add(tvToastBody, 2)
        content.addView(toast, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(16), 0, dp(16), dp(16))
        })

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

    private fun showTab(i: Int, animate: Boolean = true) {
        val from = tab
        tab = i
        pages.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, t ->
            val on = k == i
            t.setTextColor(if (on) p.accent else p.muted)
            t.typeface = Fonts.ui(this, if (on) 600 else 400)
        }
        if (animate && from != i) {
            // La página nueva entra desde el lado hacia el que se va.
            val v = pages[i]
            val dir = if (i > from) 1 else -1
            v.translationX = dir * dpf(48f)
            v.alpha = 0f
            v.animate().translationX(0f).alpha(1f).setDuration(180).start()
        }
    }

    /** Deslizar a la izquierda o a la derecha cambia de pestaña. */
    private fun setupSwipe() {
        swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (swipeBlocked || e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (kotlin.math.abs(dx) < dp(80) || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.5f) return false
                if (kotlin.math.abs(vx) < dp(250)) return false
                val next = if (dx < 0) tab + 1 else tab - 1
                if (next !in pages.indices) return false
                showTab(next)
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::swipe.isInitialized) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                // Las barras deslizables y los interruptores se arrastran de costado: ahí no cambia de pestaña.
                swipeBlocked = touchesSlider(window.decorView, ev.rawX.toInt(), ev.rawY.toInt())
            }
            swipe.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun touchesSlider(v: View, x: Int, y: Int): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (v is SeekBar || v is Switch) {
            val r = Rect()
            if (v.getGlobalVisibleRect(r)) {
                r.inset(-dp(8), -dp(8))
                if (r.contains(x, y)) return true
            }
            return false
        }
        if (v is ViewGroup) {
            for (k in 0 until v.childCount) if (touchesSlider(v.getChildAt(k), x, y)) return true
        }
        return false
    }

    // =====================================================================
    // Página 1: alarmas
    // =====================================================================

    private fun alarmsPage(): View {
        val c = pageColumn()
        c.add(label("Lazarus", 36f, p.ink, Fonts.display(this, 500, 72)))
        c.add(label("Levantate y escuchá.", 16f, p.muted, Fonts.display(this, 400, 24)), 2)

        updateBox = col(this)
        c.add(updateBox, 0)
        welcomeBox = col(this)
        c.add(welcomeBox, 0)

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
        if (::welcomeBox.isInitialized) refreshWelcome()
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
                if (on) sleepNote(a.copy(enabled = true))
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
            val changed = a.copy(hour = h, minute = m, enabled = true)
            AlarmStore.upsert(this, changed)
            prefetch()
            refreshAlarms()
            sleepNote(changed)
        }, a.hour, a.minute, true).show()
    }

    private fun addAlarm() {
        TimePickerDialog(this, p.dialogTheme, { _, h, m ->
            val created = AlarmStore.create(this, h, m)
            prefetch()
            refreshAlarms()
            sleepNote(created)
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
        Prefs.setTested(this)
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
                "La app guarda los de toda la semana, así suena aunque no haya internet.",
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
        Thread { GospelRepo.syncWeek(this) }.start()
    }

    private fun loadGospel(force: Boolean) {
        Thread {
            val date = GospelRepo.today()
            val g = (if (force) null else GospelRepo.cached(this, date)) ?: GospelRepo.fetch(this, date)
            GospelRepo.syncWeek(this)
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
        c.add(settingTitle("Sonido de la alarma"), 16)
        c.add(choices(Sounds.all.map { it.key to it.label }, Prefs.sound(this), vertical = true) {
            Prefs.setSound(this, it)
            Sounds.preview(this, it)
        }, 4)
        c.add(note("Al elegir uno, suena unos segundos al volumen de la alarma."), 2)
        c.add(settingTitle("Volumen progresivo"), 22)
        c.add(choices(listOf(0 to "No", 30 to "30 s", 60 to "1 min", 120 to "2 min"), Prefs.rampSec(this)) {
            Prefs.setRamp(this, it)
        }, 4)
        c.add(note("Arranca bajito y sube hasta el máximo. Si vuelve a sonar por silencio, suena fuerte de entrada."), 2)

        // Permisos
        section(c, "Permisos")
        permBox = col(this)
        c.add(permBox, 2)

        // Versión
        section(c, "Versión")
        tvVersion = label("Lazarus ${Updater.currentName(this)}", 15f, p.ink)
        c.add(tvVersion, 12)
        c.add(textButton("Buscar actualizaciones", p.accent) { checkUpdate(manual = true) }, 2)

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

    private fun <T> choices(opts: List<Pair<T, String>>, current: T, vertical: Boolean = false, onPick: (T) -> Unit): RadioGroup {
        val rg = RadioGroup(this).apply { orientation = if (vertical) RadioGroup.VERTICAL else RadioGroup.HORIZONTAL }
        val ids = opts.map { View.generateViewId() }
        opts.forEachIndexed { i, (value, text) ->
            rg.addView(RadioButton(this).apply {
                id = ids[i]
                this.text = text
                textSize = 15f
                typeface = Fonts.ui(this@MainActivity)
                setTextColor(p.ink)
                isChecked = value == current
                setPadding(dp(2), if (vertical) dp(6) else 0, dp(10), if (vertical) dp(6) else 0)
                tintRadio(p, this)
            })
        }
        rg.setOnCheckedChangeListener { _, checked ->
            val i = ids.indexOf(checked)
            if (i >= 0) onPick(opts[i].first)
        }
        return rg
    }

    // =====================================================================
    // Aviso de cuánto falta, bienvenida y versiones nuevas
    // =====================================================================

    private val hideToast = Runnable {
        toast.animate().alpha(0f).setDuration(200).withEndAction { toast.visibility = View.GONE }.start()
    }

    private fun showToast(title: String, body: String) {
        tvToastTitle.text = title
        tvToastBody.text = body
        tvToastBody.visibility = if (body.isBlank()) View.GONE else View.VISIBLE
        toast.removeCallbacks(hideToast)
        toast.alpha = 0f
        toast.visibility = View.VISIBLE
        toast.animate().alpha(1f).setDuration(180).start()
        toast.postDelayed(hideToast, 4500)
    }

    /** "Suena en 7 h 12 min": así se ve enseguida si la hora quedó bien. */
    private fun sleepNote(a: Alarm) {
        val mins = ((AlarmScheduler.nextTrigger(a) - System.currentTimeMillis()) / 60000).toInt().coerceAtLeast(0)
        val dur = if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
        showToast("Suena en $dur", "Si te acostás ahora, dormís $dur.")
    }

    private fun refreshWelcome() {
        welcomeBox.removeAllViews()
        if (Prefs.welcomeDismissed(this)) return

        val permsOk = perms().all { it.ok }
        val hasAlarm = AlarmStore.all(this).any { it.enabled }
        val tested = Prefs.tested(this)

        val c = card(p)
        val head = row(this).apply { gravity = Gravity.TOP }
        head.add(label("Bienvenido a Lazarus", 22f, p.ink, Fonts.display(this, 500, 36)), weight = 1f)
        head.add(label("✕", 18f, p.muted).apply {
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(44)
            contentDescription = "Cerrar la bienvenida"
            setOnClickListener {
                Prefs.setWelcomeDismissed(this@MainActivity)
                refreshWelcome()
            }
        }, matchWidth = false)
        c.add(head)
        c.add(label("Tres pasos para que mañana te despierte la Palabra:", 15f, p.muted), 2)

        fun step(n: Int, title: String, body: String, done: Boolean, action: String?, onTap: (() -> Unit)?) {
            val r = row(this).apply { gravity = Gravity.TOP }
            r.add(dayDot(if (done) "✓" else "$n", done, p, 28), matchWidth = false)
            val t = col(this).apply { setPadding(dp(12), 0, 0, 0) }
            t.add(label(title, 16f, if (done) p.muted else p.ink, Fonts.ui(this, 600)))
            t.add(label(body, 13f, p.muted), 2)
            if (!done && action != null && onTap != null) t.add(textButton(action, p.accent, onTap), 0)
            r.add(t, weight = 1f)
            c.add(r, 16)
        }
        step(1, "Activá los permisos", "Para que suene con el celular bloqueado y pueda escucharte.",
            permsOk, "Ir a los permisos") { showTab(2) }
        step(2, "Creá tu alarma", "Elegí la hora y los días en que querés levantarte.",
            hasAlarm, "Crear una alarma") { addAlarm() }
        step(3, "Probala una vez", "Leé el Evangelio de hoy en voz alta hasta que se apague.",
            tested, "Probar ahora") { testNow() }
        if (permsOk && hasAlarm && tested) {
            c.add(label("Listo. Ya podés cerrar esta ventana con la X.", 14f, p.accent, Fonts.ui(this, 600)), 14)
        }
        welcomeBox.add(c, 20)
    }

    /** Busca una versión nueva. Si [manual], avisa también cuando no hay. */
    private fun checkUpdate(manual: Boolean) {
        if (manual) showToast("Buscando actualizaciones…", "")
        Thread {
            val info = Updater.check(this)
            runOnUiThread {
                update = info
                refreshUpdate()
                if (manual) {
                    if (info == null) showToast("Tenés la última versión", "Lazarus ${Updater.currentName(this)}.")
                    else { showTab(0); showToast("Hay una versión nueva", "Está arriba de todo, en Alarmas.") }
                }
            }
        }.start()
    }

    private fun refreshUpdate() {
        updateBox.removeAllViews()
        val info = update ?: return
        val c = card(p)
        c.add(eyebrow("Versión nueva", p))
        c.add(label("Lazarus ${info.tag.removePrefix("v")} está lista", 18f, p.ink, Fonts.display(this, 500, 36)), 6)
        val status = label("Se instala encima de la actual: no perdés alarmas ni ajustes.", 13f, p.muted)
        c.add(status, 4)
        val btn = pill("Actualizar", p.primaryBg, p.primaryFg) {}
        btn.setOnClickListener {
            if (updating) return@setOnClickListener
            if (!Updater.canInstall(this)) {
                status.text = "Android pide permiso una sola vez: activá “Permitir de esta fuente” y volvé."
                status.setTextColor(p.warn)
                Updater.askInstallPermission(this)
                return@setOnClickListener
            }
            updating = true
            btn.isEnabled = false
            btn.text = "Descargando…"
            Thread {
                try {
                    Updater.downloadAndInstall(this, info) { pct -> runOnUiThread { btn.text = "Descargando… $pct %" } }
                    runOnUiThread { btn.text = "Instalando…"; status.text = "Confirmá la instalación en la ventana de Android." }
                } catch (e: Exception) {
                    runOnUiThread {
                        updating = false
                        btn.isEnabled = true
                        btn.text = "Reintentar"
                        status.text = "No se pudo descargar. Revisá la conexión."
                        status.setTextColor(p.warn)
                    }
                }
            }.start()
        }
        c.add(btn, 14)
        updateBox.add(c, 20)
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

        if (missing == 0) {
            // Todo listo: se resume en una línea y el detalle queda plegado.
            val r = row(this).apply { setPadding(0, dp(14), 0, dp(6)) }
            val texts = col(this)
            texts.add(label("Todo activado", 16f, p.ink, Fonts.ui(this, 600)))
            texts.add(label("Lazarus tiene lo que necesita para sonar a tiempo y escucharte.", 13f, p.muted), 2)
            r.add(texts, weight = 1f)
            r.add(label("✓", 20f, p.accent), matchWidth = false)
            permBox.add(r)
            permBox.add(textButton(if (showPermDetail) "Ocultar detalle" else "Ver detalle", p.accent) {
                showPermDetail = !showPermDetail
                refreshPermissions()
            })
            if (!showPermDetail) return
        }

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
