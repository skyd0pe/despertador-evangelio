import SwiftUI
import AVFoundation
import Speech
import UserNotifications

struct AlarmItem: Codable, Identifiable, Equatable {
    var id = UUID()
    var hour: Int
    var minute: Int
    /// bit 0 = lunes … bit 6 = domingo; 0 = suena una sola vez.
    var days: Int
    var enabled = true
    /// Para las de una sola vez: cuándo suena.
    var onceAt: Date? = nil
    var editedAt = Date()

    static let allDays = 0x7F
    static let letters = ["L", "M", "X", "J", "V", "S", "D"]
    private static let short = ["lun", "mar", "mié", "jue", "vie", "sáb", "dom"]

    var time: String { String(format: "%02d:%02d", hour, minute) }

    var summary: String {
        switch days {
        case 0: return "Una sola vez"
        case Self.allDays: return "Todos los días"
        case 0x1F: return "De lunes a viernes"
        case 0x60: return "Fines de semana"
        default: return (0..<7).filter { days & (1 << $0) != 0 }.map { Self.short[$0] }.joined(separator: " · ")
        }
    }

    /// Lunes = 0 … domingo = 6.
    static func dayIndex(_ d: Date) -> Int { (Calendar.current.component(.weekday, from: d) + 5) % 7 }

    private func at(_ day: Date) -> Date? {
        Calendar.current.date(bySettingHour: hour, minute: minute, second: 0, of: day)
    }

    static func onceDate(hour: Int, minute: Int, from now: Date = Date()) -> Date {
        let cal = Calendar.current
        let today = cal.date(bySettingHour: hour, minute: minute, second: 0, of: now) ?? now
        return today > now.addingTimeInterval(60) ? today : (cal.date(byAdding: .day, value: 1, to: today) ?? today)
    }

    func next(after now: Date = Date()) -> Date? {
        guard enabled else { return nil }
        if days == 0 {
            if let o = onceAt, o > now { return o }
            return nil
        }
        for d in 0...7 {
            guard let day = Calendar.current.date(byAdding: .day, value: d, to: now), let t = at(day) else { continue }
            if t > now && days & (1 << Self.dayIndex(t)) != 0 { return t }
        }
        return nil
    }

    func last(before now: Date = Date()) -> Date? {
        if days == 0 {
            if let o = onceAt, o <= now { return o }
            return nil
        }
        guard enabled else { return nil }
        for d in 0...7 {
            guard let day = Calendar.current.date(byAdding: .day, value: -d, to: now), let t = at(day) else { continue }
            if t <= now && days & (1 << Self.dayIndex(t)) != 0 { return t }
        }
        return nil
    }
}

struct ReadSession: Identifiable {
    let id = UUID()
    let test: Bool
}

struct Editing: Identifiable {
    let id = UUID()
    var alarm: AlarmItem
    var isNew: Bool
    var title: String
}

struct PermState: Equatable {
    var kit = false          // iOS 26: alarmas como las del reloj
    var alarms = false
    var alarmsAsk = true
    var notif = false
    var notifAsk = true
    var mic = false
    var micAsk = true
    var speech = false
    var speechAsk = true

    var voice: Bool { mic && speech }
    var complete: Bool { alarms && voice && notif }
    var missing: Int { [alarms, voice, notif].filter { !$0 }.count }
}

