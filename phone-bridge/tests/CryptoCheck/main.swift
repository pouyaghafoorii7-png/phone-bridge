import Foundation

// Cross-language check: opens frames made by the Python reference (tests/crypto_vector.py)
// with the app's own BridgeCrypto and writes reply frames for Python to verify.
let args = CommandLine.arguments
let vec = try! JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: args[1]))) as! [String: Any]
func str(_ k: String) -> String { vec[k] as! String }
func hexData(_ s: String) -> Data {
    var d = Data()
    var i = s.startIndex
    while i < s.endIndex { let j = s.index(i, offsetBy: 2); d.append(UInt8(s[i..<j], radix: 16)!); i = j }
    return d
}

let c = BridgeCrypto(keyData: Data(base64Encoded: str("key"))!, role: str("test_role"))
precondition(c.room == str("room"), "room id mismatch")
c.useEphemeral(hexData(str("test_priv")))
guard let hello = c.open(str("hello")) else { fatalError("could not open hello") }
let (reply, _) = c.onHello(hello)
guard let s = c.open(str("session_frame")), s["body"] as? String == str("body") else { fatalError("session frame") }
guard let st = c.open(str("static_frame")), st["body"] as? String == str("body") else { fatalError("static frame") }
precondition(c.open(str("session_frame")) == nil, "replay accepted")
let samples = (vec["samples"] as! [NSNumber]).map { Int16($0.intValue) }
let mu = MuLaw.encode(samples)
let out: [String: Any] = [
    "hello": reply!,
    "session_frame": c.seal(["t": "sms", "body": str("body")])!,
    "static_frame": c.seal(["t": "sms", "body": str("body")], useSession: false)!,
    "mulaw": mu.map { String(format: "%02x", $0) }.joined(),
    "decoded": MuLaw.decode(mu).map { Int($0) },
    "ble_uuid": c.bleUUID(day: str("day")).uuidString,
]
try! JSONSerialization.data(withJSONObject: out).write(to: URL(fileURLWithPath: args[2]))
print("Swift side done")
