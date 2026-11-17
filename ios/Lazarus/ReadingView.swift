import SwiftUI
import UIKit

@MainActor
final class ReadingModel: ObservableObject {
    enum Stage { case loading, ring, read, done }

    @Published var stage: Stage = .loading
    @Published var gospel: Gospel?
    @Published var message = "Buen día. Hoy hay una Palabra para vos."
    @Published var provisional: Set<Int> = []
    @Published var version = 0
    @Published var level: Float = 0
    @Published var silence = 0
    @Published var doneSub = ""

    let test: Bool
    let threshold: Int
    let silenceLimit: Int
    private(set) var matcher: Matcher?
    private(set) var rings = 1
    private var silences = 0
    private var lastHeard = Date()
    private var lastPartial = ""
    private var lastPartialAt = Date()
    private var flushed = false
    private let listener = Listener()
    private let ringer = Ringer()
    private var timer: Timer?

    init(test: Bool) {
        self.test = test
        let d = UserDefaults.standard
        threshold = d.object(forKey: "threshold") as? Int ?? 80
        silenceLimit = d.object(forKey: "silence") as? Int ?? 30
        listener.onPartial = { [weak self] t in MainActor.assumeIsolated { self?.partial(t) } }
        listener.onFinal = { [weak self] t in MainActor.assumeIsolated { self?.final(t) } }
        listener.onLevel = { [weak self] l in MainActor.assumeIsolated { self?.level = l } }
    }

    var started: Bool { (matcher?.ptr ?? 0) > 0 }

    func load() async {
        guard stage == .loading else { return }
        let g = await GospelRepo.load(GospelRepo.key(Date()))
        gospel = g
        matcher = Matcher(text: g.text)
        if g.offline { message = "Buen día. Hoy no hubo internet, así que te espera otra Palabra." }
        stage = .ring
    }

    func startReading() async {
        guard stage == .ring else { return }
        if !(await Voice.request()) {
            message = "Sin permiso de micrófono y de reconocimiento de voz no se puede leer. Activalos en Ajustes del iPhone → Lazarus."
            return
        }
        ringer.stop()
        do {
            try listener.start()
        } catch {
            message = "El reconocimiento de voz no responde. Revisá la conexión a internet y probá de nuevo."
            return
        }
        stage = .read
        lastHeard = Date()
        silence = 0
        flushed = false
        UIApplication.shared.isIdleTimerDisabled = true
        if !test { await Scheduler.readingStarted() }
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.onTick() }
        }
    }

    private func onTick() {
        guard stage == .read else { return }
        // Una pausa en la lectura confirma lo leído hasta ahí.
        if !lastPartial.isEmpty && !flushed && Date().timeIntervalSince(lastPartialAt) > 2.2 {
            flushed = true
            listener.flush()
        }
        let s = Int(Date().timeIntervalSince(lastHeard))
        silence = s
        if s >= silenceLimit {
            silences += 1
            ringAgain(
                silences == 1
                    ? "Lázaro estuvo cuatro días en el sepulcro. Vos no tenés tanto margen."
                    : "Seguimos donde quedaste. Leé en voz alta.",
                restart: false
            )
        }
    }

    private func partial(_ t: String) {
        guard stage == .read, t != lastPartial, let m = matcher else { return }
        lastPartial = t
        lastPartialAt = Date()
        lastHeard = Date()
        flushed = false
        provisional = m.provisional(t)
        if m.reachesEnd(provisional) {
            flushed = true
            listener.flush()
        }
    }

    private func final(_ t: String) {
        guard stage == .read, let m = matcher else { return }
        lastPartial = ""
        lastHeard = Date()
        m.commit(t)
        provisional = []
        version += 1
        checkFinished()
    }

    private func checkFinished() {
        guard let m = matcher, m.finished else { return }
        let pct = Int(m.score * 100)
        if pct >= threshold {
            finish()
        } else {
            ringAgain("Llegaste al final con el \(pct) %. Hace falta el \(threshold) %. Una vuelta más.", restart: true)
        }
    }

    private func ringAgain(_ msg: String, restart: Bool) {
        stopListening()
        if restart { matcher?.reset() }
        stage = .ring
        rings += 1
        message = msg
        version += 1
        if !test { ringer.play(Sounds.current) }
    }

    private func stopListening() {
        timer?.invalidate()
        timer = nil
        listener.stop()
        provisional = []
        level = 0
        silence = 0
        lastPartial = ""
    }

    private func finish() {
        stopListening()
        ringer.stop()
        UIApplication.shared.isIdleTimerDisabled = false
        let at = Fmt.hm(Date())
        doneSub = "Escuchaste la Palabra a las \(at)." + (rings > 1 ? " La alarma sonó \(rings) veces." : "")
        if !test {
            Streak.markToday()
            AppModel.shared.cleared()
        }
        stage = .done
        let tomorrow = GospelRepo.key(Date().addingTimeInterval(86_400))
        Task { _ = await GospelRepo.fetch(tomorrow) }
    }

    func close() {
        stopListening()
        ringer.stop()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    func emergency() {
        close()
        if !test { AppModel.shared.cleared() }
    }
}

