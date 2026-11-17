import Foundation
import UIKit

struct Gospel: Codable, Equatable {
    var date: String
    var title: String
    var ref: String
    var text: String
    var offline = false

    /// "Mateo", "Marcos"… a partir de la cita.
    var book: String { ref.split(separator: " ").first.map(String.init) ?? "" }
}

/// Trae el Evangelio del día. La fuente es Evangelizo (evangeliodeldia.org), que en español
/// publica el texto de El Libro del Pueblo de Dios, la traducción de la CEA. Se guarda en el
/// teléfono la semana entera, para que la lectura funcione aunque no haya internet.
enum GospelRepo {
    static let base = "https://feed.evangelizo.org/v2/reader.php"
    private static let defaults = UserDefaults.standard

    static func key(_ d: Date) -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: d)
        return String(format: "%04d%02d%02d", c.year ?? 2000, c.month ?? 1, c.day ?? 1)
    }

    static func week() -> [String] {
        (0...6).compactMap { Calendar.current.date(byAdding: .day, value: $0, to: Date()) }.map(key)
    }

    static func cached(_ k: String) -> Gospel? {
        guard let data = defaults.data(forKey: "g.\(k)") else { return nil }
        return try? JSONDecoder().decode(Gospel.self, from: data)
    }

    private static func save(_ g: Gospel) {
        if let data = try? JSONEncoder().encode(g) { defaults.set(data, forKey: "g.\(g.date)") }
    }

    static func load(_ k: String) async -> Gospel {
        if let g = cached(k) { return g }
        if let g = await fetch(k) { return g }
        return fallback(k)
    }

    /// Baja los días que falten de esta semana y borra los viejos.
    static func syncWeek() async {
        let keep = week()
        for k in keep where cached(k) == nil { _ = await fetch(k) }
        for k in defaults.dictionaryRepresentation().keys where k.hasPrefix("g.") {
            if !keep.contains(String(k.dropFirst(2))) { defaults.removeObject(forKey: k) }
        }
    }

    static func fetch(_ k: String) async -> Gospel? {
        guard let raw = try? await get("\(base)?date=\(k)&type=reading&content=GSP&lang=SP") else { return nil }
        let text = await clean(raw)
        guard text.count >= 40 else { return nil }
        var ref = ""
        if let r = try? await get("\(base)?date=\(k)&type=reading_st&content=GSP&lang=SP") {
            let plain = await html(r)
            ref = expandRef(plain.trimmed)
        }
        var title = ""
        if let t = try? await get("\(base)?date=\(k)&type=liturgic_t&lang=SP") {
            let plain = await html(t)
            title = plain.trimmed
        }
        let g = Gospel(date: k, title: title, ref: ref, text: text)
        save(g)
        return g
    }

    private static func get(_ url: String) async throws -> String {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        var req = URLRequest(url: u, timeoutInterval: 10)
        req.setValue("Lazarus/1.0 (iPhone)", forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return String(decoding: data, as: UTF8.self)
    }

    /// HTML a texto plano (con los saltos de línea de los versículos).
    @MainActor static func html(_ s: String) -> String {
        let wrapped = "<meta charset=\"utf-8\">" + s
        if let data = wrapped.data(using: .utf8),
           let a = try? NSAttributedString(
               data: data,
               options: [
                   .documentType: NSAttributedString.DocumentType.html,
                   .characterEncoding: String.Encoding.utf8.rawValue,
               ],
               documentAttributes: nil
           ) {
            return a.string
                .replacingOccurrences(of: "\u{2028}", with: "\n")
                .replacingOccurrences(of: "\u{2029}", with: "\n")
                .replacingOccurrences(of: "\u{00A0}", with: " ")
        }
        return s.re("<br\\s*/?>", "\n").re("<[^>]+>", "")
    }

    @MainActor static func clean(_ raw: String) -> String {
        var t = html(raw)
        let cuts = ["Extraído de la Biblia", "Extraido de la Biblia", "evangeliodeldia.org"]
            .compactMap { t.range(of: $0)?.lowerBound }
        if let cut = cuts.min() { t = String(t[..<cut]) }
        return t.components(separatedBy: "\n")
            .map { $0.trimmed.re("\\s+", " ") }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
    }

    private static let books = ["Mt": "Mateo", "Mc": "Marcos", "Mr": "Marcos", "Lc": "Lucas", "Jn": "Juan", "Jo": "Juan"]

    static func expandRef(_ r: String) -> String {
        let parts = r.split(separator: " ", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return r }
        let abbr = parts[0].trimmingCharacters(in: CharacterSet(charactersIn: "."))
        let book = books[abbr] ?? parts[0]
        return "\(book) \(parts[1].re(",\\s*", ", "))"
    }

    /// Texto de reserva si no hay internet ni nada guardado (Reina-Valera 1909, dominio público).
    static func fallback(_ k: String) -> Gospel {
        Gospel(
            date: k,
            title: "Sin conexión: no se pudo bajar el Evangelio de hoy",
            ref: "Juan 3, 16-17",
            text: "Porque de tal manera amó Dios al mundo, que ha dado a su Hijo unigénito, para que todo aquel que en él cree, no se pierda, mas tenga vida eterna.\nPorque no envió Dios a su Hijo al mundo, para que condene al mundo, mas para que el mundo sea salvo por él.",
            offline: true
        )
    }

    /// Una frase del Evangelio para llevar al día: lo último que dice Jesús, si es corto;
    /// si no, la última oración.
    static func takeaway(_ text: String) -> String {
        let flat = text.replacingOccurrences(of: "\n", with: " ")
        if let rx = try? NSRegularExpression(pattern: "«([^»]{25,200})»") {
            let ns = flat as NSString
            let ms = rx.matches(in: flat, range: NSRange(location: 0, length: ns.length))
            if let last = ms.last {
                return "«\(ns.substring(with: last.range(at: 1)).trimmed)»"
            }
        }
        let sentences = flat.re("([.!?])\\s+", "$1\n").components(separatedBy: "\n")
            .map { $0.trimmed }
            .filter { (25...200).contains($0.count) }
        return sentences.last ?? String(flat.prefix(180))
    }
}

