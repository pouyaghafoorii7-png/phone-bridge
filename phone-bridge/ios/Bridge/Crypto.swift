import CryptoKit
import Security
import Foundation

/// Protocol v2 (docs/PROTOCOL.md): AES-256-GCM frames, X25519 session keys with forward secrecy,
/// replay and reflection protection. Use from one thread (the main actor).
final class BridgeCrypto {
    let room: String
    private let pairKey: Data
    private let role: String
    private let staticKey: SymmetricKey
    private var priv: Curve25519.KeyAgreement.PrivateKey?
    private var session: SymmetricKey?
    private var sendN = 0
    private var recvN = 0
    private var seen = Set<String>()
    private var seenOrder: [String] = []

    var hasSession: Bool { session != nil }

    init(keyData: Data, role: String = "i") {
        pairKey = keyData
        self.role = role
        let hex = SHA256.hash(data: keyData).map { String(format: "%02x", $0) }.joined()
        room = String(hex.prefix(32))
        staticKey = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: keyData), salt: Data(),
                                           info: Data("pb2 static".utf8), outputByteCount: 32)
    }

    /// Bluetooth service UUID for one UTC day ("YYYY-MM-DD"); it changes daily so the phone cannot be tracked.
    func bleUUID(day: String) -> UUID {
        let k = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: pairKey), salt: Data(),
                                       info: Data("pb2 ble \(day)".utf8), outputByteCount: 16)
        let b = k.withUnsafeBytes { Array($0) }
        return UUID(uuid: (b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
                           b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]))
    }

    /// New ephemeral key (drops the old session). Returns the hello frame to send.
    func startHandshake() -> String? {
        priv = Curve25519.KeyAgreement.PrivateKey()
        session = nil
        return hello(reply: false)
    }

    func useEphemeral(_ raw: Data) {
        priv = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw)
        session = nil
    }

    func dropSession() { session = nil }

    private func hello(reply: Bool) -> String? {
        let p = priv ?? Curve25519.KeyAgreement.PrivateKey()
        priv = p
        return seal(["t": "hello", "e": p.publicKey.rawRepresentation.base64EncodedString(), "r": reply], useSession: false)
    }

    /// Derives the session key from the peer's hello. Returns (reply frame if needed, whether the key is new).
    func onHello(_ msg: [String: Any]) -> (reply: String?, isNew: Bool) {
        guard let e = msg["e"] as? String, let peerRaw = Data(base64Encoded: e),
              let peer = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerRaw) else { return (nil, false) }
        let p = priv ?? Curve25519.KeyAgreement.PrivateKey()
        priv = p
        guard let shared = try? p.sharedSecretFromKeyAgreement(with: peer) else { return (nil, false) }
        let mine = p.publicKey.rawRepresentation
        let (lo, hi) = mine.lexicographicallyPrecedes(peerRaw) ? (mine, peerRaw) : (peerRaw, mine)
        let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: pairKey,
                                                 sharedInfo: Data("pb2 session".utf8) + lo + hi, outputByteCount: 32)
        let isNew = session.map { old in old.withUnsafeBytes { Data($0) } != key.withUnsafeBytes { Data($0) } } ?? true
        session = key
        if isNew { sendN = 0; recvN = 0 }
        let reply = (msg["r"] as? Bool ?? false) ? nil : hello(reply: true)
        return (reply, isNew)
    }

    func seal(_ object: [String: Any], useSession: Bool? = nil) -> String? {
        var o = object
        o["from"] = role
        o["ts"] = Date().timeIntervalSince1970
        let key: SymmetricKey
        let kind: UInt8
        if (useSession ?? hasSession), let s = session {
            sendN += 1
            o["n"] = sendN
            key = s
            kind = 1
        } else {
            var r = [UInt8](repeating: 0, count: 8)
            _ = SecRandomCopyBytes(kSecRandomDefault, r.count, &r)
            o["mid"] = r.map { String(format: "%02x", $0) }.joined()
            key = staticKey
            kind = 0
        }
        let header = Data([2, kind])
        guard let json = try? JSONSerialization.data(withJSONObject: o),
              let box = try? AES.GCM.seal(json, using: key, authenticating: header),
              let combined = box.combined else { return nil }
        return (header + combined).base64EncodedString()
    }

    /// Returns the message with "_s" = true when it came under the session key, or nil if rejected.
    func open(_ text: String) -> [String: Any]? {
        guard let d = Data(base64Encoded: text), d.count >= 30, d[d.startIndex] == 2 else { return nil }
        let kind = d[d.startIndex + 1]
        guard kind == 0 || kind == 1, let key = kind == 1 ? session : staticKey else { return nil }
        let header = d.prefix(2)
        guard let box = try? AES.GCM.SealedBox(combined: d.dropFirst(2)),
              let plain = try? AES.GCM.open(box, using: key, authenticating: header),
              var o = (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any] else { return nil }
        if o["from"] as? String == role { return nil } // reflected frame
        if kind == 1 {
            let n = (o["n"] as? NSNumber)?.intValue ?? 0
            guard n > recvN else { return nil }
            recvN = n
        } else {
            let ts = (o["ts"] as? NSNumber)?.doubleValue ?? 0
            guard let mid = o["mid"] as? String, abs(Date().timeIntervalSince1970 - ts) < 7 * 86400,
                  !seen.contains(mid) else { return nil }
            seen.insert(mid)
            seenOrder.append(mid)
            if seenOrder.count > 5000 { seen.remove(seenOrder.removeFirst()) }
        }
        o["_s"] = kind == 1
        return o
    }
}