@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published var alarms: [AlarmItem] = []
    @Published var session: ReadSession?
    @Published var editing: Editing?
    @Published var perm = PermState()
    @Published var flash: String?

    private let key = "alarms.v1"
    private let clearedKey = "clearedAt"

    init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let list = try? JSONDecoder().decode([AlarmItem].self, from: data) {
            alarms = list
        }
    }

    // MARK: Alarmas

    private func persist() {
        alarms.sort { ($0.hour, $0.minute) < ($1.hour, $1.minute) }
        if let data = try? JSONEncoder().encode(alarms) { UserDefaults.standard.set(data, forKey: key) }
        resync()
    }

    func resync() {
        let list = alarms
        let pending = pendingFire
        Task { await Scheduler.sync(list, pending: pending) }
    }

    func upsert(_ input: AlarmItem) {
        var a = input
        a.editedAt = Date()
        if a.days == 0 {
            a.onceAt = a.enabled ? AlarmItem.onceDate(hour: a.hour, minute: a.minute) : nil
        } else {
            a.onceAt = nil
        }
        if let i = alarms.firstIndex(where: { $0.id == a.id }) { alarms[i] = a } else { alarms.append(a) }
        persist()
        if let t = a.next() {
            let left = t.timeIntervalSinceNow
            flash = left < 16 * 3600
                ? "Suena en \(Fmt.duration(left)). Que descanses."
                : "Lista para las \(a.time)."
        }
    }

    func setEnabled(_ id: UUID, _ on: Bool) {
        guard var a = alarms.first(where: { $0.id == id }) else { return }
        a.enabled = on
        upsert(a)
    }

    func delete(_ id: UUID) {
        alarms.removeAll { $0.id == id }
        persist()
    }

    func newAlarm() {
        let base = alarms.first
        editing = Editing(
            alarm: AlarmItem(hour: base?.hour ?? 7, minute: base?.minute ?? 0, days: AlarmItem.allDays),
            isNew: true, title: "Nueva alarma"
        )
    }

    /// Desde la pregunta de la noche: una alarma de una sola vez para mañana.
    func newForTomorrow() {
        let next = nextFire.map { Calendar.current.dateComponents([.hour, .minute], from: $0) }
        editing = Editing(
            alarm: AlarmItem(hour: next?.hour ?? 7, minute: next?.minute ?? 0, days: 0),
            isNew: true, title: "¿A qué hora te despertás mañana?"
        )
    }

    var nextFire: Date? { alarms.compactMap { $0.next() }.min() }

    /// Las de una sola vez que ya sonaron quedan apagadas.
    private func expireOnce() {
        var changed = false
        for i in alarms.indices where alarms[i].days == 0 && alarms[i].enabled {
            if let o = alarms[i].onceAt, o <= Date() {
                alarms[i].enabled = false
                changed = true
            }
        }
        if changed, let data = try? JSONEncoder().encode(alarms) { UserDefaults.standard.set(data, forKey: key) }
    }

    // MARK: Lectura pendiente

    /// Una alarma sonó en las últimas 3 horas y todavía no se leyó: la lectura queda pendiente.
    var pendingFire: Date? {
        let now = Date()
        let cleared = UserDefaults.standard.double(forKey: clearedKey)
        let fires = alarms.compactMap { a -> Date? in
            guard let t = a.last(before: now), t > a.editedAt, now.timeIntervalSince(t) < 3 * 3600 else { return nil }
            return t
        }
        guard let t = fires.max(), t.timeIntervalSince1970 > cleared else { return nil }
        return t
    }

    func openReading() {
        if session == nil { session = ReadSession(test: false) }
    }

    func test() {
        session = ReadSession(test: true)
    }

    /// Leyó (o usó la salida de emergencia): se apagan las repeticiones.
    func cleared() {
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: clearedKey)
        expireOnce()
        resync()
    }

    func becameActive() {
        if pendingFire != nil { openReading() }
        expireOnce()
        resync()
        Task {
            await refreshPerms()
            await GospelRepo.syncWeek()
        }
    }

    // MARK: Permisos

    func refreshPerms() async {
        var s = PermState()
        let ns = await Notifs.center.notificationSettings().authorizationStatus
        s.notif = ns == .authorized || ns == .provisional || ns == .ephemeral
        s.notifAsk = ns == .notDetermined
        if #available(iOS 26.0, *) {
            s.kit = true
            s.alarms = KitAlarms.authorized
            s.alarmsAsk = KitAlarms.notDetermined
        } else {
            s.alarms = s.notif
            s.alarmsAsk = s.notifAsk
        }
        let rp = AVAudioApplication.shared.recordPermission
        s.mic = rp == .granted
        s.micAsk = rp == .undetermined
        let sp = SFSpeechRecognizer.authorizationStatus()
        s.speech = sp == .authorized
        s.speechAsk = sp == .notDetermined
        perm = s
    }

    func askAlarms() async {
        await refreshPerms()
        if !perm.alarmsAsk && !perm.notifAsk && !(perm.alarms && perm.notif) {
            openSettings()
            return
        }
        if #available(iOS 26.0, *) { _ = await KitAlarms.request() }
        _ = await Notifs.request()
        await refreshPerms()
        resync()
    }

    func askVoice() async {
        await refreshPerms()
        if !perm.voice && !perm.micAsk && !perm.speechAsk {
            openSettings()
            return
        }
        _ = await Voice.request()
        await refreshPerms()
    }

    func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }
}

enum Voice {
    static func request() async -> Bool {
        let mic = await AVAudioApplication.requestRecordPermission()
        let speech = await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in
            SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0 == .authorized) }
        }
        return mic && speech
    }
}

enum Sounds {
    static let all: [(id: String, name: String)] = [
        ("campanas", "Campanas"), ("amanecer", "Amanecer"), ("arpa", "Arpa"), ("clasica", "Clásica"),
    ]

    static var current: String { UserDefaults.standard.string(forKey: "sound") ?? "campanas" }
}
