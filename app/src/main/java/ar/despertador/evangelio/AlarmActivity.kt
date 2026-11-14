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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pantalla que aparece cuando suena: el Evangelio del día, que hay que leer en voz alta. */
class AlarmActivity : Activity(), RecognitionListener {

    private enum class St { LOADING, RING, READ, DONE }

    private lateinit var p: Palette
    private val h = Handler(Looper.getMainLooper())
    private var ecumenical = false

    /** Abierta desde el aviso "Ya estás despierto": se lee igual, pero sin sonido ni encierro. */
    private var quiet = false

    private var st = St.LOADING
    private var gospel: Gospel? = null
    private var matcher: Matcher? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var lastHeard = 0L
    private var lastPartial = ""
    private var rings = 1
    private var silences = 0
    private var readStart = 0L
    private var readMs = 0L
    private var thresholdPct = 80
    private var silenceSec = 30

    // Paneles
    private lateinit var ringPanel: LinearLayout
    private lateinit var readPanel: LinearLayout
    private lateinit var donePanel: LinearLayout

    // Sonando
    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvGreeting: TextView
    private lateinit var tvCardRef: TextView
    private lateinit var tvCardLit: TextView
    private lateinit var btnRead: LinearLayout
    private lateinit var tvBtnRead: TextView
    private lateinit var tvStreak: TextView

    // Leyendo
    private lateinit var tvReadHead: TextView
    private lateinit var tvReadRef: TextView
    private lateinit var progress: RuleProgress
    private lateinit var tvPct: TextView
    private lateinit var scroll: ScrollView
    private lateinit var tvText: TextView
    private lateinit var bars: LevelBars
    private lateinit var tvListen: TextView
    private lateinit var tvSilence: TextView

    // Listo
    private lateinit var tvDoneSub: TextView
    private lateinit var weekRow: LinearLayout
    private lateinit var tvDoneStreak: TextView
    private lateinit var tvTakeaway: TextView

    private lateinit var tvEmergency: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        p = Palette(this)
        setTheme(p.activityTheme)
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
        p.applyTo(this)

        quiet = intent.getBooleanExtra("quiet", false)
        if (quiet) Awake.answered(this)
        ecumenical = Prefs.ecumenical(this)
        thresholdPct = Prefs.threshold(this)
        silenceSec = Prefs.silenceSec(this)
        buildUi()
        if (quiet) {
            tvGreeting.text = "Ya estás despierto. Cuando quieras, leé el Evangelio de hoy."
            tvEmergency.setOnTouchListener(null)
            tvEmergency.text = "Leer más tarde"
            tvEmergency.setTextColor(p.accent)
            tvEmergency.setOnClickListener { finish() }
        }
        render()

