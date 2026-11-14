package ar.despertador.evangelio

import android.Manifest
import android.app.Activity
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
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var tvTime: TextView
    private lateinit var swOn: Switch
    private lateinit var tvNext: TextView
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
        refreshAlarm()
        refreshPermissions()
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }

        col.add(label("Despertador del Evangelio", 26f, p.fg, bold = true))
        col.add(label("Se apaga cuando leés en voz alta el Evangelio del día.", 15f, p.muted), 4)

        // Alarma
        val alarm = card(p)
        alarm.add(eyebrow("Alarma", p))
        tvTime = label("07:00", 60f, p.fg, bold = true).apply {
            setOnClickListener { pickTime() }
        }
        alarm.add(tvTime, 2)
        swOn = Switch(this).apply {
            text = "Activada"
            textSize = 16f
            setTextColor(p.fg)
            setOnCheckedChangeListener { _, on ->
                if (on == Prefs.enabled(this@MainActivity)) return@setOnCheckedChangeListener
                Prefs.setEnabled(this@MainActivity, on)
                if (on) prefetch()
                refreshAlarm()
            }
        }
        alarm.add(swOn, 6)
        tvNext = label("", 14f, p.muted)
        alarm.add(tvNext, 4)
        alarm.add(label("Tocá la hora para cambiarla. Suena todos los días.", 13f, p.muted), 8)
        col.add(alarm, 18)

        col.add(pillButton("Probar ahora", p.accent, p.onAccent) { testNow() }, 12)

        // Ajustes
        val adj = card(p)
        adj.add(eyebrow("Ajustes", p))
        val tvTol = label("", 15f, p.fg)
        fun showTol(v: Int) { tvTol.text = "Cuánto hay que leer bien: $v %" }
        showTol(Prefs.threshold(this))
        adj.add(tvTol, 8)
        adj.add(SeekBar(this).apply {
            max = 7
            progress = (Prefs.threshold(this@MainActivity) - 60) / 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    val pct = 60 + v * 5
                    showTol(pct)
                    Prefs.setThreshold(this@MainActivity, pct)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }, 4)
        adj.add(label("Si te quedás callado, vuelve a sonar a los:", 15f, p.fg), 14)
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val current = Prefs.silenceSec(this)
        listOf(15, 30, 60).forEach { s ->
            rg.addView(RadioButton(this).apply {
                id = 1000 + s
                text = "$s s"
                textSize = 15f
                setTextColor(p.fg)
                isChecked = s == current
                setPadding(dp(4), 0, dp(16), 0)
            })
        }
        rg.setOnCheckedChangeListener { _, id -> Prefs.setSilence(this, id - 1000) }
        adj.add(rg, 4)
        col.add(adj, 18)

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

    // ---------- alarma ----------

    private fun pickTime() {
        TimePickerDialog(this, { _, h, m ->
            Prefs.setTime(this, h, m)
            if (!Prefs.enabled(this)) {
                Prefs.setEnabled(this, true)
                prefetch()
            }
            refreshAlarm()
        }, Prefs.hour(this), Prefs.minute(this), true).show()
    }

    private fun refreshAlarm() {
        tvTime.text = String.format(Locale.US, "%02d:%02d", Prefs.hour(this), Prefs.minute(this))
        val on = Prefs.enabled(this)
        swOn.isChecked = on
        tvTime.setTextColor(if (on) p.fg else p.muted)
        if (!on) {
            AlarmScheduler.cancel(this)
            tvNext.text = "Apagada"
            tvNext.setTextColor(p.muted)
            return
        }
        if (!AlarmScheduler.schedule(this)) {
            tvNext.text = "No se pudo programar: falta el permiso de alarmas exactas (abajo)."
            tvNext.setTextColor(p.miss)
            return
        }
        val t = Calendar.getInstance().apply { timeInMillis = AlarmScheduler.nextTrigger(this@MainActivity) }
        val today = Calendar.getInstance()
        val day = if (t.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) "hoy" else "mañana"
        val mins = ((t.timeInMillis - System.currentTimeMillis()) / 60000).toInt()
        tvNext.text = "Suena $day a las ${tvTime.text} (en ${mins / 60} h ${mins % 60} min)"
        tvNext.setTextColor(p.accent)
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
