package ar.despertador.evangelio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pantalla que aparece cuando suena: muestra el Evangelio y escucha la lectura. */
class AlarmActivity : Activity(), RecognitionListener {

    private enum class St { LOADING, RING, READ, DONE }

    private lateinit var p: Palette
    private val h = Handler(Looper.getMainLooper())
    private var ecumenical = false

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
    private var fontSp = 20

    private lateinit var ribbon: RibbonView
    private lateinit var tvRubric: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvHeading: TextView
    private lateinit var tvRefNums: TextView
    private lateinit var tvReason: TextView
    private lateinit var scroll: ScrollView
    private lateinit var tvText: TextView
    private lateinit var progress: RuleProgress
    private lateinit var tvPct: TextView
    private lateinit var tvGoal: TextView
    private lateinit var listenBox: LinearLayout
    private lateinit var bars: LevelBars
    private lateinit var tvListen: TextView
    private lateinit var tvSilence: TextView
    private lateinit var btnMain: Button
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

        ecumenical = Prefs.ecumenical(this)
        thresholdPct = Prefs.threshold(this)
        silenceSec = Prefs.silenceSec(this)
        fontSp = Prefs.fontSize(this)
        buildUi()
        render()

        Thread {
            val g = GospelRepo.loadOrFetch(this, GospelRepo.today())
            runOnUiThread { setGospel(g) }
        }.start()
    }

    // ---------- interfaz ----------

    private fun buildUi() {
        val root = col(this).apply { setPadding(dp(28), dp(36), dp(28), dp(14)) }

        tvRubric = rubric("", p).apply { setPadding(0, 0, dp(44), 0) }
        tvDate = label("", 14f, p.muted, Fonts.italic(this))
        root.add(tvRubric)
        root.add(tvDate, 2)

        val clockRow = row(this).apply { gravity = Gravity.BOTTOM }
        tvClock = label(LocalTimeText.now(), 84f, p.ink, Fonts.display(this, 600)).apply {
            includeFontPadding = false
            setLineSpacing(0f, 1f)
        }
        clockRow.add(tvClock, matchWidth = false)
        clockRow.add(label("LAZARUS", 17f, p.muted, Fonts.display(this, 500)).apply {
            letterSpacing = 0.3f
            setPadding(dp(12), 0, 0, dp(14))
        }, matchWidth = false)
        root.add(clockRow, 10)

        root.add(hairline(p), 16)

        val head = row(this).apply { gravity = Gravity.BOTTOM }
        tvHeading = label("", 15f, p.rubric, Fonts.italic(this))
        tvRefNums = label("", 15f, p.rubric, Fonts.text(this, 600)).apply { gravity = Gravity.END }
        head.add(tvHeading, weight = 1f)
        head.add(tvRefNums, matchWidth = false)
        root.add(head, 14)

        tvReason = label("", 15f, p.ink, Fonts.italic(this)).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(p.surface)
                cornerRadius = dpf(3f)
            }
            visibility = View.GONE
        }
        root.add(tvReason, 12)

        tvText = label("Preparando el Evangelio de hoy…", fontSp.toFloat(), p.muted, Fonts.text(this)).apply {
            setLineSpacing(0f, 1.5f)
            setPadding(0, dp(6), 0, dp(10))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(tvText)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        lp.topMargin = dp(8)
        root.addView(scroll, lp)

        progress = RuleProgress(this, p).apply { goal = thresholdPct / 100f }
        root.add(progress, 12)
        val labels = row(this)
        tvPct = label("0 % leído", 13f, p.muted)
        tvGoal = label("Meta $thresholdPct %", 13f, p.muted).apply { gravity = Gravity.END }
        labels.add(tvPct, weight = 1f)
        labels.add(tvGoal, matchWidth = false)
        root.add(labels, 2)

        listenBox = row(this).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(1), p.ink)
                cornerRadius = dpf(3f)
            }
            visibility = View.GONE
        }
        bars = LevelBars(this, p.rubric)
        tvListen = label("Escuchando tu lectura", 16f, p.ink)
        tvSilence = label("", 13f, p.muted).apply { gravity = Gravity.END }
        listenBox.addView(bars, LinearLayout.LayoutParams(dp(22), dp(22)))
        listenBox.add(tvListen.apply { setPadding(dp(12), 0, 0, 0) }, weight = 1f)
        listenBox.add(tvSilence, matchWidth = false)
        root.add(listenBox, 16)

        btnMain = bookButton("Empezar a leer", p.rubric, p.onRubric, filled = true) { onMainButton() }
        root.add(btnMain, 16)

        tvEmergency = label("Emergencia: mantené apretado acá 8 segundos", 12f, p.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
            setOnTouchListener(EmergencyHold())
        }
        root.add(tvEmergency, 4)

        ribbon = RibbonView(this).apply { color = p.rubric }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(p.paper)
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(ribbon, FrameLayout.LayoutParams(dp(22), dp(118), Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(32)
            })
        }
        setContentView(frame)
    }

    private fun setGospel(g: Gospel) {
        gospel = g
        val (letter, body) = splitDropCap(g.text)
        matcher = Matcher(g.text)
        dropLetter = letter
        bodyText = body

        val date = Liturgy.dateOf(g.date)
        val dateText = date.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale("es", "AR")))
            .replaceFirstChar { it.uppercase() }
        val book = g.ref.substringBefore(' ').ifBlank { "" }
        tvRefNums.text = g.ref.substringAfter(' ', "")

        if (ecumenical) {
            ribbon.color = p.rubric
            tvRubric.text = dateText.uppercase()
            tvDate.visibility = View.GONE
            tvHeading.text = if (book.isNotBlank()) "Evangelio de $book" else "Evangelio"
        } else {
            ribbon.color = p.liturgical(Liturgy.color(date, g.title))
            tvRubric.text = g.title.ifBlank { "Evangelio del día" }.uppercase()
            tvDate.text = dateText
            tvHeading.text = if (book.isNotBlank()) "✠  Lectura del santo Evangelio según san $book" else "✠  Evangelio del día"
        }
        if (g.offline) reason("Sin internet: hoy toca un texto de reserva.")
        if (st == St.LOADING) st = St.RING
        render()
    }

    private var dropLetter: String? = null
    private var bodyText: String = ""

    /** Separa la primera letra para la capital, si el texto empieza con una letra. */
    private fun splitDropCap(t: String): Pair<String?, String> {
        if (t.isEmpty() || !t[0].isLetter()) return null to t
        return t.substring(0, 1) to t.substring(1)
    }

    private fun render(prov: Set<Int> = emptySet()) {
        when (st) {
            St.LOADING -> {
                btnMain.visibility = View.VISIBLE
                listenBox.visibility = View.GONE
                btnMain.isEnabled = false
                btnMain.text = "Preparando…"
            }
            St.RING -> {
                btnMain.visibility = View.VISIBLE
                listenBox.visibility = View.GONE
                btnMain.isEnabled = true
                styleButton(filled = true)
                val m = matcher
                btnMain.text = if (m != null && m.ptr > 0) "Seguir leyendo" else "Empezar a leer"
            }
            St.READ -> {
                btnMain.visibility = View.GONE
                listenBox.visibility = View.VISIBLE
            }
            St.DONE -> {
                btnMain.visibility = View.VISIBLE
                listenBox.visibility = View.GONE
                btnMain.isEnabled = true
                styleButton(filled = false)
                btnMain.text = "Cerrar"
            }
        }
        tvEmergency.visibility = if (st == St.DONE) View.GONE else View.VISIBLE

        val m = matcher ?: return
        val pct = (m.score() * 100).toInt()
        progress.fraction = m.score()
        tvPct.text = "$pct % leído"

        val letter = dropLetter
        val shift = if (letter != null) 1 else 0
        val sb = SpannableString(bodyText)
        val ex = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        if (letter != null) {
            val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Fonts.display(this@AlarmActivity, 700)
                color = p.rubric
                textSize = tvText.textSize * 3.1f
            }
            val end = bodyText.indexOf('\n').let { if (it < 0) bodyText.length else it }
            if (end > 0) sb.setSpan(DropCapSpan(letter, capPaint, dpf(6f)), 0, end, ex)
        }
        m.words.forEachIndexed { i, w ->
            val s = (w.start - shift).coerceAtLeast(0)
            val e = (w.end - shift).coerceAtMost(bodyText.length)
            if (e <= s) return@forEachIndexed
            when {
                w.state == 1 -> sb.setSpan(ForegroundColorSpan(p.muted), s, e, ex)
                w.state == 2 -> {
                    sb.setSpan(ForegroundColorSpan(p.rubric), s, e, ex)
                    sb.setSpan(StrikethroughSpan(), s, e, ex)
                }
                i in prov -> {
                    sb.setSpan(ForegroundColorSpan(p.muted), s, e, ex)
                    sb.setSpan(UnderlineSpan(), s, e, ex)
                }
                i == m.ptr && st == St.READ -> {
                    sb.setSpan(ForegroundColorSpan(p.ink), s, e, ex)
                    sb.setSpan(BackgroundColorSpan(p.rubricSoft), s, e, ex)
                    sb.setSpan(UnderlineSpan(), s, e, ex)
                }
                else -> sb.setSpan(ForegroundColorSpan(p.ink), s, e, ex)
            }
        }
        tvText.text = sb
        if (st == St.READ) followReading(m, shift)
    }

    private fun styleButton(filled: Boolean) {
        (btnMain.background as? GradientDrawable)?.apply {
            if (filled) {
                setColor(p.rubric)
                setStroke(0, p.rubric)
            } else {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(1), p.ink)
            }
        }
        btnMain.setTextColor(if (filled) p.onRubric else p.ink)
    }

    private fun followReading(m: Matcher, shift: Int) {
        val w = m.words.getOrNull(m.ptr) ?: return
        tvText.post {
            val layout = tvText.layout ?: return@post
            val off = (w.start - shift).coerceIn(0, maxOf(0, bodyText.length - 1))
            val y = layout.getLineTop(layout.getLineForOffset(off)) + tvText.paddingTop
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
        render()
        listen()
        h.removeCallbacks(tick)
        h.post(tick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startReading()
            else reason("Sin permiso de micrófono no se puede apagar leyendo. Dalo en Ajustes → Apps → Lazarus → Permisos.")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (st != St.READ) return
            val s = ((System.currentTimeMillis() - lastHeard) / 1000).toInt()
            tvSilence.text = if (s >= 3) "silencio $s s" else ""
            tvSilence.setTextColor(if (s > silenceSec * 2 / 3) p.rubric else p.muted)
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
        tvRubric.text = "ALARMA APAGADA"
        reason(
            "Buen día. Leíste el $pct % en ${sec / 60}:${String.format(Locale.US, "%02d", sec % 60)}" +
                (if (rings > 1) " y sonó $rings veces." else ".")
        )
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
                tvListen.text = "Reconectando el reconocimiento…"
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

    // ---------- no dejar escapar ----------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (st == St.DONE) finish()
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

/** Cuatro barritas que se mueven con la voz. */
class LevelBars(c: Context, private val color: Int) : View(c) {
    var level = 0f
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = floatArrayOf(0.45f, 1f, 0.7f, 0.85f)

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        val bw = width / 7f
        for (i in 0 until 4) {
            val hgt = height * (0.25f + 0.75f * level * shape[i])
            val x = i * bw * 2f
            canvas.drawRect(x, (height - hgt) / 2f, x + bw, (height + hgt) / 2f, paint)
        }
    }
}

object LocalTimeText {
    fun now(): String = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("H:mm"))
}
