import SwiftUI
import UserNotifications

@main
struct LazarusApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = AppModel.shared

    init() { Brand.registerFonts() }

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(model)
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let kind = response.notification.request.content.userInfo["kind"] as? String
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                if kind == "night" { AppModel.shared.newForTomorrow() }
                if kind == "alarm" { AppModel.shared.openReading() }
            }
            completionHandler()
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage("welcomeDone") private var welcomeDone = false
    @AppStorage("theme") private var theme = 0
    @Environment(\.scenePhase) private var phase

    var body: some View {
        Group {
            if welcomeDone {
                MainTabs()
            } else {
                WelcomeView { welcomeDone = true }
            }
        }
        .preferredColorScheme(theme == 1 ? .light : theme == 2 ? .dark : nil)
        .fullScreenCover(item: $model.session) { s in
            ReadingView(test: s.test).environmentObject(model)
        }
        .sheet(item: $model.editing) { e in
            AlarmEditor(editing: e).environmentObject(model)
        }
        .onChange(of: phase) { _, p in
            if p == .active { model.becameActive() }
        }
    }
}

struct MainTabs: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        TabView {
            AlarmsView()
                .tabItem { Label("Alarmas", systemImage: "alarm") }
            GospelView()
                .tabItem { Label("Evangelio", systemImage: "book") }
            SettingsView()
                .tabItem { Label("Ajustes", systemImage: "gearshape") }
                .badge(model.perm.missing)
        }
        .tint(Palette.of(scheme).accent)
    }
}

// MARK: - Bienvenida

struct WelcomeView: View {
    let onStart: () -> Void
    @Environment(\.colorScheme) private var scheme
    private var p: Palette { .of(scheme) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Eyebrow(text: "Bienvenido", p: p).padding(.top, 40)
                Text("Lazarus").font(Brand.display(52, 500)).foregroundStyle(p.ink)
                VStack(alignment: .leading, spacing: 6) {
                    Text("«¡Lázaro, ven afuera!»").font(Brand.text(22)).italic().foregroundStyle(p.ink)
                    Text("Juan 11, 43").font(Brand.ui(13)).foregroundStyle(p.muted)
                }
                Trazo()
                Text("Lazarus es un despertador que no se conforma con que lo apagues. Cuando suena, tocá «Leer» y leé en voz alta el Evangelio del día, con el texto de El Libro del Pueblo de Dios. Si la apagás sin leer, vuelve a sonar cada cinco minutos.")
                    .font(Brand.ui(16))
                    .foregroundStyle(p.ink)
                    .lineSpacing(4)
                VStack(alignment: .leading, spacing: 10) {
                    Eyebrow(text: "Un mensaje de quien hizo Lazarus", p: p)
                    Text("¡Gracias por probar Lazarus! Espero que te ayude a conquistar un mejor descanso, como me ha ayudado también a mí. Cualquier duda o aporte que creas que puede ayudar a que la app mejore, me encantaría escucharlo.")
                        .font(Brand.text(18))
                        .foregroundStyle(p.ink)
                        .lineSpacing(4)
                }
                .card(p)
                GoldButton(title: "Empezar", action: onStart).padding(.top, 8)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 32)
        }
        .background(p.paper.ignoresSafeArea())
    }
}

// MARK: - Alarmas

struct AlarmsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.colorScheme) private var scheme
    @AppStorage("setupClosed") private var setupClosed = false
    private var p: Palette { .of(scheme) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Lazarus").font(Brand.display(40, 500)).foregroundStyle(p.ink)
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("«¡Lázaro, ven afuera!»").font(Brand.text(17)).italic().foregroundStyle(p.ink)
                        Text("Juan 11, 43").font(Brand.ui(12)).foregroundStyle(p.muted)
                    }
                    Trazo().padding(.top, 4)
                }
                .padding(.top, 12)

                if let f = model.flash {
                    Text(f)
                        .font(Brand.ui(15, 500))
                        .foregroundStyle(p.onPrimary)
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(p.primaryBg)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .transition(.opacity)
                        .task {
                            try? await Task.sleep(nanoseconds: 4_000_000_000)
                            withAnimation { model.flash = nil }
                        }
                }

                if !setupClosed { SetupCard(onClose: { withAnimation { setupClosed = true } }) }

                if model.alarms.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Todavía no hay alarmas.").font(Brand.display(20, 500)).foregroundStyle(p.ink)
                        Text("Elegí a qué hora te despertás.").font(Brand.ui(15)).foregroundStyle(p.muted)
                    }
                    .card(p)
                }

                ForEach(model.alarms) { a in
                    AlarmRow(alarm: a, p: p)
                        .onTapGesture {
                            model.editing = Editing(alarm: a, isNew: false, title: "Editar alarma")
                        }
                }

                Button {
                    model.newAlarm()
                } label: {
                    Label("Nueva alarma", systemImage: "plus")
                        .font(Brand.ui(17, 600))
                        .foregroundStyle(p.onPrimary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(p.primaryBg)
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)

                if let next = model.nextFire {
                    Text("Próxima: \(Calendar.current.isDateInToday(next) ? "hoy" : Calendar.current.isDateInTomorrow(next) ? "mañana" : Fmt.longDate(next).lowercased()) a las \(Fmt.hm(next)), en \(Fmt.duration(next.timeIntervalSinceNow)).")
                        .font(Brand.ui(14))
                        .foregroundStyle(p.muted)
                }

                OutlineButton(title: "Probar la lectura", p: p) { model.test() }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .background(p.paper.ignoresSafeArea())
        .animation(.default, value: model.flash)
    }
}

