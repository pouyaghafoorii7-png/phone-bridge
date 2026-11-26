"""Cross-language test vectors for protocol v2.

  make ROLE_UNDER_TEST > vec.json     the other role is played by this Python reference
  verify vec.json reply.json          checks what the Swift / Kotlin code produced
"""
import base64, json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from pbcrypto import Crypto, X25519PrivateKey, raw_pub, b64, mulaw_encode, mulaw_decode, ble_uuid

BODY = "سلام 👋 test ✓"
SAMPLES = [0, 1, -1, 100, -100, 1000, -1000, 8000, -8000, 32767, -32768, 12345, -23456]


def make(test_role):
    peer_role = "i" if test_role == "a" else "a"
    key, peer_priv, test_priv = os.urandom(32), os.urandom(32), os.urandom(32)
    peer = Crypto(key, peer_role)
    peer.new_eph(peer_priv)
    hello = peer.hello(False)
    peer.on_hello({"e": b64(raw_pub(X25519PrivateKey.from_private_bytes(test_priv))), "r": True})
    return {
        "key": b64(key), "test_role": test_role, "peer_role": peer_role, "room": peer.room,
        "peer_priv": peer_priv.hex(), "test_priv": test_priv.hex(), "body": BODY,
        "hello": hello,
        "session_frame": peer.seal({"t": "sms", "body": BODY}),
        "static_frame": peer.seal({"t": "sms", "body": BODY}, session=False),
        "samples": SAMPLES, "mulaw": mulaw_encode(SAMPLES).hex(),
        "decoded": mulaw_decode(mulaw_encode(SAMPLES)),
        "day": "2026-11-25", "ble_uuid": ble_uuid(key, "2026-11-25"),
    }


def verify(vec, reply):
    peer = Crypto(base64.b64decode(vec["key"]), vec["peer_role"])
    peer.new_eph(bytes.fromhex(vec["peer_priv"]))
    h = peer.open(reply["hello"])
    assert h and h["t"] == "hello" and h["r"] is True and not h["_s"], h
    peer.on_hello(h)
    s = peer.open(reply["session_frame"])
    assert s and s["_s"] and s["body"] == BODY and s["n"] == 1, s
    st = peer.open(reply["static_frame"])
    assert st and not st["_s"] and st["body"] == BODY, st
    assert peer.open(reply["session_frame"]) is None, "replay was accepted"
    assert reply["mulaw"] == vec["mulaw"], "mu-law encode differs"
    assert reply["decoded"] == vec["decoded"], "mu-law decode differs"
    assert reply["ble_uuid"].upper() == vec["ble_uuid"], ("Bluetooth UUID differs", reply["ble_uuid"], vec["ble_uuid"])
    print("crypto cross-check OK:", vec["test_role"], "<-> Python reference")


if sys.argv[1] == "make":
    print(json.dumps(make(sys.argv[2])))
else:
    verify(json.load(open(sys.argv[2])), json.load(open(sys.argv[3])))