/// Color litúrgico del día: por el tiempo del año y, si el día lo indica, por la fiesta.
enum Liturgy {
    enum Tone { case green, violet, white, red, rose }

    private static let cal = Calendar(identifier: .gregorian)

    private static func d(_ y: Int, _ m: Int, _ day: Int) -> Date {
        cal.date(from: DateComponents(year: y, month: m, day: day, hour: 12)) ?? Date()
    }

    private static func add(_ date: Date, _ n: Int) -> Date {
        cal.date(byAdding: .day, value: n, to: date) ?? date
    }

    private static func isSunday(_ date: Date) -> Bool { cal.component(.weekday, from: date) == 1 }

    static func easter(_ y: Int) -> Date {
        let a = y % 19, b = y / 100, c = y % 100, dd = b / 4, e = b % 4
        let f = (b + 8) / 25, g = (b - f + 1) / 3
        let h = (19 * a + b - dd - g + 15) % 30
        let i = c / 4, k = c % 4
        let l = (32 + 2 * e + 2 * i - h - k) % 7
        let m = (a + 11 * h + 22 * l) / 451
        let month = (h + l - 7 * m + 114) / 31
        let day = ((h + l - 7 * m + 114) % 31) + 1
        return d(y, month, day)
    }

    static func adventStart(_ y: Int) -> Date {
        var x = d(y, 12, 24)
        while !isSunday(x) { x = add(x, -1) }
        return add(x, -21)
    }

    static func baptism(_ y: Int) -> Date {
        var x = add(d(y, 1, 6), 1)
        while !isSunday(x) { x = add(x, 1) }
        return x
    }

