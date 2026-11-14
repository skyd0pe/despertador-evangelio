package ar.despertador.evangelio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pantalla que aparece cuando suena: muestra el Evangelio y escucha la lectura. */
class AlarmActivity : Activity(), RecognitionListener {

    private enum class St { LOADING, RING, READ, DONE }

    private lateinit var p: Palette
    private val h = Handler(Looper.getMainLooper())

    private var st = St.LOADING
    private var gospel: Gospel? = null
    private var matcher: Matcher? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var lastHeard = 0L
    private var lastPartial = ""
    private var rings = 1
    private var readStart = 0L
    private var readMs = 0L
    private var thresholdPct = 80
    private var silenceSec = 30

    private lateinit var root: LinearLayout
    private lateinit var tvClock: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvRef: TextView
    private lateinit var tvReason: TextView
    private lateinit var progress: ProgressBar
    private lateinit var tvPct: TextView
    private lateinit var scroll: ScrollView
    private lateinit var tvText: TextView
    private lateinit var tvHeard: TextView
    private lateinit var tvSilence: TextView
    private lateinit var btnMain: Button
    private lateinit var tvEmergency: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        p = Palette(this)
        thresholdPct = Prefs.threshold(this)
        silenceSec = Prefs.silenceSec(this)
        buildUi()
        render()