struct ReadingView: View {
    @StateObject private var m: ReadingModel
    @EnvironmentObject private var app: AppModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss
    @AppStorage("fontSize") private var fontSize = 20.0
    @AppStorage("ecumenical") private var ecumenical = false
    @State private var holding = false

    init(test: Bool) {
        _m = StateObject(wrappedValue: ReadingModel(test: test))
    }

    private var p: Palette { .of(scheme) }

    var body: some View {
        ZStack {
            p.paper.ignoresSafeArea()
            switch m.stage {
            case .loading:
                ProgressView().tint(Brand.oro)
            case .ring:
                ring
            case .read:
                read
            case .done:
                done
            }
        }
        .task { await m.load() }
        .onDisappear { m.close() }
    }

    private func leave() {
        app.session = nil
        dismiss()
    }

    // MARK: Suena

    private var ring: some View {
        VStack(spacing: 18) {
            HStack {
                if m.test {
                    Button("Cerrar") { m.close(); leave() }
                        .font(Brand.ui(16, 500))
                        .foregroundStyle(p.muted)
                }
                Spacer()
                if m.test { Text("Prueba").font(Brand.ui(13, 600)).foregroundStyle(p.accent) }
            }
            Spacer(minLength: 0)
            TimelineView(.periodic(from: .now, by: 1)) { ctx in
                Text(Fmt.hm(ctx.date))
                    .font(Brand.display(80, 300))
                    .foregroundStyle(p.ink)
                    .monospacedDigit()
            }
            Text(Fmt.longDate(Date()))
                .font(Brand.ui(15))
                .foregroundStyle(p.muted)
            Trazo()
            Text(m.message)
                .font(Brand.display(23))
                .foregroundStyle(p.ink)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            if let g = m.gospel { gospelCard(g) }
            Spacer(minLength: 0)
            GoldButton(title: m.started ? "Seguir leyendo" : "Empezar a leer", icon: "mic.fill") {
                Task { await m.startReading() }
            }
            Text(Streak.short(Streak.current()))
                .font(Brand.ui(13))
                .foregroundStyle(p.muted)
            if !m.test { emergencyButton }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 16)
    }

