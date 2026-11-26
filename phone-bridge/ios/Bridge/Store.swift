import Foundation
import SwiftUI
import UserNotifications

// MARK: - Models

struct SMS: Codable, Identifiable, Hashable {
    var id: String
    var peer: String
    var name: String?
    var body: String
    var ts: Double
    var out: Bool
    var failed: Bool? = nil

    var threadKey: String { phoneKey(peer) }
    var date: Date { Date(timeIntervalSince1970: ts) }
}

struct CallEntry: Codable, Identifiable, Hashable {
    var id: String
    var number: String
    var name: String?
    var type: String      // in | out | missed | rejected
    var ts: Double
    var dur: Double

    var date: Date { Date(timeIntervalSince1970: ts) }
    var isMissed: Bool { type == "missed" }
}

struct Contact: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var numbers: [String]
}

struct Conversation: Identifiable, Hashable {
    var id: String        // thread key
    var title: String
    var peer: String
    var last: SMS
    var unread: Bool
}

struct CallInfo: Equatable {
    var state: String     // ringing | dialing | active
    var number: String
    var name: String?
    var started: Date?
}

/// Same person whether written +98912…, 0912… or 912…
func phoneKey(_ number: String) -> String {
    let digits = String(number.filter(\.isNumber).suffix(10))
    return digits.isEmpty ? number : digits
}

/// Latin initials like the system apps ("Hamed Mohamadi" → "HM"); other scripts get the silhouette.
func initials(_ name: String?) -> String? {
    guard let name else { return nil }
    let words = name.split(separator: " ").filter { $0.first.map { $0.isASCII && $0.isLetter } == true }
    let letters = words.prefix(2).compactMap(\.first).map(String.init)
    return letters.isEmpty ? nil : letters.joined().uppercased()
}

private struct Snapshot: Codable {
    var messages: [SMS] = []
    var calls: [CallEntry] = []
    var contacts: [Contact] = []
    var lastRead: [String: Double] = [:]
}

// MARK: - Store

@MainActor
final class BridgeStore: ObservableObject {
    @Published private(set) var pairing: Pairing?
    @Published private(set) var relayConnected = false
    @Published private(set) var androidOnline = false
    @Published private(set) var messages: [SMS] = []
    @Published private(set) var calls: [CallEntry] = []
    @Published private(set) var contacts: [Contact] = []
    @Published private(set) var lastRead: [String: Double] = [:]
    @Published var call: CallInfo?
    @Published var forwardStatus: String?
    @Published var myNumber: String = UserDefaults.standard.string(forKey: "myNumber") ?? "" {
        didSet { UserDefaults.standard.set(myNumber, forKey: "myNumber") }
    }
    @Published var forwardMode: String = UserDefaults.standard.string(forKey: "forwardMode") ?? "off"
    @Published var internetAudio: Bool = UserDefaults.standard.object(forKey: "internetAudio") as? Bool ?? true {
        didSet { UserDefaults.standard.set(internetAudio, forKey: "internetAudio") }
    }
    @Published var stayConnected: Bool = UserDefaults.standard.object(forKey: "stayConnected") as? Bool ?? true {
        didSet { UserDefaults.standard.set(stayConnected, forKey: "stayConnected") }
    }
    @Published private(set) var secure = false          // forward-secret session is up
    @Published private(set) var audioOn = false
    @Published var muted = false { didSet { audio.muted = muted } }
    @Published var speaker = false { didSet { audio.setSpeaker(speaker) } }
    @Published var audioProblem: String?
    /// Where the Android phone plays the call: "speaker" or "cable" (silent).
    @Published private(set) var audioRoute: String?
    /// How the Android phone hears the caller: "call line" (Shizuku, clean) or a microphone name.
    @Published private(set) var audioSource: String?

    var isActive = true
    /// Launch argument used by the automated end-to-end test.
    let testMode = UserDefaults.standard.string(forKey: "pairHex") != nil