struct AlarmRow: View {
    @EnvironmentObject private var model: AppModel
    let alarm: AlarmItem
    let p: Palette

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 6) {
                Text(alarm.time)
                    .font(Brand.display(44, 400))
                    .foregroundStyle(alarm.enabled ? p.ink : p.faint)
                    .monospacedDigit()
                HStack(spacing: 6) {
                    ForEach(0..<7, id: \.self) { i in
                        let on = alarm.days & (1 << i) != 0
                        Text(AlarmItem.letters[i])
                            .font(Brand.ui(12, on ? 700 : 400))
                            .foregroundStyle(on && alarm.enabled ? p.accent : p.faint)
                    }
                    Text("· \(alarm.summary)").font(Brand.ui(12)).foregroundStyle(p.muted)
                }
            }
            Spacer()
            Toggle("", isOn: Binding(
                get: { alarm.enabled },
                set: { model.setEnabled(alarm.id, $0) }
            ))
            .labelsHidden()
            .tint(Brand.oro)
        }
        .card(p)
        .contentShape(Rectangle())
    }
}

/// Primeros pasos: queda en la pantalla principal hasta que se cierra con la X.
struct SetupCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.colorScheme) private var scheme
    let onClose: () -> Void
    private var p: Palette { .of(scheme) }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Configurá Lazarus").font(Brand.display(21, 500)).foregroundStyle(p.ink)
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "xmark").font(.system(size: 14, weight: .semibold)).foregroundStyle(p.muted)
                        .frame(width: 30, height: 30)
                }
                .accessibilityLabel("Cerrar")
            }
            step(1, "Permitir las alarmas",
                 model.perm.kit ? "Para que suene como el reloj del iPhone, aunque esté en silencio." : "Para que suene a la hora elegida.",
                 model.perm.alarms && model.perm.notif) { Task { await model.askAlarms() } }
            step(2, "Permitir el micrófono", "Para escuchar tu lectura.", model.perm.voice) {
                Task { await model.askVoice() }
            }
            step(3, "Crear tu primera alarma", "Elegí la hora y los días.", !model.alarms.isEmpty) { model.newAlarm() }
            if !model.perm.kit {
                Text("Con iOS 26 la alarma suena como la del reloj, aunque el iPhone esté en silencio. En esta versión suena como una notificación: dejá el volumen alto y el iPhone sin modo silencio.")
                    .font(Brand.ui(13))
                    .foregroundStyle(p.warn)
            }
        }
        .card(p)
        .task { await model.refreshPerms() }
    }

    private func step(_ n: Int, _ title: String, _ sub: String, _ ok: Bool, action: @escaping () -> Void) -> some View {
        Button(action: { if !ok { action() } }) {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(ok ? Brand.oro : Color.clear).frame(width: 28, height: 28)
                    Circle().stroke(ok ? Color.clear : p.rule, lineWidth: 1.5).frame(width: 28, height: 28)
                    if ok {
                        Image(systemName: "checkmark").font(.system(size: 12, weight: .bold)).foregroundStyle(Brand.vigilia)
                    } else {
                        Text("\(n)").font(Brand.ui(13, 600)).foregroundStyle(p.muted)
                    }
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(Brand.ui(15, 600)).foregroundStyle(ok ? p.muted : p.ink).strikethrough(ok)
                    Text(sub).font(Brand.ui(13)).foregroundStyle(p.muted)
                }
                Spacer()
                if !ok { Image(systemName: "chevron.right").font(.system(size: 13)).foregroundStyle(p.faint) }
            }
        }
        .buttonStyle(.plain)
    }
}

