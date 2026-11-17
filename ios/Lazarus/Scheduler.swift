import SwiftUI
import AppIntents
import ActivityKit
import AlarmKit
import UserNotifications

/// Programa las alarmas.
///
/// En iOS 26 usa AlarmKit: suenan como las del reloj del iPhone, aunque esté en silencio o en
/// "No molestar", y traen el botón «Leer» que abre Lazarus. En versiones anteriores suenan como
/// notificaciones. En los dos casos, si no leés, vuelve a sonar cada 5 minutos durante media hora.
@MainActor
enum Scheduler {
    static let repeats = 6
    static let gap: TimeInterval = 5 * 60

    static func sync(_ alarms: [AlarmItem], pending: Date?) async {
        let sound = Sounds.current
        let start: Date? = pending != nil ? Date() : alarms.compactMap { $0.next() }.min()
        let backups = start.map { s in (1...repeats).map { s.addingTimeInterval(Double($0) * gap) } } ?? []

        if #available(iOS 26.0, *), KitAlarms.authorized {
            await Notifs.clearAlarms()
            await KitAlarms.replaceAll(alarms, backups: backups, sound: sound)
        } else {
            await Notifs.scheduleAlarms(alarms, backups: backups, sound: sound)
        }
        await Notifs.scheduleNight(alarms)
    }

    /// Empezó a leer: las repeticiones se reemplazan por una sola, por si deja a la mitad.
    static func readingStarted() async {
        let safety = [Date().addingTimeInterval(8 * 60)]
        if #available(iOS 26.0, *), KitAlarms.authorized {
            KitAlarms.cancelBackups()
            await KitAlarms.addBackups(safety, sound: Sounds.current)
        } else {
            await Notifs.replaceBackups(safety, sound: Sounds.current)
        }
    }
}

/// El botón «Leer» de la alarma: abre Lazarus en la lectura.
struct OpenReadingIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Leer el Evangelio"
    static var description = IntentDescription("Abre Lazarus para leer el Evangelio del día.")
    static var openAppWhenRun: Bool = true

    init() {}

    func perform() async throws -> some IntentResult {
        await MainActor.run { AppModel.shared.openReading() }
        return .result()
    }
}

@available(iOS 26.0, *)
enum KitAlarms {
    struct Meta: AlarmMetadata {}

    private static let backupKey = "kit.backups"
    private static let weekdays: [Locale.Weekday] = [.monday, .tuesday, .wednesday, .thursday, .friday, .saturday, .sunday]

    static var authorized: Bool { AlarmManager.shared.authorizationState == .authorized }
    static var notDetermined: Bool { AlarmManager.shared.authorizationState == .notDetermined }

    static func request() async -> Bool {
        do { return try await AlarmManager.shared.requestAuthorization() == .authorized } catch { return false }
    }

    private static func cancelAll() {
        let list = (try? AlarmManager.shared.alarms) ?? []
        for a in list { try? AlarmManager.shared.cancel(id: a.id) }
        UserDefaults.standard.removeObject(forKey: backupKey)
    }

    static func cancelBackups() {
        for s in UserDefaults.standard.stringArray(forKey: backupKey) ?? [] {
            if let id = UUID(uuidString: s) { try? AlarmManager.shared.cancel(id: id) }
        }
        UserDefaults.standard.removeObject(forKey: backupKey)
    }

    static func replaceAll(_ alarms: [AlarmItem], backups: [Date], sound: String) async {
        cancelAll()
        for a in alarms where a.enabled {
            let schedule: Alarm.Schedule
            if a.days == 0 {
                guard let o = a.onceAt, o > Date() else { continue }
                schedule = .fixed(o)
            } else {
                let days = (0..<7).filter { a.days & (1 << $0) != 0 }.map { weekdays[$0] }
                schedule = .relative(Alarm.Schedule.Relative(
                    time: Alarm.Schedule.Relative.Time(hour: a.hour, minute: a.minute),
                    repeats: .weekly(days)
                ))
            }
            try? await add(id: UUID(), schedule: schedule, title: "¡Lázaro, ven afuera!", sound: sound)
        }
        await addBackups(backups, sound: sound)
    }

    static func addBackups(_ dates: [Date], sound: String) async {
        var ids = UserDefaults.standard.stringArray(forKey: backupKey) ?? []
        for d in dates where d > Date() {
            let id = UUID()
            do {
                try await add(id: id, schedule: .fixed(d), title: "Todavía falta leer el Evangelio", sound: sound)
                ids.append(id.uuidString)
            } catch {}
        }
        UserDefaults.standard.set(ids, forKey: backupKey)
    }

    private static func add(id: UUID, schedule: Alarm.Schedule, title: String, sound: String) async throws {
        let stop = AlarmButton(text: "Detener", textColor: .white, systemImageName: "stop.fill")
        let read = AlarmButton(text: "Leer", textColor: Brand.vigilia, systemImageName: "book.fill")
        let alert = AlarmPresentation.Alert(
            title: LocalizedStringResource(stringLiteral: title),
            stopButton: stop,
            secondaryButton: read,
            secondaryButtonBehavior: .custom
        )
        let attributes = AlarmAttributes<Meta>(
            presentation: AlarmPresentation(alert: alert),
            metadata: Meta(),
            tintColor: Brand.oro
        )
        let config = AlarmManager.AlarmConfiguration<Meta>(
            countdownDuration: nil,
            schedule: schedule,
            attributes: attributes,
            stopIntent: nil,
            secondaryIntent: OpenReadingIntent(),
            sound: .named("\(sound).wav")
        )
        _ = try await AlarmManager.shared.schedule(id: id, configuration: config)
    }
}