/// G.711 mu-law, the same as the Android side.
enum MuLaw {
    static func encode(_ samples: [Int16]) -> Data {
        Data(samples.map(encodeOne))
    }

    static func decode(_ data: Data) -> [Int16] {
        data.map(decodeOne)
    }

    private static func encodeOne(_ sample: Int16) -> UInt8 {
        var s: Int = Int(sample)
        let sign: Int = s < 0 ? 0x80 : 0
        if s < 0 { s = -s }
        s = min(s, 32635) + 0x84
        var exp: Int = 7
        var mask: Int = 0x4000
        while (s & mask) == 0 && exp > 0 {
            exp -= 1
            mask >>= 1
        }
        let mantissa: Int = (s >> (exp + 3)) & 0x0F
        let packed: Int = sign | (exp << 4) | mantissa
        return UInt8(truncatingIfNeeded: ~packed)
    }

    private static func decodeOne(_ byte: UInt8) -> Int16 {
        let u: Int = Int(~byte)
        let mantissa: Int = (u & 0x0F) << 3
        let exp: Int = (u >> 4) & 7
        var s: Int = ((mantissa + 0x84) << exp) - 0x84
        if (u & 0x80) != 0 { s = -s }
        return Int16(clamping: s)
    }
}

/// What the Android QR code contains: {"u": relay url, "k": base64 key, "p": certificate pin}.
struct Pairing: Codable, Equatable {
    let u: String
    let k: String
    /// SHA-256 fingerprint of the relay's TLS certificate (wss:// only).
    var p: String? = nil

    static func parse(_ text: String) -> Pairing? {
        guard let data = text.trimmingCharacters(in: .whitespacesAndNewlines).data(using: .utf8),
              let p = try? JSONDecoder().decode(Pairing.self, from: data),
              p.u.isEmpty || URL(string: p.u) != nil, // empty: Bluetooth only
              Data(base64Encoded: p.k)?.count == 32 else { return nil }
        return p
    }

    var keyData: Data { Data(base64Encoded: k) ?? Data() }
}

/// The pairing key lives only in the Keychain, on this device.
enum Keychain {
    private static let account = "pairing"

    static func save(_ p: Pairing) {
        guard let data = try? JSONEncoder().encode(p) else { return }
        delete()
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemAdd(q as CFDictionary, nil)
    }

    static func load() -> Pairing? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return try? JSONDecoder().decode(Pairing.self, from: data)
    }

    static func delete() {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: account]
        SecItemDelete(q as CFDictionary)
    }
}