struct AlarmEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    let editing: Editing
    @State private var time: Date
    @State private var days: Int

    init(editing: Editing) {
        self.editing = editing
        let a = editing.alarm
        _time = State(initialValue: Calendar.current.date(bySettingHour: a.hour, minute: a.minute, second: 0, of: Date()) ?? Date())
        _days = State(initialValue: a.days)
    }

    private var p: Palette { .of(scheme) }

    private var sleepNote: String {
        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
        var probe = editing.alarm
        probe.hour = c.hour ?? 7
        probe.minute = c.minute ?? 0
        probe.days = days
        probe.enabled = true
        probe.onceAt = days == 0 ? AlarmItem.onceDate(hour: probe.hour, minute: probe.minute) : nil
        guard let t = probe.next() else { return "" }
        return "Si te dormís ahora, dormís \(Fmt.duration(t.timeIntervalSinceNow))."
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("Hora", selection: $time, displayedComponents: .hourAndMinute)
                        .datePickerStyle(.wheel)
                        .labelsHidden()
                        .environment(\.locale, Fmt.ar)
                        .frame(maxWidth: .infinity)
                } footer: {
                    Text(sleepNote).font(Brand.ui(13))
                }
                Section {
                    HStack(spacing: 6) {
                        ForEach(0..<7, id: \.self) { i in
                            let on = days & (1 << i) != 0
                            Button {
                                days ^= (1 << i)
                            } label: {
                                Text(AlarmItem.letters[i])
                                    .font(Brand.ui(15, 600))
                                    .frame(width: 38, height: 38)
                                    .foregroundStyle(on ? Brand.vigilia : p.ink)
                                    .background(Circle().fill(on ? Brand.oro : Color.clear))
                                    .overlay(Circle().stroke(on ? Color.clear : p.rule, lineWidth: 1.5))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .frame(maxWidth: .infinity)
                } header: {
                    Text("Días")
                } footer: {
                    Text(days == 0 ? "Sin días marcados suena una sola vez." : AlarmItem(hour: 0, minute: 0, days: days).summary)
                }
                if !editing.isNew {
                    Section {
                        Button("Borrar alarma", role: .destructive) {
                            model.delete(editing.alarm.id)
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(editing.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
                        var a = editing.alarm
                        a.hour = c.hour ?? 7
                        a.minute = c.minute ?? 0
                        a.days = days
                        a.enabled = true
                        model.upsert(a)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
        .tint(p.accent)
    }
}

// MARK: - Evangelio

struct GospelView: View {
    @Environment(\.colorScheme) private var scheme
    @AppStorage("fontSize") private var fontSize = 20.0
    @AppStorage("ecumenical") private var ecumenical = false
    @State private var gospel: Gospel?
    private var p: Palette { .of(scheme) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Eyebrow(text: "Evangelio del día", p: p).padding(.top, 16)
                Text(Fmt.longDate(Date())).font(Brand.display(30, 500)).foregroundStyle(p.ink)
                if let g = gospel {
                    if !ecumenical && !g.title.isEmpty && !g.offline {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Circle()
                                .fill(Color(hex: Liturgy.color(Liturgy.tone(key: g.date, title: g.title))))
                                .frame(width: 9, height: 9)
                                .overlay(Circle().stroke(p.rule))
                            Text(g.title).font(Brand.ui(14)).foregroundStyle(p.muted)
                        }
                    }
                    Text(g.ref).font(Brand.ui(15, 600)).foregroundStyle(p.accent)
                    Trazo()
                    ForEach(Array(g.text.components(separatedBy: "\n").enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(Brand.text(CGFloat(fontSize)))
                            .lineSpacing(CGFloat(fontSize) * 0.35)
                            .foregroundStyle(p.ink)
                    }
                    Text("El Libro del Pueblo de Dios").font(Brand.ui(12)).foregroundStyle(p.faint).padding(.top, 8)
                } else {
                    ProgressView().tint(Brand.oro).frame(maxWidth: .infinity).padding(.top, 40)
                }
            }
            .padding(.horizontal, 22)
            .padding(.bottom, 24)
        }
        .background(p.paper.ignoresSafeArea())
        .refreshable {
            let k = GospelRepo.key(Date())
            if let g = await GospelRepo.fetch(k) { gospel = g }
        }
        .task { gospel = await GospelRepo.load(GospelRepo.key(Date())) }
    }
}

// MARK: - Ajustes

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.colorScheme) private var scheme
    @AppStorage("theme") private var theme = 0
    @AppStorage("fontSize") private var fontSize = 20.0
    @AppStorage("threshold") private var threshold = 80
    @AppStorage("silence") private var silence = 30
    @AppStorage("sound") private var sound = "campanas"
    @AppStorage("ecumenical") private var ecumenical = false
    @AppStorage("night") private var night = true
    @State private var preview = Ringer()
    private var p: Palette { .of(scheme) }

    var body: some View {
        NavigationStack {
            Form {
                Section("Apariencia") {
                    Picker("Tema", selection: $theme) {
                        Text("Sistema").tag(0)
                        Text("Claro").tag(1)
                        Text("Oscuro").tag(2)
                    }
                    .pickerStyle(.segmented)
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text("Tamaño de letra")
                            Spacer()
                            Text("\(Int(fontSize))").foregroundStyle(p.muted)
                        }
                        Slider(value: $fontSize, in: 15...32, step: 1).tint(Brand.oro)
                        Text("«Felices los que tienen alma de pobres, porque a ellos les pertenece el Reino de los Cielos.»")
                            .font(Brand.text(CGFloat(fontSize)))
                            .lineSpacing(CGFloat(fontSize) * 0.3)
                    }
                    .padding(.vertical, 4)
                }

                Section {
                    Stepper("Meta de lectura: \(threshold) %", value: $threshold, in: 50...100, step: 5)
                    Stepper("Silencio permitido: \(silence) s", value: $silence, in: 15...90, step: 5)
                } header: {
                    Text("Lectura")
                } footer: {
                    Text("Si dejás de leer más tiempo que el permitido, la alarma vuelve a sonar.")
                }

                Section("Sonido") {
                    ForEach(Sounds.all, id: \.id) { s in
                        Button {
                            sound = s.id
                            preview.play(s.id, loop: false)
                            model.resync()
                        } label: {
                            HStack {
                                Text(s.name).foregroundStyle(p.ink)
                                Spacer()
                                if sound == s.id { Image(systemName: "checkmark").foregroundStyle(p.accent) }
                            }
                        }
                    }
                }

                Section {
                    Toggle("Modo ecuménico", isOn: $ecumenical).tint(Brand.oro)
                } header: {
                    Text("Tradición")
                } footer: {
                    Text("Oculta las referencias católicas (el día litúrgico y su color) y conserva solo la Escritura.")
                }

                Section {
                    Toggle("Preguntar cada noche", isOn: $night)
                        .tint(Brand.oro)
                        .onChange(of: night) { _, _ in model.resync() }
                    Button("Probar ahora") { Notifs.testNight() }
                } header: {
                    Text("Recordatorio")
                } footer: {
                    Text("A las 22 h te pregunta a qué hora te despertás mañana.")
                }

                Section("Permisos") {
                    permRow(model.perm.kit ? "Alarmas" : "Alarmas (notificaciones)", model.perm.alarms) {
                        Task { await model.askAlarms() }
                    }
                    permRow("Notificaciones", model.perm.notif) { Task { await model.askAlarms() } }
                    permRow("Micrófono y voz", model.perm.voice) { Task { await model.askVoice() } }
                }

                Section {
                    Text("Si la alarma suena y no podés leer, en la pantalla de la alarma mantené apretado «Emergencia» durante 8 segundos.")
                        .font(Brand.ui(14))
                        .foregroundStyle(p.muted)
                } header: {
                    Text("Salida de emergencia")
                }

                Section {
                    HStack {
                        Text("Versión")
                        Spacer()
                        Text(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0")
                            .foregroundStyle(p.muted)
                    }
                    Text("Texto bíblico: El Libro del Pueblo de Dios (CEA), vía evangeliodeldia.org.")
                        .font(Brand.ui(13))
                        .foregroundStyle(p.muted)
                }
            }
            .scrollContentBackground(.hidden)
            .background(p.paper.ignoresSafeArea())
            .navigationTitle("Ajustes")
            .task { await model.refreshPerms() }
        }
        .tint(p.accent)
    }

    private func permRow(_ title: String, _ ok: Bool, action: @escaping () -> Void) -> some View {
        HStack {
            Text(title)
            Spacer()
            if ok {
                Label("Activado", systemImage: "checkmark").labelStyle(.titleAndIcon).foregroundStyle(p.muted).font(Brand.ui(14))
            } else {
                Button("Activar", action: action).fontWeight(.semibold)
            }
        }
    }
}