/// Notificaciones: la pregunta de las 22 h y, antes de iOS 26, las alarmas.
enum Notifs {
    static let center = UNUserNotificationCenter.current()
    private static let alarmPrefix = "lz-alarm-"
    private static let backupPrefix = "lz-backup-"
    private static let nightPrefix = "lz-night-"

    static func request() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    private static func remove(prefix: String) async {
        let ids = await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix(prefix) }
        center.removePendingNotificationRequests(withIdentifiers: ids)
    }

    static func clearAlarms() async {
        await remove(prefix: alarmPrefix)
        await remove(prefix: backupPrefix)
    }

    private static func alarmContent(_ title: String, _ sound: String) -> UNMutableNotificationContent {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = "Abrí Lazarus y leé en voz alta el Evangelio de hoy."
        c.sound = UNNotificationSound(named: UNNotificationSoundName("\(sound).wav"))
        c.userInfo = ["kind": "alarm"]
        c.interruptionLevel = .timeSensitive
        return c
    }

    static func scheduleAlarms(_ alarms: [AlarmItem], backups: [Date], sound: String) async {
        await clearAlarms()
        for a in alarms where a.enabled {
            if a.days == 0 {
                guard let o = a.onceAt, o > Date() else { continue }
                let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: o)
                let req = UNNotificationRequest(
                    identifier: "\(alarmPrefix)\(a.id.uuidString)",
                    content: alarmContent("¡Lázaro, ven afuera!", sound),
                    trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
                )
                try? await center.add(req)
            } else {
                for i in 0..<7 where a.days & (1 << i) != 0 {
                    var comps = DateComponents()
                    comps.weekday = (i + 1) % 7 + 1 // lunes (0) → 2 … domingo (6) → 1
                    comps.hour = a.hour
                    comps.minute = a.minute
                    let req = UNNotificationRequest(
                        identifier: "\(alarmPrefix)\(a.id.uuidString)-\(i)",
                        content: alarmContent("¡Lázaro, ven afuera!", sound),
                        trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: true)
                    )
                    try? await center.add(req)
                }
            }
        }
        await addBackups(backups, sound: sound)
    }

    private static func addBackups(_ dates: [Date], sound: String) async {
        for (i, d) in dates.enumerated() where d > Date() {
            let req = UNNotificationRequest(
                identifier: "\(backupPrefix)\(i)",
                content: alarmContent("Todavía falta leer el Evangelio", sound),
                trigger: UNTimeIntervalNotificationTrigger(timeInterval: max(1, d.timeIntervalSinceNow), repeats: false)
            )
            try? await center.add(req)
        }
    }

    static func replaceBackups(_ dates: [Date], sound: String) async {
        await remove(prefix: backupPrefix)
        await addBackups(dates, sound: sound)
    }

    // MARK: La pregunta de la noche

    private static let ask: [(String, String)] = [
        ("¿A qué hora te despertás mañana?", "Elegí la hora y dejá que la Palabra te despierte."),
        ("¿A qué hora querés ganar la primera batalla de mañana?", "Elegí la hora antes de dormirte."),
        ("Lázaro no eligió la hora. Vos sí.", "¿A qué hora te levantás mañana?"),
        ("¿A qué hora te despertarías mañana?", "Mañana hay una Palabra para vos."),
        ("Antes de dormir: ¿a qué hora te levantás?", "Elegí la hora de mañana."),
    ]

    /// Todas las noches a las 22 h pregunta a qué hora te despertás mañana (programa la semana).
    static func scheduleNight(_ alarms: [AlarmItem]) async {
        await remove(prefix: nightPrefix)
        guard UserDefaults.standard.object(forKey: "night") as? Bool ?? true else { return }
        let cal = Calendar.current
        for d in 0..<7 {
            guard let day = cal.date(byAdding: .day, value: d, to: Date()),
                  let at = cal.date(bySettingHour: 22, minute: 0, second: 0, of: day),
                  at > Date() else { continue }
            let (title, text) = ask[cal.ordinality(of: .day, in: .year, for: at).map { $0 % ask.count } ?? 0]
            let next = alarms.compactMap { $0.next(after: at) }.min()
            let c = UNMutableNotificationContent()
            c.title = title
            if let next, next.timeIntervalSince(at) < 20 * 3600 {
                c.body = "Ahora tenés una a las \(Fmt.hm(next)). ¿La dejás así o elegís otra?"
            } else {
                c.body = text
            }
            c.sound = .default
            c.userInfo = ["kind": "night"]
            let comps = cal.dateComponents([.year, .month, .day, .hour, .minute], from: at)
            let req = UNNotificationRequest(
                identifier: "\(nightPrefix)\(d)",
                content: c,
                trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
            )
            try? await center.add(req)
        }
    }

    static func testNight() {
        let c = UNMutableNotificationContent()
        c.title = ask[0].0
        c.body = ask[0].1
        c.sound = .default
        c.userInfo = ["kind": "night"]
        center.add(UNNotificationRequest(
            identifier: "lz-test",
            content: c,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: 3, repeats: false)
        ))
    }
}