        Thread {
            val g = GospelRepo.loadOrFetch(this, GospelRepo.today())
            runOnUiThread { setGospel(g) }
        }.start()
    }

    // ---------- interfaz ----------

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
            setBackgroundColor(p.alarmSoft)
        }

        tvClock = label(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), 56f, p.fg, bold = true)
        tvTitle = label("Cargando el Evangelio de hoy…", 14f, p.muted)
        tvRef = label("", 20f, p.fg, bold = true)
        tvReason = label("", 15f, p.fg).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(p.surface)
                setStroke(dp(1), p.line)
            }
            visibility = View.GONE
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(p.line)
        }
        tvPct = label("0 % leído · meta $thresholdPct %", 13f, p.muted)

        tvText = label("", Prefs.fontSize(this).toFloat(), p.muted, serif = true).apply {
            setLineSpacing(0f, 1.4f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        scroll = ScrollView(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(p.surface)
                setStroke(dp(1), p.line)
            }
            addView(tvText)
        }

        tvHeard = label("", 13f, p.muted).apply { maxLines = 2 }
        tvSilence = label("", 13f, p.muted)

        btnMain = pillButton("Empezar a leer", p.alarm, p.surface) { onMainButton() }

        tvEmergency = label("Emergencia: mantené apretado acá 8 segundos", 12f, p.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
            setOnTouchListener(EmergencyHold())
        }

        root.add(tvClock)
        root.add(tvTitle, 2)
        root.add(tvRef, 10)
        root.add(tvReason, 10)
        root.add(progress, 12)
        root.add(tvPct, 4)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        lp.topMargin = dp(10)
        root.addView(scroll, lp)
        root.add(tvHeard, 8)
        root.add(tvSilence, 2)
        root.add(btnMain, 10)
        root.add(tvEmergency, 2)

        setContentView(FrameLayout(this).apply { addView(root) })
    }

    private fun setGospel(g: Gospel) {
        gospel = g
        matcher = Matcher(g.text)
        tvTitle.text = g.title.ifBlank { "Evangelio del día" }
        tvRef.text = g.ref.ifBlank { "Evangelio" }
        tvText.text = g.text
        if (st == St.LOADING) st = St.RING
        render()
    }

    private fun render(prov: Set<Int> = emptySet()) {
        val m = matcher
        val g = gospel
        val bg = if (st == St.RING || st == St.LOADING) p.alarmSoft else p.bg
        root.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        when (st) {
            St.LOADING -> {
                btnMain.isEnabled = false
                btnMain.text = "Cargando…"
            }
            St.RING -> {
                btnMain.isEnabled = true
                btnMain.text = if (m != null && m.ptr > 0) "Seguir leyendo" else "Empezar a leer"
                tvSilence.text = "Mientras no leas, la alarma sigue sonando."
            }
            St.READ -> {
                btnMain.isEnabled = false
                btnMain.text = "Leé en voz alta…"
            }
            St.DONE -> {
                btnMain.isEnabled = true
                btnMain.text = "Cerrar"
            }
        }
        (btnMain.background as? android.graphics.drawable.GradientDrawable)?.setColor(
            when (st) {
                St.DONE -> p.accent
                St.READ -> p.surface2
                else -> p.alarm
            }
        )
        btnMain.setTextColor(if (st == St.READ) p.muted else if (st == St.DONE) p.onAccent else p.surface)
        tvEmergency.visibility = if (st == St.DONE) View.GONE else View.VISIBLE

        if (m == null || g == null) return
        val pct = (m.score() * 100).toInt()
        progress.progress = pct
        tvPct.text = "$pct % leído · meta $thresholdPct %"

        val sb = SpannableString(g.text)
        val ex = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        m.words.forEachIndexed { i, w ->
            when {
                w.state == 1 -> {
                    sb.setSpan(ForegroundColorSpan(p.fg), w.start, w.end, ex)
                    sb.setSpan(BackgroundColorSpan(p.accentSoft), w.start, w.end, ex)
                }
                w.state == 2 -> {
                    sb.setSpan(ForegroundColorSpan(p.miss), w.start, w.end, ex)
                    sb.setSpan(StrikethroughSpan(), w.start, w.end, ex)
                }
                i in prov -> {
                    sb.setSpan(ForegroundColorSpan(p.fg), w.start, w.end, ex)
                    sb.setSpan(UnderlineSpan(), w.start, w.end, ex)
                }
                i == m.ptr && st == St.READ -> {
                    sb.setSpan(ForegroundColorSpan(p.fg), w.start, w.end, ex)
                    sb.setSpan(BackgroundColorSpan(p.alarmSoft), w.start, w.end, ex)
                }
            }
        }
        tvText.text = sb
        if (st == St.READ) followReading(m)
    }

    private fun followReading(m: Matcher) {
        val w = m.words.getOrNull(m.ptr) ?: return
        tvText.post {
            val layout = tvText.layout ?: return@post
            val y = layout.getLineTop(layout.getLineForOffset(w.start)) + tvText.paddingTop
            val sh = scroll.height
            if (y < scroll.scrollY + sh / 6 || y > scroll.scrollY + sh * 2 / 3) {
                scroll.smoothScrollTo(0, (y - sh / 3).coerceAtLeast(0))
            }
        }
    }

    private fun reason(msg: String?) {
        tvReason.text = msg ?: ""
        tvReason.visibility = if (msg.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    // ---------- flujo ----------

    private fun onMainButton() {
        when (st) {
            St.RING -> startReading()
            St.DONE -> finish()
            else -> {}
        }
    }

    private fun startReading() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            reason("Este celular no tiene reconocimiento de voz. Instalá o actualizá la app de Google.")
            return
        }
        AlarmService.instance?.mute()
        st = St.READ
        reason(null)
        readStart = System.currentTimeMillis()
        lastHeard = readStart
        tvHeard.text = ""
        render()
        listen()
        h.removeCallbacks(tick)
        h.post(tick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startReading()
            else reason("Sin permiso de micrófono no se puede apagar leyendo. Dalo en Ajustes → Apps → Despertador del Evangelio → Permisos.")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (st != St.READ) return
            val s = ((System.currentTimeMillis() - lastHeard) / 1000).toInt()
            tvSilence.text = if (s >= 3) "Silencio: $s de $silenceSec segundos" else "Escuchando…"
            tvSilence.setTextColor(if (s > silenceSec * 2 / 3) p.alarm else p.muted)
            if (s >= silenceSec) {
                ringAgain("Te quedaste callado $silenceSec segundos.", restart = false)
                return
            }
            h.postDelayed(this, 500)
        }
    }

    private fun ringAgain(msg: String, restart: Boolean) {
        stopListening()
        if (st == St.READ) readMs += System.currentTimeMillis() - readStart
        if (restart) matcher?.reset()
        st = St.RING
        rings++
        val svc = AlarmService.instance
        if (svc != null) svc.ring() else AlarmService.start(this)
        reason(msg)
        tvHeard.text = ""
        tvSilence.setTextColor(p.muted)
        render()
    }

    private fun checkFinished() {
        val m = matcher ?: return
        if (!m.finished) return
        val pct = (m.score() * 100).toInt()
        if (pct >= thresholdPct) done()
        else ringAgain("Llegaste al final con el $pct %. Hace falta el $thresholdPct %. Volvé a empezar.", restart = true)
    }

    private fun done() {
        stopListening()
        readMs += System.currentTimeMillis() - readStart
        st = St.DONE
        AlarmService.stop(this)
        val pct = ((matcher?.score() ?: 0f) * 100).toInt()
        val sec = (readMs / 1000).toInt()
        tvTitle.text = "Alarma apagada"
        reason(
            "Buen día. Leíste el $pct % en ${sec / 60}:${String.format(Locale.US, "%02d", sec % 60)}" +
                (if (rings > 1) " y sonó $rings veces." else ".")
        )
        tvSilence.text = ""
        tvHeard.text = ""
        render()
        Thread { GospelRepo.fetch(this, GospelRepo.tomorrow()) }.start()
    }

    // ---------- reconocimiento de voz ----------

    private fun listen() {
        if (st != St.READ || listening) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        }
        try {
            r.startListening(i)
            listening = true
        } catch (e: Exception) {
            listenSoon(500)
        }
    }

    private fun listenSoon(ms: Long) {
        h.postDelayed({ if (st == St.READ && !listening) listen() }, ms)
    }

    private fun stopListening() {
        h.removeCallbacks(tick)
        listening = false
        lastPartial = ""
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
    }

    private fun resetRecognizer() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (st != St.READ) return
        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        if (t.isBlank() || t == lastPartial) return
        lastPartial = t
        lastHeard = System.currentTimeMillis()
        tvHeard.text = "Escuché: “${t.takeLast(110)}”"
        render(matcher?.provisional(t) ?: emptySet())
    }

    override fun onResults(results: Bundle?) {
        listening = false
        lastPartial = ""
        if (st != St.READ) return
        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!t.isNullOrBlank()) {
            lastHeard = System.currentTimeMillis()
            tvHeard.text = "Escuché: “${t.takeLast(110)}”"
            matcher?.commit(t)
            render()
            checkFinished()
        }
        if (st == St.READ) listenSoon(100)
    }

    override fun onError(error: Int) {
        listening = false
        lastPartial = ""
        if (st != St.READ) return
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                ringAgain("Falta el permiso de micrófono.", restart = false)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                resetRecognizer()
                listenSoon(400)
            }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                tvHeard.text = "Problema de conexión con el reconocimiento de voz. Sigo intentando…"
                listenSoon(800)
            }
            else -> listenSoon(150) // no entendió o hubo silencio: vuelve a escuchar
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ---------- no dejar escapar ----------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (st == St.DONE) finish()
        // Si no, no hace nada: hay que leer.
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) return st != St.DONE
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        if (st == St.READ) ringAgain("Saliste de la pantalla. Volvé a leer.", restart = false)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        resetRecognizer()
        super.onDestroy()
    }

    /** Salida de emergencia: mantener apretado 8 segundos. */
    private inner class EmergencyHold : View.OnTouchListener {
        private var startAt = 0L
        private val step = object : Runnable {
            override fun run() {
                val left = 8 - ((System.currentTimeMillis() - startAt) / 1000).toInt()
                if (left <= 0) {
                    stopListening()
                    AlarmService.stop(this@AlarmActivity)
                    st = St.DONE
                    finish()
                } else {
                    tvEmergency.text = "Soltá para cancelar · se apaga en $left s"
                    h.postDelayed(this, 200)
                }
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startAt = System.currentTimeMillis()
                    h.post(step)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    h.removeCallbacks(step)
                    tvEmergency.text = "Emergencia: mantené apretado acá 8 segundos"
                    if (e.action == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            return true
        }
    }
}
