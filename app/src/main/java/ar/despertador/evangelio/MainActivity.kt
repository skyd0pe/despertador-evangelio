package ar.despertador.evangelio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Calendar

class MainActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var tvNext: TextView
    private lateinit var alarmsBox: LinearLayout
    private lateinit var permBox: LinearLayout
    private lateinit var tvGTitle: TextView
    private lateinit var tvGRef: TextView
    private lateinit var tvGText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(this)
        buildUi()
        loadGospel(force = false)
    }

    override fun onResume() {
        super.onResume()
        refreshAlarms()
        refreshPermissions()
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }

        col.add(label("Despertador del Evangelio", 26f, p.fg, bold = true))
        col.add(label("Se apaga cuando leés en voz alta el Evangelio del día.", 15f, p.muted), 4)

        tvNext = label("", 15f, p.accent, bold = true)
        col.add(tvNext, 14)

        // Alarmas
        col.add(eyebrow("Alarmas", p), 18)
        alarmsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.add(alarmsBox, 4)
        col.add(pillButton("+ Agregar alarma", p.surface2, p.fg, p.line) { addAlarm() }, 10)
        col.add(pillButton("Probar ahora", p.accent, p.onAccent) { testNow() }, 10)

        col.add(settingsCard(), 18)

        // Permisos
        val perm = card(p)
        perm.add(eyebrow("Permisos que necesita", p))
        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        perm.add(permBox, 4)
        col.add(perm, 18)

        // Evangelio de hoy
        val gos = card(p)
        gos.add(eyebrow("Evangelio de hoy", p))
        tvGTitle = label("Cargando…", 13f, p.muted)
        tvGRef = label("", 18f, p.fg, bold = true)
        tvGText = label("", 16f, p.muted, serif = true).apply { maxLines = 5 }
        gos.add(tvGTitle, 6)
        gos.add(tvGRef, 4)
        gos.add(tvGText, 6)
        gos.add(pillButton("Actualizar", p.surface2, p.fg, p.line) { loadGospel(force = true) }, 12)
        col.add(gos, 18)

        col.add(
            label(
                "Texto: El Libro del Pueblo de Dios, vía evangeliodeldia.org. " +
                    "Si un día no hay internet, usa el que bajó la noche anterior.\n\n" +
                    "Salida de emergencia: en la pantalla de la alarma, mantené apretado " +
                    "“Emergencia” durante 8 segundos.",
                13f, p.muted
            ),
            18
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(col)
        })
    }

    // ---------- ajustes ----------

    private fun settingsCard(): LinearLayout {
        val adj = card(p)
        adj.add(eyebrow("Ajustes", p))

        // Cuánto hay que leer
        val tvTol = label("", 15f, p.fg)
        fun showTol(v: Int) { tvTol.text = "Cuánto hay que leer bien: $v %" }
        showTol(Prefs.threshold(this))
        adj.add(tvTol, 10)
        adj.add(SeekBar(this).apply {
            max = 7
            progress = (Prefs.threshold(this@MainActivity) - 60) / 5
            setOnSeekBarChangeListener(seek { v ->
                val pct = 60 + v * 5
                showTol(pct)
                Prefs.setThreshold(this@MainActivity, pct)
            })
        }, 4)

        // Silencio
        adj.add(label("Si te quedás callado, vuelve a sonar a los:", 15f, p.fg), 16)
        adj.add(choices(listOf(15 to "15 s", 30 to "30 s", 60 to "60 s"), Prefs.silenceSec(this)) {
            Prefs.setSilence(this, it)
        }, 4)

        // Volumen progresivo
        adj.add(label("Volumen progresivo (tarda en llegar al máximo):", 15f, p.fg), 16)
        adj.add(choices(listOf(0 to "No", 30 to "30 s", 60 to "1 min", 120 to "2 min"), Prefs.rampSec(this)) {
            Prefs.setRamp(this, it)
        }, 4)
        adj.add(label("Si vuelve a sonar porque te quedaste callado, suena fuerte de entrada.", 13f, p.muted), 2)

        // Tamaño de letra con muestra
        val tvFont = label("", 15f, p.fg)
        val sample = label(
            "Bienaventurados los pobres en espíritu: porque de ellos es el reino de los cielos.",
            Prefs.fontSize(this).toFloat(), p.fg, serif = true
        ).apply {
            setLineSpacing(0f, 1.4f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(p.surface2)
                setStroke(dp(1), p.line)
            }
        }
        fun showFont(v: Int) {
            tvFont.text = "Tamaño de la letra del Evangelio: $v"
            sample.textSize = v.toFloat()
        }
        showFont(Prefs.fontSize(this))
        adj.add(tvFont, 16)
        adj.add(SeekBar(this).apply {
            max = 8 // 16 a 32, de a 2
            progress = (Prefs.fontSize(this@MainActivity) - 16) / 2
            setOnSeekBarChangeListener(seek { v ->
                val size = 16 + v * 2
                showFont(size)
                Prefs.setFontSize(this@MainActivity, size)
            })
        }, 4)
        adj.add(label("Así se va a ver al despertarte:", 13f, p.muted), 6)
        adj.add(sample, 4)
        return adj
    }

    private fun seek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) = onChange(v)
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun choices(opts: List<Pair<Int, String>>, current: Int, onPick: (Int) -> Unit): RadioGroup {
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val ids = opts.map { android.view.View.generateViewId() }
        opts.forEachIndexed { i, (value, text) ->
            rg.addView(RadioButton(this).apply {
                id = ids[i]
                this.text = text
                textSize = 15f
                setTextColor(p.fg)
                isChecked = value == current
                setPadding(dp(2), 0, dp(12), 0)
            })
        }
        rg.setOnCheckedChangeListener { _, checked ->
            val i = ids.indexOf(checked)
            if (i >= 0) onPick(opts[i].first)
        }
        return rg
    }

    // ---------- alarmas ----------

    private fun refreshAlarms() {
        val ok = AlarmScheduler.scheduleAll(this)
        alarmsBox.removeAllViews()
        AlarmStore.all(this).forEach { alarmsBox.add(alarmCard(it), 10) }

        val next = AlarmScheduler.next(this)
        when {
            !ok -> {
                tvNext.text = "No se pueden programar: falta el permiso de alarmas exactas (abajo)."
                tvNext.setTextColor(p.miss)
            }
            next == null -> {
                tvNext.text = "No hay alarmas activadas."
                tvNext.setTextColor(p.muted)
            }
            else -> {
                tvNext.text = "Próxima: " + whenText(next.second)
                tvNext.setTextColor(p.accent)
            }
        }
    }

    private fun whenText(t: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance()
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val sameDay = { a: Calendar, b: Calendar ->
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }
        val day = when {
            sameDay(cal, today) -> "hoy"
            sameDay(cal, tomorrow) -> "mañana"
            else -> "el " + listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")[Alarm.dayIndex(cal)]
        }
        val hhmm = String.format(java.util.Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val mins = ((t - System.currentTimeMillis()) / 60000).toInt()
        return "$day a las $hhmm (en ${mins / 60} h ${mins % 60} min)"
    }

    private fun alarmCard(a: Alarm): LinearLayout {
        val c = card(p)

        val top = row(this)
        val tvTime = label(a.timeText(), 44f, if (a.enabled) p.fg else p.muted, bold = true).apply {
            setOnClickListener { editTime(a) }
        }
        top.add(tvTime, weight = 1f)
        top.add(Switch(this).apply {
            isChecked = a.enabled
            setOnCheckedChangeListener { _, on ->
                AlarmStore.upsert(this@MainActivity, a.copy(enabled = on))
                if (on) prefetch()
                refreshAlarms()
            }
        }, matchWidth = false)
        c.add(top)

        // Días: L M M J V S D
        val days = row(this)
        for (i in 0..6) {
            val on = a.hasDay(i)
            val chip = label(Alarm.DAY_LETTERS[i], 15f, if (on) p.onAccent else p.fg, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (on) p.accent else p.surface2)
                    if (!on) setStroke(dp(1), p.line)
                }
                contentDescription = Alarm.DAY_NAMES[i] + if (on) ", activado" else ", desactivado"
                setOnClickListener {
                    AlarmStore.upsert(this@MainActivity, a.copy(days = a.days xor (1 shl i)))
                    refreshAlarms()
                }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(6)
            days.addView(chip, lp)
        }
        c.add(days, 10)

        val bottom = row(this)
        bottom.add(label(a.daysText(), 14f, p.muted), weight = 1f)
        bottom.add(label("Borrar", 14f, p.miss, bold = true).apply {
            setPadding(dp(12), dp(8), 0, dp(8))
            setOnClickListener { confirmDelete(a) }
        }, matchWidth = false)
        c.add(bottom, 6)
        return c
    }

    private fun editTime(a: Alarm) {
        TimePickerDialog(this, { _, h, m ->
            AlarmStore.upsert(this, a.copy(hour = h, minute = m, enabled = true))
            prefetch()
            refreshAlarms()
        }, a.hour, a.minute, true).show()
    }

    private fun addAlarm() {
        TimePickerDialog(this, { _, h, m ->
            AlarmStore.create(this, h, m)
            prefetch()
            refreshAlarms()
        }, 7, 0, true).show()
    }

    private fun confirmDelete(a: Alarm) {
        AlertDialog.Builder(this)
            .setMessage("¿Borrar la alarma de las ${a.timeText()}?")
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

    // ---------- evangelio ----------

    private fun prefetch() {
        Thread {
            GospelRepo.fetch(this, GospelRepo.today())
            GospelRepo.fetch(this, GospelRepo.tomorrow())
        }.start()
    }

    private fun loadGospel(force: Boolean) {
        tvGTitle.text = "Cargando…"
        Thread {
            val date = GospelRepo.today()
            val g = (if (force) null else GospelRepo.cached(this, date))
                ?: GospelRepo.fetch(this, date)
            GospelRepo.fetch(this, GospelRepo.tomorrow())
            runOnUiThread {
                if (g == null) {
                    tvGTitle.text = "No se pudo bajar. Revisá la conexión y tocá Actualizar."
                    tvGRef.text = ""
                    tvGText.text = ""
                } else {
                    tvGTitle.text = g.title
                    tvGRef.text = g.ref
                    tvGText.text = g.text
                }
            }
        }.start()
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
        perms().forEach { pr ->
            val r = row(this)
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.add(label((if (pr.ok) "✓  " else "•  ") + pr.name, 16f, if (pr.ok) p.accent else p.fg, bold = true))
            texts.add(label(pr.why, 13f, p.muted), 2)
            r.add(texts, weight = 1f)
            if (!pr.ok && pr.fix != null) {
                val b = pillButton("Dar", p.accent, p.onAccent) { pr.fix?.invoke() }.apply {
                    minHeight = dp(40)
                    minimumHeight = dp(40)
                    textSize = 14f
                    gravity = Gravity.CENTER
                }
                r.add(b, matchWidth = false)
            }
            permBox.add(r, 12)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }
}
