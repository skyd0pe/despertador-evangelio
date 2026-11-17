import Foundation

/// Sigue la lectura: compara lo que reconoce el micrófono con el texto, palabra por palabra,
/// con tolerancia (acentos, letras que suenan igual, palabras salteadas). Los nombres propios
/// tienen todavía más tolerancia, porque el reconocimiento de voz suele escribirlos mal.
final class Matcher {
    struct Word {
        let para: Int
        let tok: Int
        let norm: String
        let phon: String
        let proper: Bool
        /// 0 = sin leer, 1 = leída, 2 = salteada
        var state = 0
    }

    /// Párrafos, cada uno partido en sus palabras tal como se muestran.
    private(set) var tokens: [[String]] = []
    private(set) var words: [Word] = []
    /// Para cada párrafo y token, el índice de la palabra (o -1 si es solo puntuación).
    private(set) var index: [[Int]] = []
    /// Próxima palabra esperada.
    private(set) var ptr = 0

    private static let look = 6

    init(text: String) {
        let paras = text.components(separatedBy: "\n").map { $0.trimmed }.filter { !$0.isEmpty }
        var prev: Character? = nil
        for (pi, p) in paras.enumerated() {
            let toks = p.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
            var idx: [Int] = []
            for (ti, t) in toks.enumerated() {
                let n = Matcher.norm(t)
                if n.isEmpty {
                    idx.append(-1)
                } else {
                    idx.append(words.count)
                    words.append(Word(para: pi, tok: ti, norm: n, phon: Matcher.phonetic(n), proper: Matcher.isProper(t, prev: prev)))
                }
                prev = t.last
            }
            tokens.append(toks)
            index.append(idx)
        }
    }

    var finished: Bool { !words.isEmpty && ptr >= words.count - 1 }

    var score: Double {
        words.isEmpty ? 0 : Double(words.filter { $0.state == 1 }.count) / Double(words.count)
    }

    func reset() {
        ptr = 0
        for i in words.indices { words[i].state = 0 }
    }

    /// Aplica un tramo ya confirmado por el reconocedor.
    func commit(_ heard: String) {
        let (p, marks) = align(split(heard), from: ptr)
        for (i, s) in marks { words[i].state = s }
        ptr = p
    }

    /// Palabras que se marcarían con un resultado parcial, sin aplicarlo.
    func provisional(_ heard: String) -> Set<Int> {
        Set(align(split(heard), from: ptr).1.filter { $0.1 == 1 }.map { $0.0 })
    }

    /// Si con este resultado parcial ya se llegó al final del texto.
    func reachesEnd(_ prov: Set<Int>) -> Bool {
        guard let m = prov.max() else { return false }
        return m + 1 >= words.count - 1
    }

    private func split(_ t: String) -> [String] {
        t.split(whereSeparator: { $0.isWhitespace }).map { Matcher.norm(String($0)) }.filter { !$0.isEmpty }
    }

    private func align(_ heard: [String], from start: Int) -> (Int, [(Int, Int)]) {
        var p = start
        var marks: [(Int, Int)] = []
        for h in heard {
            let hp = Matcher.phonetic(h)
            let end = min(p + Matcher.look, words.count)
            var k = p
            while k < end {
                if Matcher.same(words[k], h, hp) {
                    // Un nombre propio que el reconocedor no entendió no cuenta como salteado.
                    var s = p
                    while s < k {
                        marks.append((s, words[s].proper ? 1 : 2))
                        s += 1
                    }
                    marks.append((k, 1))
                    p = k + 1
                    break
                }
                k += 1
            }
        }
        return (p, marks)
    }

    static func norm(_ s: String) -> String {
        let f = s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).lowercased()
        var out = ""
        for u in f.unicodeScalars where (97...122).contains(u.value) || (48...57).contains(u.value) {
            out.unicodeScalars.append(u)
        }
        return out
    }

    /// Cómo suena en castellano rioplatense: b/v, s/z/c, y/ll, h muda, qu/k, letras dobles.
    static func phonetic(_ n: String) -> String {
        var s = n.replacingOccurrences(of: "ch", with: "#")
            .replacingOccurrences(of: "h", with: "")
            .replacingOccurrences(of: "#", with: "ch")
        s = s.replacingOccurrences(of: "v", with: "b")
            .replacingOccurrences(of: "w", with: "u")
            .replacingOccurrences(of: "ll", with: "y")
            .replacingOccurrences(of: "qu", with: "k")
        s = s.re("c([ei])", "s$1")
            .replacingOccurrences(of: "z", with: "s")
            .replacingOccurrences(of: "c", with: "k")
            .replacingOccurrences(of: "x", with: "ks")
        return s.re("(.)\\1+", "$1")
    }

    /// Mayúscula en medio de una oración: nombre propio.
    private static func isProper(_ raw: String, prev: Character?) -> Bool {
        guard let first = raw.first(where: { $0.isLetter }), first.isUppercase, let prev else { return false }
        return !".!?¿¡«»:;—–-\"“”'".contains(prev)
    }

    private static func same(_ w: Word, _ heard: String, _ hp: String) -> Bool {
        if w.norm == heard || w.phon == hp { return true }
        let d = lev(w.phon, hp)
        if w.proper {
            if w.phon.count >= 4 && d <= 2 { return true }
            if w.phon.count >= 7 && d <= 3 { return true }
            if w.phon.count >= 4 && hp.count >= 3 && w.phon.prefix(3) == hp.prefix(3) { return true }
            return false
        }
        if w.phon.count >= 5 && d <= 1 { return true }
        if w.phon.count >= 8 && d <= 2 { return true }
        return false
    }

    private static func lev(_ a: String, _ b: String) -> Int {
        let x = Array(a)
        let y = Array(b)
        if abs(x.count - y.count) > 3 { return 9 }
        var d = Array(0...y.count)
        if x.isEmpty { return y.count }
        for i in 1...x.count {
            var prev = d[0]
            d[0] = i
            if y.isEmpty { continue }
            for j in 1...y.count {
                let t = d[j]
                d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (x[i - 1] == y[j - 1] ? 0 : 1))
                prev = t
            }
        }
        return d[y.count]
    }
}
