import Foundation
import AVFoundation
import Speech

enum Audio {
    static func activate() throws {
        let s = AVAudioSession.sharedInstance()
        try s.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
        try s.setActive(true)
    }
}

/// Guarda el pedido de reconocimiento para que el micrófono le pase audio desde su propio hilo.
private final class RequestBox: @unchecked Sendable {
    private let lock = NSLock()
    private var req: SFSpeechAudioBufferRecognitionRequest?

    func set(_ r: SFSpeechAudioBufferRecognitionRequest?) {
        lock.lock()
        req = r
        lock.unlock()
    }

    func append(_ b: AVAudioPCMBuffer) {
        lock.lock()
        let r = req
        lock.unlock()
        r?.append(b)
    }

    func end() {
        lock.lock()
        let r = req
        lock.unlock()
        r?.endAudio()
    }
}

enum ListenError: Error { case unavailable }

/// Escucha la lectura con el reconocimiento de voz del iPhone (castellano rioplatense).
/// Entrega resultados parciales mientras se habla y confirma cada tramo.
final class Listener {
    var onPartial: ((String) -> Void)?
    var onFinal: ((String) -> Void)?
    var onLevel: ((Float) -> Void)?

    private var recognizer: SFSpeechRecognizer?
    private let engine = AVAudioEngine()
    private let box = RequestBox()
    private var task: SFSpeechRecognitionTask?
    private var gen = 0
    private(set) var running = false
    private var rotate: Timer?

    private static func makeRecognizer() -> SFSpeechRecognizer? {
        for id in ["es-AR", "es-419", "es-US", "es-MX", "es-ES"] {
            if let r = SFSpeechRecognizer(locale: Locale(identifier: id)), r.isAvailable { return r }
        }
        return nil
    }

    func start() throws {
        guard !running else { return }
        if recognizer == nil { recognizer = Self.makeRecognizer() }
        guard recognizer != nil else { throw ListenError.unavailable }
        try Audio.activate()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.removeTap(onBus: 0)
        let box = self.box
        var counter = 0
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            box.append(buffer)
            counter += 1
            if counter % 4 == 0 {
                let level = Listener.rms(buffer)
                DispatchQueue.main.async { self?.onLevel?(level) }
            }
        }
        engine.prepare()
        try engine.start()
        running = true
        newTask()
        // Cada tanto cierra el tramo para que no se haga demasiado largo.
        rotate = Timer.scheduledTimer(withTimeInterval: 40, repeats: true) { [weak self] _ in self?.flush() }
    }

    /// Confirma ya lo que se escuchó hasta ahora.
    func flush() { box.end() }

    func stop() {
        guard running else { return }
        running = false
        gen += 1
        rotate?.invalidate()
        rotate = nil
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        box.end()
        box.set(nil)
        task?.cancel()
        task = nil
        onLevel?(0)
    }

    private func newTask() {
        guard running, let rec = recognizer else { return }
        gen += 1
        let my = gen
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.taskHint = .dictation
        req.addsPunctuation = false
        if rec.supportsOnDeviceRecognition { req.requiresOnDeviceRecognition = true }
        box.set(req)
        task = rec.recognitionTask(with: req) { [weak self] result, error in
            let text = result?.bestTranscription.formattedString ?? ""
            let isFinal = result?.isFinal ?? false
            let failed = error != nil
            DispatchQueue.main.async {
                guard let self, self.running, my == self.gen else { return }
                if !text.isEmpty {
                    if isFinal { self.onFinal?(text) } else { self.onPartial?(text) }
                }
                if isFinal || failed {
                    self.task = nil
                    let delay = isFinal ? 0.05 : 0.4
                    DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                        guard let self, self.running, my == self.gen else { return }
                        self.newTask()
                    }
                }
            }
        }
    }

    private static func rms(_ buffer: AVAudioPCMBuffer) -> Float {
        guard let data = buffer.floatChannelData?[0] else { return 0 }
        let n = Int(buffer.frameLength)
        if n == 0 { return 0 }
        var sum: Float = 0
        for i in 0..<n { sum += data[i] * data[i] }
        let rms = (sum / Float(n)).squareRoot()
        return min(1, rms * 12)
    }
}

/// El sonido de la alarma dentro de la app (cuando vuelve a sonar por silencio).
final class Ringer {
    private var player: AVAudioPlayer?
    private var ramp: Timer?

    func play(_ name: String, loop: Bool = true) {
        stop()
        guard let url = Bundle.main.url(forResource: name, withExtension: "wav") else { return }
        try? Audio.activate()
        guard let p = try? AVAudioPlayer(contentsOf: url) else { return }
        p.numberOfLoops = loop ? -1 : 0
        p.volume = loop ? 0.25 : 1
        p.play()
        player = p
        if loop {
            // Volumen progresivo.
            ramp = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] t in
                guard let p = self?.player else { t.invalidate(); return }
                p.volume = min(1, p.volume + 0.05)
                if p.volume >= 1 { t.invalidate() }
            }
        }
    }

    func stop() {
        ramp?.invalidate()
        ramp = nil
        player?.stop()
        player = nil
    }
}
