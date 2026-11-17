import SwiftUI
import UIKit
import CoreText

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}

/// Identidad de marca de Lazarus.
enum Brand {
    static let vigilia = Color(hex: 0x12203A)
    static let medianoche = Color(hex: 0x1F3557)
    static let oro = Color(hex: 0xC9A06A)
    static let bronce = Color(hex: 0x7A5528)
    static let lino = Color(hex: 0xEDE4D3)
    static let alba = Color(hex: 0xFAF7F1)
    static let horizonte = Color(hex: 0x2C4470)

    /// Fraunces (títulos), Inter (interfaz) y Literata (texto bíblico), incluidas en la app.
    static func registerFonts() {
        for name in ["display", "ui", "text"] {
            if let url = Bundle.main.url(forResource: name, withExtension: "ttf") {
                CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
            }
        }
    }

    private static func make(_ family: String, _ size: CGFloat, _ weight: CGFloat, opsz: CGFloat?) -> Font {
        var axes: [NSNumber: NSNumber] = [NSNumber(value: 0x7767_6874): NSNumber(value: Double(weight))] // wght
        if let o = opsz { axes[NSNumber(value: 0x6F70_737A)] = NSNumber(value: Double(o)) } // opsz
        let desc = UIFontDescriptor(fontAttributes: [
            .family: family,
            UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String): axes,
        ])
        return Font(UIFont(descriptor: desc, size: size) as CTFont)
    }

    static func display(_ size: CGFloat, _ weight: CGFloat = 400) -> Font {
        make("Fraunces", size, weight, opsz: min(144, max(9, size)))
    }

    static func ui(_ size: CGFloat, _ weight: CGFloat = 400) -> Font {
        make("Inter", size, weight, opsz: nil)
    }

    static func text(_ size: CGFloat, _ weight: CGFloat = 400) -> Font {
        make("Literata", size, weight, opsz: min(72, max(7, size)))
    }
}

/// Colores según el tema (claro u oscuro).
struct Palette {
    let paper: Color
    let surface: Color
    let ink: Color
    let muted: Color
    let faint: Color
    let rule: Color
    let accent: Color
    let accentSoft: Color
    let primaryBg: Color
    let onPrimary: Color
    let warn: Color

    static func of(_ scheme: ColorScheme) -> Palette {
        if scheme == .dark {
            return Palette(
                paper: Brand.vigilia, surface: Brand.medianoche, ink: Brand.lino,
                muted: Brand.lino.opacity(0.68), faint: Brand.lino.opacity(0.4), rule: Brand.lino.opacity(0.14),
                accent: Brand.oro, accentSoft: Brand.oro.opacity(0.28),
                primaryBg: Brand.oro, onPrimary: Brand.vigilia, warn: Color(hex: 0xE59A7F)
            )
        }
        return Palette(
            paper: Brand.alba, surface: .white, ink: Brand.vigilia,
            muted: Brand.vigilia.opacity(0.64), faint: Brand.vigilia.opacity(0.4), rule: Brand.vigilia.opacity(0.12),
            accent: Brand.bronce, accentSoft: Brand.oro.opacity(0.32),
            primaryBg: Brand.vigilia, onPrimary: Brand.alba, warn: Color(hex: 0xA4442F)
        )
    }
}

/// "El trazo que se levanta": la línea del horizonte que se dobla hacia arriba.
struct Trazo: View {
    var color: Color = Brand.oro
    var body: some View {
        Canvas { ctx, size in
            let h = size.height
            let w = size.width
            var line = Path()
            line.move(to: CGPoint(x: 0, y: h - 3))
            line.addLine(to: CGPoint(x: w * 0.62, y: h - 3))
            line.addCurve(
                to: CGPoint(x: w * 0.8, y: h * 0.42),
                control1: CGPoint(x: w * 0.74, y: h - 3),
                control2: CGPoint(x: w * 0.8, y: h * 0.78)
            )
            ctx.stroke(line, with: .color(color), style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            let r: CGFloat = 4
            ctx.fill(Path(ellipseIn: CGRect(x: w * 0.8 - r, y: h * 0.18 - r, width: r * 2, height: r * 2)), with: .color(color))
        }
        .frame(width: 96, height: 28)
        .accessibilityHidden(true)
    }
}

struct CardStyle: ViewModifier {
    let p: Palette
    func body(content: Content) -> some View {
        content
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(p.surface)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(p.rule))
    }
}

extension View {
    func card(_ p: Palette) -> some View { modifier(CardStyle(p: p)) }
}

struct Eyebrow: View {
    let text: String
    let p: Palette
    var body: some View {
        Text(text.uppercased())
            .font(Brand.ui(12, 600))
            .tracking(1.4)
            .foregroundStyle(p.accent)
    }
}

/// Botón principal: cápsula dorada.
struct GoldButton: View {
    let title: String
    var icon: String? = nil
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if let icon { Image(systemName: icon).font(.system(size: 18, weight: .semibold)) }
                Text(title).font(Brand.ui(17, 600))
            }
            .foregroundStyle(Brand.vigilia)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(Brand.oro)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

struct OutlineButton: View {
    let title: String
    let p: Palette
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(Brand.ui(16, 500))
                .foregroundStyle(p.ink)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .overlay(Capsule().stroke(p.rule, lineWidth: 1.5))
        }
        .buttonStyle(.plain)
    }
}

/// Los siete días de la semana como puntos (leídos o no).
struct WeekDots: View {
    let marks: [Bool]
    let p: Palette
    var body: some View {
        HStack(spacing: 8) {
            ForEach(0..<7, id: \.self) { i in
                Text(AlarmItem.letters[i])
                    .font(Brand.ui(13, 600))
                    .frame(width: 34, height: 34)
                    .foregroundStyle(marks[i] ? Brand.vigilia : p.muted)
                    .background(Circle().fill(marks[i] ? Brand.oro : Color.clear))
                    .overlay(Circle().stroke(marks[i] ? Color.clear : p.rule, lineWidth: 1.5))
            }
        }
    }
}

extension String {
    func re(_ pattern: String, _ template: String) -> String {
        replacingOccurrences(of: pattern, with: template, options: .regularExpression)
    }

    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

enum Fmt {
    static let ar = Locale(identifier: "es_AR")

    static func hm(_ d: Date) -> String {
        let c = Calendar.current.dateComponents([.hour, .minute], from: d)
        return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    static func longDate(_ d: Date) -> String {
        let f = DateFormatter()
        f.locale = ar
        f.dateFormat = "EEEE d 'de' MMMM"
        let s = f.string(from: d)
        return s.prefix(1).uppercased() + s.dropFirst()
    }

    static func duration(_ seconds: TimeInterval) -> String {
        let m = Int(seconds / 60)
        let h = m / 60
        let r = m % 60
        if h == 0 { return "\(r) min" }
        return r == 0 ? "\(h) h" : "\(h) h \(r) min"
    }
}