    private func gospelCard(_ g: Gospel) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Eyebrow(text: "Evangelio de hoy", p: p)
            Text(g.ref.isEmpty ? "Evangelio del día" : g.ref)
                .font(Brand.display(20, 500))
                .foregroundStyle(p.ink)
            if !ecumenical && !g.title.isEmpty && !g.offline {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Circle()
                        .fill(Color(hex: Liturgy.color(Liturgy.tone(key: g.date, title: g.title))))
                        .frame(width: 9, height: 9)
                        .overlay(Circle().stroke(p.rule))
                    Text(g.title).font(Brand.ui(14)).foregroundStyle(p.muted)
                }
            }
        }
        .card(p)
    }

    private var emergencyButton: some View {
        Text(holding ? "Seguí manteniendo…" : "Emergencia: mantené apretado 8 segundos")
            .font(Brand.ui(13, 500))
            .foregroundStyle(holding ? p.warn : p.faint)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
            .onLongPressGesture(minimumDuration: 8, maximumDistance: 40) {
                m.emergency()
                leave()
            } onPressingChanged: { holding = $0 }
    }

    // MARK: Leyendo

    private var bookHeader: String {
        let book = m.gospel?.book ?? ""
        if book.isEmpty { return "Evangelio del día" }
        return ecumenical ? "Evangelio según \(book)" : "Evangelio según san \(book)"
    }

    private var read: some View {
        VStack(alignment: .leading, spacing: 12) {
            Eyebrow(text: bookHeader, p: p)
            progressBar
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        if let mt = m.matcher {
                            ForEach(0..<mt.tokens.count, id: \.self) { i in
                                Text(paragraph(i, mt))
                                    .font(Brand.text(CGFloat(fontSize)))
                                    .lineSpacing(CGFloat(fontSize) * 0.35)
                                    .id(i)
                            }
                        }
                    }
                    .padding(.vertical, 8)
                    .padding(.bottom, 120)
                }
                .onChange(of: m.version) { _, _ in follow(proxy) }
                .onChange(of: m.provisional) { _, _ in follow(proxy) }
            }
            listenBox
        }
        .padding(.horizontal, 22)
        .padding(.top, 16)
    }

    private func follow(_ proxy: ScrollViewProxy) {
        guard let mt = m.matcher, mt.ptr < mt.words.count else { return }
        withAnimation(.easeInOut(duration: 0.35)) {
            proxy.scrollTo(mt.words[mt.ptr].para, anchor: UnitPoint(x: 0, y: 0.25))
        }
    }

    private var progressBar: some View {
        let frac = m.matcher?.score ?? 0
        let goal = Double(m.threshold) / 100
        return VStack(spacing: 6) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(p.rule).frame(height: 6)
                    Capsule().fill(Brand.oro).frame(width: geo.size.width * frac, height: 6)
                    Rectangle().fill(p.ink).frame(width: 2, height: 14).offset(x: geo.size.width * goal - 1)
                }
                .frame(height: 14)
            }
            .frame(height: 14)
            HStack {
                Text("\(Int(frac * 100)) % leído").font(Brand.ui(13, 600)).foregroundStyle(p.ink)
                Spacer()
                Text("Meta \(m.threshold) %").font(Brand.ui(13)).foregroundStyle(p.muted)
            }
        }
    }

    private func paragraph(_ i: Int, _ mt: Matcher) -> AttributedString {
        var out = AttributedString()
        let toks = mt.tokens[i]
        for (t, tok) in toks.enumerated() {
            var a = AttributedString(tok + (t < toks.count - 1 ? " " : ""))
            a.foregroundColor = p.ink
            let wi = mt.index[i][t]
            if wi >= 0 {
                let w = mt.words[wi]
                if w.state == 1 {
                    a.foregroundColor = p.faint
                } else if w.state == 2 {
                    a.foregroundColor = p.warn
                    a.strikethroughStyle = Text.LineStyle.single
                } else if m.provisional.contains(wi) {
                    a.foregroundColor = p.muted
                    a.underlineStyle = Text.LineStyle.single
                } else if wi == mt.ptr {
                    a.backgroundColor = p.accentSoft
                    a.underlineStyle = Text.LineStyle.single
                }
            }
            out += a
        }
        return out
    }

    private var listenBox: some View {
        HStack(spacing: 14) {
            LevelBars(level: m.level)
            VStack(alignment: .leading, spacing: 2) {
                Text("Escuchando tu lectura").font(Brand.ui(15, 600)).foregroundStyle(p.ink)
                Text(m.silence >= 3 ? "silencio \(m.silence) s" : "Leé en voz alta, a tu ritmo.")
                    .font(Brand.ui(13))
                    .foregroundStyle(m.silence > m.silenceLimit * 2 / 3 ? p.warn : p.muted)
            }
            Spacer()
        }
        .card(p)
        .padding(.bottom, 8)
    }

    // MARK: Listo

    private var done: some View {
        ScrollView {
            VStack(spacing: 20) {
                Sunrise().frame(height: 120).padding(.top, 24)
                Text("Ganaste la primera batalla del día.")
                    .font(Brand.display(30, 500))
                    .foregroundStyle(p.ink)
                    .multilineTextAlignment(.center)
                Text(m.doneSub).font(Brand.ui(15)).foregroundStyle(p.muted).multilineTextAlignment(.center)
                if !m.test {
                    WeekDots(marks: Streak.thisWeek(), p: p)
                    Text(Streak.phrase(Streak.current())).font(Brand.ui(15, 500)).foregroundStyle(p.ink)
                }
                VStack(alignment: .leading, spacing: 10) {
                    Eyebrow(text: "Para llevar al día", p: p)
                    Text(GospelRepo.takeaway(m.gospel?.text ?? ""))
                        .font(Brand.text(19))
                        .italic()
                        .foregroundStyle(p.ink)
                    if let r = m.gospel?.ref, !r.isEmpty {
                        Text(r).font(Brand.ui(13)).foregroundStyle(p.muted)
                    }
                }
                .card(p)
                GoldButton(title: "Empezar el día") { leave() }
                if !m.test {
                    ShareLink(item: shareText) {
                        Text("Compartir mi racha")
                            .font(Brand.ui(16, 500))
                            .foregroundStyle(p.ink)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .overlay(Capsule().stroke(p.rule, lineWidth: 1.5))
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
    }

    private var shareText: String {
        let n = Streak.current()
        return n <= 1
            ? "Hoy me levanté leyendo el Evangelio. Primer día con Lazarus."
            : "Llevo \(n) mañanas seguidas levantándome con el Evangelio. Lazarus."
    }
}

struct LevelBars: View {
    let level: Float
    private let weights: [Float] = [0.5, 0.8, 1, 0.75, 0.55]
    var body: some View {
        HStack(alignment: .center, spacing: 4) {
            ForEach(0..<weights.count, id: \.self) { i in
                Capsule()
                    .fill(Brand.oro)
                    .frame(width: 5, height: CGFloat(6 + 26 * min(1, level * weights[i] * 1.4)))
            }
        }
        .frame(width: 44, height: 34)
        .animation(.easeOut(duration: 0.12), value: level)
    }
}

/// El sol que sale sobre el horizonte.
struct Sunrise: View {
    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            let cx = w / 2
            let base = h * 0.82
            let r = h * 0.42
            ctx.fill(
                Path { p in
                    p.addArc(center: CGPoint(x: cx, y: base), radius: r, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
                    p.closeSubpath()
                },
                with: .color(Brand.oro)
            )
            for i in 0..<7 {
                let a = Double.pi * (1 + Double(i + 1) / 8)
                var ray = Path()
                ray.move(to: CGPoint(x: cx + cos(a) * (r + 10), y: base + sin(a) * (r + 10)))
                ray.addLine(to: CGPoint(x: cx + cos(a) * (r + 26), y: base + sin(a) * (r + 26)))
                ctx.stroke(ray, with: .color(Brand.oro), style: StrokeStyle(lineWidth: 3, lineCap: .round))
            }
            var line = Path()
            line.move(to: CGPoint(x: cx - r * 2, y: base))
            line.addLine(to: CGPoint(x: cx + r * 2, y: base))
            ctx.stroke(line, with: .color(Brand.horizonte), style: StrokeStyle(lineWidth: 3, lineCap: .round))
        }
        .accessibilityHidden(true)
    }
}