        Thread {
            val g = GospelRepo.loadOrFetch(this, GospelRepo.today())
            runOnUiThread { setGospel(g) }
        }.start()
    }

    // =====================================================================
    // Interfaz
    // =====================================================================

    private fun buildUi() {
        val root = col(this).apply {
            setPadding(dp(24), dp(40), dp(24), dp(12))
            setBackgroundColor(p.paper)
        }
        root.add(eyebrow("Lazarus", p).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.24f
        })

        ringPanel = buildRingPanel()
        readPanel = buildReadPanel()
        donePanel = buildDonePanel()
        listOf(ringPanel, readPanel, donePanel).forEach {
            root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        tvEmergency = label("Emergencia: mantené apretado acá 8 segundos", 12f, p.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
            setOnTouchListener(EmergencyHold())
        }
        root.add(tvEmergency, 2)
        setContentView(root)
    }

    private fun buildRingPanel(): LinearLayout {
        val c = col(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        tvClock = label(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), 96f, p.ink, Fonts.display(this, 400, 144)).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setLineSpacing(0f, 1f)
            letterSpacing = -0.02f
        }
        tvDate = label("", 16f, p.muted).apply { gravity = Gravity.CENTER }
        c.add(tvClock, 36)
        c.add(tvDate, 4)
        c.add(TrazoView(this, p.accent), 28)
        tvGreeting = label("Buen día. Hoy hay una Palabra para vos.", 24f, p.ink, Fonts.display(this, 400, 36)).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        c.add(tvGreeting, 20)

        c.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val card = card(p)
        card.add(eyebrow("Evangelio del día", p))
        tvCardRef = label("Preparando…", 20f, p.ink, Fonts.text(this, 500))
        card.add(tvCardRef, 8)
        tvCardLit = label("", 13f, p.muted)
        card.add(tvCardLit, 4)
        card.add(label("Leelo en voz alta para apagar la alarma.", 15f, p.muted), 8)
        c.add(card, 12)

        btnRead = row(this).apply {
            gravity = Gravity.CENTER
            background = rounded(p.primaryBg, 28f)
            minimumHeight = dp(56)
            isClickable = true
            isFocusable = true
            contentDescription = "Empezar a leer"
            setOnClickListener { onMainButton() }
        }
        val mic = MicIcon(this, p.primaryFg)
        btnRead.addView(mic, LinearLayout.LayoutParams(dp(20), dp(20)))
        tvBtnRead = label("Empezar a leer", 17f, p.primaryFg, Fonts.ui(this, 600)).apply { setPadding(dp(10), 0, 0, 0) }
        btnRead.addView(tvBtnRead)
        c.addView(btnRead, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(18) })

        tvStreak = label(Streak.short(Streak.current(this)), 13f, p.muted).apply { gravity = Gravity.CENTER }
        c.add(tvStreak, 12)
        return c
    }

    private fun buildReadPanel(): LinearLayout {
        val c = col(this)
        tvReadHead = eyebrow("Evangelio del día", p)
        tvReadRef = label("", 26f, p.ink, Fonts.display(this, 400, 48))
        c.add(tvReadHead, 24)
        c.add(tvReadRef, 4)

        progress = RuleProgress(this, p).apply { goal = thresholdPct / 100f }
        c.add(progress, 14)
        val labels = row(this)
        tvPct = label("0 % leído", 13f, p.muted)
        labels.add(tvPct, weight = 1f)
        labels.add(label("Meta $thresholdPct %", 13f, p.muted), matchWidth = false)
        c.add(labels, 2)

        tvText = label("", Prefs.fontSize(this).toFloat(), p.ink, Fonts.text(this)).apply {
            setLineSpacing(0f, 1.55f)
            setPadding(0, dp(4), 0, dp(12))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(tvText)
        }
        c.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })

        val box = row(this).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(p.surface, 20f)
        }
        bars = LevelBars(this, p.accent)
        box.addView(bars, LinearLayout.LayoutParams(dp(22), dp(22)))
        tvListen = label("Escuchando tu lectura", 16f, p.ink).apply { setPadding(dp(12), 0, 0, 0) }
        box.add(tvListen, weight = 1f)
        tvSilence = label("", 13f, p.muted)
        box.add(tvSilence, matchWidth = false)
        c.add(box, 12)
        return c
    }

    private fun buildDonePanel(): LinearLayout {
        val c = col(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        c.add(SunriseView(this, p.accent, if (p.dark) Brand.HORIZONTE else Brand.BRONCE), 28)
        c.add(label("Ganaste la primera batalla del día.", 30f, p.ink, Fonts.display(this, 400, 48)).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.22f)
        }, 24)
        tvDoneSub = label("", 16f, p.muted).apply { gravity = Gravity.CENTER }
        c.add(tvDoneSub, 10)
        weekRow = row(this).apply { gravity = Gravity.CENTER }
        c.add(weekRow, 26)
        tvDoneStreak = label("", 14f, p.muted).apply { gravity = Gravity.CENTER }
        c.add(tvDoneStreak, 10)

        c.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val card = card(p)
        card.add(eyebrow("Para llevar al día", p))
        tvTakeaway = label("", 19f, p.ink, Fonts.text(this)).apply { setLineSpacing(0f, 1.4f) }
        card.add(tvTakeaway, 8)
        c.add(card, 12)
        c.add(pill("Empezar el día", p.primaryBg, p.primaryFg) { finish() }, 16)
        c.add(textButton("Compartir mi racha", p.accent) { shareStreak() }.apply { gravity = Gravity.CENTER }, 4)
        return c
    }

    private fun setGospel(g: Gospel) {
        gospel = g
        matcher = Matcher(g.text)
        val date = Liturgy.dateOf(g.date)
        tvDate.text = date.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale("es", "AR")))
            .replaceFirstChar { it.uppercase() }
        val book = g.ref.substringBefore(' ')
        tvCardRef.text = g.ref.ifBlank { "Evangelio del día" }
        tvReadRef.text = g.ref
        if (ecumenical) {
            tvCardLit.visibility = View.GONE
            tvReadHead.text = "EVANGELIO DE ${book.uppercase()}"
        } else {
            val color = p.liturgical(Liturgy.color(date, g.title))
            val title = g.title.ifBlank { "" }
            val sb = SpannableString("●  $title")
            sb.setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tvCardLit.text = sb
            tvCardLit.visibility = if (title.isBlank()) View.GONE else View.VISIBLE
            tvReadHead.text = "EVANGELIO SEGÚN SAN ${book.uppercase()}"
        }
        if (g.offline) tvGreeting.text = "Buen día. Hoy no hubo internet, así que te espera otra Palabra."
        if (st == St.LOADING) st = St.RING
        render()
    }

    private fun render(prov: Set<Int> = emptySet()) {
        ringPanel.visibility = if (st == St.RING || st == St.LOADING) View.VISIBLE else View.GONE
        readPanel.visibility = if (st == St.READ) View.VISIBLE else View.GONE
        donePanel.visibility = if (st == St.DONE) View.VISIBLE else View.GONE
        tvEmergency.visibility = if (st == St.DONE) View.GONE else View.VISIBLE
        btnRead.isEnabled = st == St.RING
        btnRead.alpha = if (st == St.RING) 1f else 0.5f
        val m = matcher
        tvBtnRead.text = if (m != null && m.ptr > 0) "Seguir leyendo" else "Empezar a leer"

        if (m == null || st != St.READ) return
        val pct = (m.score() * 100).toInt()
        progress.fraction = m.score()
        tvPct.text = "$pct % leído"

        val text = gospel?.text ?: return
        val sb = SpannableString(text)
        val ex = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        m.words.forEachIndexed { i, w ->
            when {
                w.state == 1 -> sb.setSpan(ForegroundColorSpan(p.muted), w.start, w.end, ex)
                w.state == 2 -> {
                    sb.setSpan(ForegroundColorSpan(p.warn), w.start, w.end, ex)
                    sb.setSpan(StrikethroughSpan(), w.start, w.end, ex)
                }
                i in prov -> {
                    sb.setSpan(ForegroundColorSpan(p.muted), w.start, w.end, ex)
                    sb.setSpan(UnderlineSpan(), w.start, w.end, ex)
                }
                i == m.ptr -> {
                    sb.setSpan(BackgroundColorSpan(p.accentSoft), w.start, w.end, ex)
                    sb.setSpan(UnderlineSpan(), w.start, w.end, ex)
                }
            }
        }
        tvText.text = sb
        followReading(m)
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

    /** Vuelve a la pantalla de alarma con un mensaje en lugar del saludo. */
    private fun greeting(msg: String) {
        tvGreeting.text = msg
    }

    // =====================================================================
    // Flujo
    // =====================================================================

    private fun onMainButton() {
        if (st == St.RING) startReading()
    }

    private fun startReading() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            greeting("Este celular no tiene reconocimiento de voz. Instalá o actualizá la app de Google.")
            return
        }
        AlarmService.instance?.mute()
        st = St.READ
        readStart = System.currentTimeMillis()
        lastHeard = readStart
        tvListen.text = "Escuchando tu lectura"
        render()
        listen()
        h.removeCallbacks(tick)
        h.post(tick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startReading()
            else greeting("Sin permiso de micrófono no se puede apagar leyendo. Dalo en Ajustes → Apps → Lazarus → Permisos.")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (st != St.READ) return
            val s = ((System.currentTimeMillis() - lastHeard) / 1000).toInt()
            tvSilence.text = if (s >= 3) "silencio $s s" else ""
            tvSilence.setTextColor(if (s > silenceSec * 2 / 3) p.warn else p.muted)
            if (s >= silenceSec) {
                silences++
                ringAgain(
                    if (silences == 1) "Lázaro estuvo cuatro días en el sepulcro. Vos no tenés tanto margen."
                    else "Seguimos donde quedaste. Leé en voz alta.",
                    restart = false
                )
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
        if (!quiet) {
            val svc = AlarmService.instance
            if (svc != null) svc.ring() else AlarmService.start(this)
        }
        tvClock.text = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        greeting(msg)
        render()
    }

    private fun checkFinished() {
        val m = matcher ?: return
        if (!m.finished) return
        val pct = (m.score() * 100).toInt()
        if (pct >= thresholdPct) done()
        else ringAgain("Llegaste al final con el $pct %. Hace falta el $thresholdPct %. Una vuelta más.", restart = true)
    }

    private fun done() {
        stopListening()
        readMs += System.currentTimeMillis() - readStart
        st = St.DONE
        AlarmService.stop(this)
        Streak.markToday(this)

        val at = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        tvDoneSub.text = "Escuchaste la Palabra a las $at." + if (rings > 1) " La alarma sonó $rings veces." else ""
        weekRow.removeAllViews()
        listOf("L", "M", "X", "J", "V", "S", "D").zip(Streak.thisWeek(this)).forEachIndexed { i, (l, on) ->
            val dot = dayDot(l, on, p)
            (dot.layoutParams as LinearLayout.LayoutParams).leftMargin = if (i > 0) dp(8) else 0
            weekRow.addView(dot)
        }
        val n = Streak.current(this)
        tvDoneStreak.text = Streak.phrase(n)
        tvTakeaway.text = takeaway(gospel?.text ?: "")
        render()
        Thread { GospelRepo.fetch(this, GospelRepo.tomorrow()) }.start()
    }

    /** Una frase del Evangelio de hoy: la última que dice Jesús, si es corta; si no, la última oración. */
    private fun takeaway(text: String): String {
        val quotes = Regex("«([^»]{25,200})»").findAll(text).map { it.groupValues[1].trim() }.toList()
        if (quotes.isNotEmpty()) return "«${quotes.last()}»"
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.length in 25..200 }
        return sentences.lastOrNull() ?: text.take(180)
    }

    private fun shareStreak() {
        val n = Streak.current(this)
        val msg = when (n) {
            1 -> "Hoy me levanté leyendo el Evangelio. Primer día con Lazarus."
            else -> "Llevo $n mañanas seguidas levantándome con el Evangelio. Lazarus."
        }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg),
            "Compartir mi racha"
        ))
    }

    // =====================================================================
    // Reconocimiento de voz
    // =====================================================================

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
        bars.level = 0f
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
        render(matcher?.provisional(t) ?: emptySet())
    }

    override fun onResults(results: Bundle?) {
        listening = false
        lastPartial = ""
        if (st != St.READ) return
        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!t.isNullOrBlank()) {
            lastHeard = System.currentTimeMillis()
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
                tvListen.text = "Reconectando…"
                listenSoon(800)
            }
            else -> listenSoon(150)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        tvListen.text = "Escuchando tu lectura"
    }

    override fun onRmsChanged(rmsdB: Float) {
        bars.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onBeginningOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    // =====================================================================
    // No dejar escapar
    // =====================================================================

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (st == St.DONE || quiet) finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) return st != St.DONE && !quiet
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        if (st == St.READ) {
            if (quiet) {
                stopListening()
                st = St.RING
                render()
            } else {
                ringAgain("La Palabra te espera donde la dejaste.", restart = false)
            }
        }
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