    /// Pairing given as a hex launch argument (plain JSON would be parsed as a plist by iOS).
    private static var launchPairing: Pairing? {
        guard let hex = UserDefaults.standard.string(forKey: "pairHex"), hex.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            guard let b = UInt8(hex[i..<j], radix: 16) else { return nil }
            bytes.append(b)
            i = j
        }
        return String(bytes: bytes, encoding: .utf8).flatMap(Pairing.parse)
    }
    private var crypto: BridgeCrypto?
    private var relay: RelayConnection?
    private var bleCrypto: BridgeCrypto?
    private var ble: BleLink?
    @Published private(set) var bleUp = false
    private var relayPeer = false
    /// Something can carry messages right now (server or Bluetooth).
    var linkUp: Bool { relayConnected || bleUp }

    private func updateOnline() {
        androidOnline = relayPeer || bleUp
        secure = (crypto?.hasSession ?? false) || (bleCrypto?.hasSession ?? false)
    }
    private var outbox: [[String: Any]] = []
    private let audio = CallAudio()
    private let keepAlive = KeepAlive()
    private lazy var callKit: CallKitManager? = testMode ? nil : makeCallKit()
    private var ringingViaCallKit = false
    private var nameCache: [String: String] = [:]

    init() {
        let snap = Self.load()
        messages = snap.messages
        calls = snap.calls
        contacts = snap.contacts
        lastRead = snap.lastRead
        rebuildNames()
        if let p = Self.launchPairing {
            use(p)
        } else if let p = Keychain.load() {
            use(p)
        }
        if !testMode {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        }
    }

    // MARK: pairing

    func pair(with p: Pairing) {
        Keychain.save(p)
        messages = []; calls = []; contacts = []; lastRead = [:]
        save()
        use(p)
    }

    func unpair() {
        relay?.stop()
        relay = nil
        crypto = nil
        ble?.stop()
        ble = nil
        bleCrypto = nil
        bleUp = false
        Keychain.delete()
        pairing = nil
        messages = []; calls = []; contacts = []; lastRead = [:]
        call = nil
        save()
    }

    private func use(_ p: Pairing) {
        pairing = p
        startBluetooth(p)
        guard !p.u.isEmpty, let url = URL(string: p.u) else { return } // Bluetooth only
        let c = BridgeCrypto(keyData: p.keyData, role: "i")
        crypto = c
        relay?.stop()
        let r = RelayConnection(url: url, room: c.room, pin: p.p)
        r.onState = { [weak self] up in
            Task { @MainActor in
                self?.relayConnected = up
                if !up { self?.relayPeer = false; self?.crypto?.dropSession(); self?.updateOnline() }
            }
        }
        r.onText = { [weak self] text in
            Task { @MainActor in self?.receive(text) }
        }
        relay = r
        r.start()
    }

    func reconnect() { relay?.start() }

    /// Direct Bluetooth link: preferred whenever the Android phone is near.
    private func startBluetooth(_ p: Pairing) {
        ble?.stop()
        guard !testMode else { return } // the Simulator has no Bluetooth
        let c = BridgeCrypto(keyData: p.keyData, role: "i")
        bleCrypto = c
        let link = BleLink(uuidFor: { day in c.bleUUID(day: day) })
        // CoreBluetooth calls back on the main queue.
        link.onOpen = { [weak self] in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.bleUp = true
                if let hello = c.startHandshake() { link.send(hello) }
                self.updateOnline()
            }
        }
        link.onFrame = { [weak self] text in
            MainActor.assumeIsolated { self?.receive(text, viaBluetooth: true) }
        }
        link.onClosed = { [weak self] in
            MainActor.assumeIsolated {
                c.dropSession()
                self?.bleUp = false
                self?.updateOnline()
            }
        }
        ble = link
    }

    // MARK: incoming

    private func receive(_ text: String, viaBluetooth: Bool = false) {
        if text.hasPrefix("#") {
            if text == "#peer:1" {
                if !relayPeer, let hello = crypto?.startHandshake() { relay?.send(hello) }
                relayPeer = true
            } else if text == "#peer:0" {
                relayPeer = false
                crypto?.dropSession()
            }
            updateOnline()
            return
        }
        guard let c = viaBluetooth ? bleCrypto : crypto,
              let msg = c.open(text), let type = msg["t"] as? String else { return }
        let fromSession = msg["_s"] as? Bool ?? false
        if type == "hello" {
            let (reply, isNew) = c.onHello(msg)
            if let reply { if viaBluetooth { ble?.send(reply) } else { relay?.send(reply) } }
            updateOnline()
            if isNew { sessionReady() }
            return
        }
        if type == "au" {
            if fromSession, let d = msg["d"] as? String, let data = Data(base64Encoded: d) { audio.play(data) }
            return
        }
        // Without the session key only data the Android queued while this iPhone was offline is accepted.
        let staticAllowed: Set<String> = ["sms", "call", "sms_status"]
        if !fromSession && !staticAllowed.contains(type) { return }
        if !fromSession, type == "call", Date().timeIntervalSince1970 - ((msg["ts"] as? NSNumber)?.doubleValue ?? 0) > 60 { return }
        switch type {
        case "sms":
            if let s = Self.sms(from: msg), !messages.contains(where: { $0.id == s.id }) {
                messages.append(s)
                save()
                notify(title: displayName(s.peer, fallback: s.name), body: s.body,
                       info: ["part": "messages", "peer": s.threadKey])
            }
        case "sms_batch":
            let items = (msg["items"] as? [[String: Any]] ?? []).compactMap(Self.sms(from:))
            let newest = items.map(\.ts).max() ?? 0
            let firstSync = messages.isEmpty && lastRead.isEmpty
            // The Android inbox is the source of truth; keep only local items newer than it.
            messages = items + messages.filter { $0.ts > newest && !$0.id.hasPrefix("s") }
            if firstSync {
                // Do not mark the whole history as unread on first pairing.
                for m in messages { lastRead[m.threadKey] = max(lastRead[m.threadKey] ?? 0, m.ts) }
            }
            save()
        case "calls_batch":
            calls = (msg["items"] as? [[String: Any]] ?? []).compactMap(Self.callEntry(from:))
            save()
        case "contacts_batch":
            contacts = (msg["items"] as? [[String: Any]] ?? []).compactMap { d in
                guard let id = d["id"] as? String, let name = d["name"] as? String else { return nil }
                return Contact(id: id, name: name, numbers: d["numbers"] as? [String] ?? [])
            }
            rebuildNames()
            save()
        case "sms_status":
            if let id = msg["id"] as? String, let ok = msg["ok"] as? Bool, !ok,
               let i = messages.firstIndex(where: { $0.id == id }) {
                messages[i].failed = true
            }
        case "forward_status":
            let ok = msg["ok"] as? Bool ?? false
            forwardStatus = ok ? "Sent to the carrier. Android shows the carrier's answer."
                               : "Could not send. Check the number and the Android phone permissions."
        case "call":
            let state = msg["state"] as? String ?? "ended"
            let number = msg["number"] as? String ?? call?.number ?? "Unknown"
            let name = msg["name"] as? String
            if state == "ended" {
                callKit?.remoteEnded()
                endAudio()
                call = nil
                ringingViaCallKit = false
            } else {
                let started = state == "active" ? (call?.started ?? Date()) : nil
                let isNewRing = state == "ringing" && call?.state != "ringing"
                call = CallInfo(state: state, number: number, name: name ?? call?.name, started: started)
                if isNewRing { ring(number: number, name: displayName(number, fallback: name)) }
                if state == "active" { callKit?.connected() }
            }
        case "audio_start":
            if internetAudio && !audio.running && callKit?.current == nil { startAudio(activateSession: true) }
        case "audio_error":
            audioProblem = "On the Android phone, do step 4b (Shizuku, clean sound) or turn on “Ghafoorii call audio” in Accessibility settings."
        case "audio_info":
            let kind = msg["kind"] as? String
            let detail = msg["detail"] as? String
            if kind == "route" { audioRoute = detail }
            if kind == "source" { audioSource = detail }
            if kind == "problem" {
                audioProblem = detail == "mic"
                    ? "The Android phone could not open its microphone. Check its microphone permission."
                    : "No caller sound reaches this iPhone. On the Android phone, do step 4b (Shizuku) or turn on “Ghafoorii call audio” in Accessibility settings."
            }
        default:
            break
        }
    }

    private static func sms(from m: [String: Any]) -> SMS? {
        guard let id = m["id"] as? String, let peer = m["peer"] as? String else { return nil }
        return SMS(id: id, peer: peer, name: m["name"] as? String, body: m["body"] as? String ?? "",
                   ts: (m["ts"] as? NSNumber)?.doubleValue ?? Date().timeIntervalSince1970,
                   out: m["out"] as? Bool ?? false)
    }

    private static func callEntry(from m: [String: Any]) -> CallEntry? {
        guard let id = m["id"] as? String else { return nil }
        return CallEntry(id: id, number: m["number"] as? String ?? "", name: m["name"] as? String,
                         type: m["type"] as? String ?? "in",
                         ts: (m["ts"] as? NSNumber)?.doubleValue ?? 0,
                         dur: (m["dur"] as? NSNumber)?.doubleValue ?? 0)
    }

    // MARK: commands

    /// Commands go only under the forward-secret session key; until it is up they wait in the outbox.
    private func send(_ obj: [String: Any]) {
        // Bluetooth first when the Android phone is near; otherwise the server.
        if bleUp, let b = bleCrypto, b.hasSession, let text = b.seal(obj) {
            ble?.send(text)
            return
        }
        guard let c = crypto, c.hasSession, let text = c.seal(obj) else {
            if obj["t"] as? String != "au" { outbox.append(obj) }
            return
        }
        relay?.send(text)
    }

    private func sessionReady() {
        send(["t": "sync"])
        let waiting = outbox
        outbox.removeAll()
        for o in waiting { send(o) }
        runSelfTest()
    }

    func sendSMS(to peer: String, body: String) {
        let text = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !peer.isEmpty else { return }
        let id = "o-" + UUID().uuidString
        messages.append(SMS(id: id, peer: peer, name: nil, body: text, ts: Date().timeIntervalSince1970, out: true))
        markRead(phoneKey(peer))
        save()
        send(["t": "send_sms", "to": peer, "body": text, "id": id])
    }

    // MARK: calls

    private func makeCallKit() -> CallKitManager {
        let ck = CallKitManager()
        ck.onAnswer = { [weak self] in self?.answeredLocally() }
        ck.onEnd = { [weak self] in
            guard let self else { return }
            self.send(["t": "hangup"])
            self.endAudio()
            self.call = nil
            self.ringingViaCallKit = false
        }
        ck.onStartOutgoing = { [weak self] number in
            guard let self else { return }
            self.send(["t": "dial", "number": number, "audio": self.internetAudio])
        }
        ck.onMute = { [weak self] m in self?.muted = m }
        ck.onAudioSession = { [weak self] active in
            guard let self else { return }
            if active, self.internetAudio { self.startAudio(activateSession: false) } else if !active { self.endAudio() }
        }
        return ck
    }

    private func ring(number: String, name: String) {
        if isActive || callKit == nil {
            if testMode && UserDefaults.standard.bool(forKey: "autoAnswer") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in self?.answer() }
            }
            if callKit == nil { return } // our own call screen shows it
        }
        ringingViaCallKit = true
        callKit?.reportIncoming(number: number, name: name) { [weak self] ok in
            if !ok {
                self?.ringingViaCallKit = false
                self?.notify(title: name, body: "Incoming call", info: ["part": "phone", "tab": "recents"])
            }
        }
    }

    var showsOwnCallScreen: Bool {
        guard let c = call else { return false }
        return !(c.state == "ringing" && ringingViaCallKit)
    }

    private func answeredLocally() {
        send(["t": "answer", "audio": internetAudio])
        if var c = call { c.state = "active"; c.started = Date(); call = c }
        ringingViaCallKit = false
    }

    func answer() {
        if ringingViaCallKit, let ck = callKit, ck.current != nil {
            // Let CallKit run the answer so it manages the audio session.
            ck.answerFromApp()
            return
        }
        answeredLocally()
        if internetAudio { startAudio(activateSession: true) }
    }

    /// Sends one DTMF key to the live call (best effort; some phones block it from a non-default dialer).
    func sendDTMF(_ digit: String) {
        send(["t": "dtmf", "digit": digit])
    }

    func hangup() {
        if let ck = callKit, ck.current != nil {
            ck.endFromApp()
        } else {
            send(["t": "hangup"])
            endAudio()
            call = nil
        }
    }

    func dial(_ number: String) {
        let n = number.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { return }
        call = CallInfo(state: "dialing", number: n, name: name(for: n), started: nil)
        if let ck = callKit {
            ck.startOutgoing(number: n) { [weak self] ok in
                guard let self, !ok else { return }
                self.send(["t": "dial", "number": n, "audio": self.internetAudio])
                if self.internetAudio { self.startAudio(activateSession: true) }
            }
        } else {
            send(["t": "dial", "number": n, "audio": internetAudio])
            if internetAudio { startAudio(activateSession: true) }
        }
    }

    private func startAudio(activateSession: Bool) {
        guard !audio.running else { return }
        keepAlive.stop()
        audio.onFrame = { [weak self] frame in
            Task { @MainActor in self?.send(["t": "au", "d": frame.base64EncodedString()]) }
        }
        do {
            try audio.start(activateSession: activateSession)
            audioOn = true
            audioProblem = nil
        } catch {
            audioOn = false
            audioProblem = "Could not start the microphone: \(error.localizedDescription)"
        }
    }

    private func endAudio() {
        audioRoute = nil
        audioSource = nil
        audio.stop()
        audioOn = false
        muted = false
        speaker = false
        if !isActive && stayConnected { keepAlive.start() }
    }

    /// Called when the app goes to the background or comes back.
    func appMovedToBackground(_ background: Bool) {
        if background {
            if stayConnected && !audio.running && !testMode { keepAlive.start() }
        } else {
            keepAlive.stop()
        }
    }

    func setForwarding(_ mode: String) {
        forwardMode = mode
        UserDefaults.standard.set(mode, forKey: "forwardMode")
        forwardStatus = "Sending…"
        send(["t": "forward", "mode": mode, "number": myNumber])
    }

    /// Automated test only: proves the iPhone → Android direction works end to end.
    private func runSelfTest() {
        guard testMode else { return }
        send(["t": "send_sms", "to": "+15550100", "body": "selftest", "id": "o-selftest"])
        if UserDefaults.standard.bool(forKey: "ring") { send(["t": "debug_ring"]) }
    }

    // MARK: derived data

    private func rebuildNames() {
        var map: [String: String] = [:]
        for c in contacts { for n in c.numbers { map[phoneKey(n)] = c.name } }
        nameCache = map
    }

    func name(for number: String) -> String? { nameCache[phoneKey(number)] }

    func displayName(_ number: String, fallback: String? = nil) -> String {
        name(for: number) ?? fallback ?? formatNumber(number)
    }

    func markRead(_ key: String) {
        let newest = messages.filter { $0.threadKey == key }.map(\.ts).max() ?? Date().timeIntervalSince1970
        if (lastRead[key] ?? 0) < newest {
            lastRead[key] = newest
            save()
        }
    }

    var conversations: [Conversation] {
        Dictionary(grouping: messages, by: \.threadKey).compactMap { key, list in
            guard let last = list.max(by: { $0.ts < $1.ts }) else { return nil }
            let fallback = list.compactMap(\.name).first
            let unread = list.contains { !$0.out && $0.ts > (lastRead[key] ?? 0) }
            return Conversation(id: key, title: displayName(last.peer, fallback: fallback),
                                peer: last.peer, last: last, unread: unread)
        }.sorted { $0.last.ts > $1.last.ts }
    }

    /// Removes a chat from this iPhone only. The SMS stay on the Android phone.
    func deleteThread(_ key: String) {
        messages.removeAll { $0.threadKey == key }
        save()
    }

    var unreadCount: Int { conversations.filter(\.unread).count }
    var missedCount: Int { calls.prefix(30).filter { $0.isMissed && $0.ts > (lastRead["#calls"] ?? 0) }.count }

    func markCallsSeen() {
        lastRead["#calls"] = Date().timeIntervalSince1970
        save()
    }

    func thread(_ key: String) -> [SMS] {
        messages.filter { $0.threadKey == key }.sorted { $0.ts < $1.ts }
    }

    // MARK: notifications & storage

    /// `info` tells the app which part to open when the notification is tapped (see AppDelegate).
    fileprivate func notify(title: String, body: String, info: [String: String] = [:]) {
        guard !isActive else { return }
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.userInfo = info
        c.threadIdentifier = info["peer"] ?? "calls" // groups notifications per chat, like Messages
        c.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
    }

    private static var fileURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("bridge.json")
    }

    private static func load() -> Snapshot {
        guard let data = try? Data(contentsOf: fileURL) else { return Snapshot() }
        return (try? JSONDecoder().decode(Snapshot.self, from: data)) ?? Snapshot()
    }

    private func save() {
        let snap = Snapshot(messages: messages, calls: calls, contacts: contacts, lastRead: lastRead)
        let url = Self.fileURL
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(snap) {
            try? data.write(to: url, options: [.atomic, .completeFileProtection])
        }
    }
}

/// "+989121234567" → "+98 912 123 4567", "09121234567" → "0912 123 4567"
func formatNumber(_ raw: String) -> String {
    let d = raw.filter { $0.isNumber || $0 == "+" }
    if d.hasPrefix("+98"), d.count == 13 {
        let s = Array(d)
        return "+98 \(String(s[3..<6])) \(String(s[6..<9])) \(String(s[9...]))"
    }
    if d.hasPrefix("09"), d.count == 11 {
        let s = Array(d)
        return "\(String(s[0..<4])) \(String(s[4..<7])) \(String(s[7...]))"
    }
    return raw
}