    private static let redWords = ["mártir", "martir", "apóstol", "apostol", "evangelista", "pentecostés", "pentecostes",
                                   "pasión", "pasion", "domingo de ramos", "viernes santo", "exaltación de la santa cruz"]
    private static let whiteWords = ["virgen", "santa maría", "inmaculada", "asunción", "navidad", "epifanía", "todos los santos",
                                     "cristo rey", "jesucristo, rey", "san josé", "natividad", "transfiguración", "anunciación",
                                     "presentación del señor", "nuestra señora", "sagrado corazón", "santísima trinidad",
                                     "cuerpo y la sangre", "ascensión", "jueves santo", "octava de pascua", "bautismo del señor"]

    static func tone(key: String, title: String) -> Tone {
        let t = title.lowercased()
        if redWords.contains(where: { t.contains($0) }) { return .red }
        if whiteWords.contains(where: { t.contains($0) }) { return .white }
        guard key.count == 8, let y = Int(key.prefix(4)), let m = Int(key.dropFirst(4).prefix(2)), let dd = Int(key.suffix(2)) else {
            return .green
        }
        let date = d(y, m, dd)
        let e = easter(y)
        let ash = add(e, -46), palm = add(e, -7), pentecost = add(e, 49), advent = adventStart(y)
        let christmas = d(y, 12, 25)
        if date >= advent && date < christmas { return date == add(advent, 14) ? .rose : .violet }
        if date >= christmas { return .white }
        if date <= baptism(y) { return .white }
        if date >= ash && date < palm { return date == add(e, -21) ? .rose : .violet }
        if date == palm || date == add(e, -2) { return .red }
        if date == add(e, -3) { return .white }
        if date > palm && date < e { return .violet }
        if date == pentecost { return .red }
        if date >= e && date < pentecost { return .white }
        return .green
    }

    static func color(_ t: Tone) -> UInt32 {
        switch t {
        case .green: return 0x4E7A4A
        case .violet: return 0x6B4C8A
        case .white: return 0xD9D2C3
        case .red: return 0xA3372E
        case .rose: return 0xC97B93
        }
    }
}

/// La racha: días seguidos en que se apagó la alarma leyendo.
enum Streak {
    private static let key = "streak.days"

    private static func stamp(_ d: Date) -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: d)
        return String(format: "%04d-%02d-%02d", c.year ?? 2000, c.month ?? 1, c.day ?? 1)
    }

    private static var days: Set<String> { Set(UserDefaults.standard.stringArray(forKey: key) ?? []) }

    static func markToday() {
        let limit = Calendar.current.date(byAdding: .day, value: -60, to: Date()) ?? Date()
        var s = days
        s.insert(stamp(Date()))
        let keep = s.filter { $0 >= stamp(limit) }
        UserDefaults.standard.set(Array(keep).sorted(), forKey: key)
    }

    static var readToday: Bool { days.contains(stamp(Date())) }

    static func current() -> Int {
        let d = days
        var day = Date()
        if !d.contains(stamp(day)) { day = Calendar.current.date(byAdding: .day, value: -1, to: day) ?? day }
        var n = 0
        while d.contains(stamp(day)) {
            n += 1
            day = Calendar.current.date(byAdding: .day, value: -1, to: day) ?? day
        }
        return n
    }

    /// Lunes a domingo de esta semana: true si ese día leyó.
    static func thisWeek() -> [Bool] {
        let d = days
        let today = Date()
        let back = AlarmItem.dayIndex(today)
        return (0..<7).map { i in
            let day = Calendar.current.date(byAdding: .day, value: i - back, to: today) ?? today
            return d.contains(stamp(day))
        }
    }

    static func phrase(_ n: Int) -> String {
        switch n {
        case ...0: return "Hoy empieza la racha."
        case 1: return "Primer día. Todo empieza así."
        case 2..<7: return "\(n) días seguidos escuchando."
        case 7: return "Siete mañanas seguidas escuchando. Eso ya es constancia."
        default: return "\(n) mañanas seguidas. Eso ya es constancia."
        }
    }

    static func short(_ n: Int) -> String {
        switch n {
        case 0: return "Racha actual: todavía ninguna"
        case 1: return "Racha actual: 1 día"
        default: return "Racha actual: \(n) días"
        }
    }
}
