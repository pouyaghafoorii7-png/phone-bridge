import AVFoundation

/// Call sound over the internet: microphone → 16 kHz mu-law frames → Android, and back.
/// Voice processing gives echo cancellation, so the caller does not hear themselves.
final class CallAudio {
    static let rate = 16000.0
    static let frame = 640 // 40 ms

    var onFrame: ((Data) -> Void)?
    var muted = false
    private(set) var running = false

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: CallAudio.rate, channels: 1, interleaved: false)!
    private var converter: AVAudioConverter?
    private var captured: [Float] = []        // touched only on the audio thread
    private let lock = NSLock()
    private var queued = 0

    static func configureSession() {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth])
    }

    /// [activateSession]: false when CallKit activates the session itself.
    func start(activateSession: Bool) throws {
        guard !running else { return }
        Self.configureSession()
        if activateSession { try AVAudioSession.sharedInstance().setActive(true) }
        let input = engine.inputNode
        try? input.setVoiceProcessingEnabled(true)
        let inFormat = input.outputFormat(forBus: 0)
        converter = AVAudioConverter(from: inFormat, to: format)
        input.installTap(onBus: 0, bufferSize: 2048, format: inFormat) { [weak self] buffer, _ in
            self?.capture(buffer)
        }
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        engine.prepare()
        try engine.start()
        player.play()
        running = true
    }

    func stop() {
        guard running else { return }
        running = false
        engine.inputNode.removeTap(onBus: 0)
        player.stop()
        engine.stop()
        engine.detach(player)
        lock.lock(); queued = 0; lock.unlock()
        captured.removeAll()
    }

    func setSpeaker(_ on: Bool) {
        try? AVAudioSession.sharedInstance().overrideOutputAudioPort(on ? .speaker : .none)
    }

    // MARK: capture (audio thread)

    private func capture(_ buffer: AVAudioPCMBuffer) {
        guard let converter else { return }
        let ratio = format.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
        guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return }
        var given = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if given { status.pointee = .noDataNow; return nil }
            given = true
            status.pointee = .haveData
            return buffer
        }
        guard error == nil, let ch = out.floatChannelData?[0] else { return }
        captured.append(contentsOf: UnsafeBufferPointer(start: ch, count: Int(out.frameLength)))
        while captured.count >= Self.frame {
            let chunk = captured.prefix(Self.frame)
            captured.removeFirst(Self.frame)
            let pcm: [Int16] = muted ? [Int16](repeating: 0, count: Self.frame)
                : chunk.map { Int16(clamping: Int(($0 * 32767).rounded())) }
            onFrame?(MuLaw.encode(pcm))
        }
    }

    // MARK: playback

    func play(_ mulaw: Data) {
        guard running, mulaw.count == Self.frame else { return }
        lock.lock()
        let backlog = queued
        lock.unlock()
        if backlog > 10 { return } // more than 400 ms behind: drop to keep the delay low
        if backlog == 0 { schedule([Float](repeating: 0, count: Self.frame * 2)) } // 80 ms cushion after a gap
        schedule(MuLaw.decode(mulaw).map { Float($0) / 32768 })
    }

    private func schedule(_ samples: [Float]) {
        guard let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)) else { return }
        buf.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { src in
            buf.floatChannelData![0].update(from: src.baseAddress!, count: samples.count)
        }
        lock.lock(); queued += 1; lock.unlock()
        player.scheduleBuffer(buf) { [weak self] in
            guard let self else { return }
            self.lock.lock(); self.queued -= 1; self.lock.unlock()
        }
    }
}

/// Plays silence while the app is in the background so the link stays open and
/// messages and calls arrive. Costs some battery; can be turned off in Settings.
final class KeepAlive {
    private var player: AVAudioPlayer?

    func start() {
        guard player == nil else { return }
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playback, options: [.mixWithOthers])
        try? s.setActive(true)
        player = try? AVAudioPlayer(data: Self.silentWav)
        player?.numberOfLoops = -1
        player?.volume = 0
        player?.play()
    }

    func stop() {
        player?.stop()
        player = nil
    }

    private static let silentWav: Data = {
        let rate: UInt32 = 8000, seconds: UInt32 = 1
        let dataSize = rate * seconds * 2
        var d = Data()
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        d.append(contentsOf: Array("RIFF".utf8)); u32(36 + dataSize); d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8)); u32(16); u16(1); u16(1); u32(rate); u32(rate * 2); u16(2); u16(16)
        d.append(contentsOf: Array("data".utf8)); u32(dataSize)
        d.append(Data(count: Int(dataSize)))
        return d
    }()
}
