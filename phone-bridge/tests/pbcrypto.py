"""Reference implementation of protocol v2 (see docs/PROTOCOL.md). Used by the tests."""
import base64, hashlib, json, os, time
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

b64 = lambda b: base64.b64encode(b).decode()


def hkdf(ikm, salt, info):
    return HKDF(hashes.SHA256(), 32, salt or None, info).derive(ikm)


def raw_pub(priv):
    return priv.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


class Crypto:
    def __init__(self, key, role):
        self.k, self.role = key, role
        self.room = hashlib.sha256(key).hexdigest()[:32]
        self.static = hkdf(key, b"", b"pb2 static")
        self.priv = None
        self.session = None
        self.send_n = self.recv_n = 0
        self.seen = set()

    def new_eph(self, priv_bytes=None):
        self.priv = X25519PrivateKey.from_private_bytes(priv_bytes) if priv_bytes else X25519PrivateKey.generate()
        self.session = None

    def hello(self, reply=False):
        if self.priv is None:
            self.new_eph()
        return self.seal({"t": "hello", "e": b64(raw_pub(self.priv)), "r": reply}, session=False)

    def on_hello(self, msg):
        if self.priv is None:
            self.new_eph()
        peer, mine = base64.b64decode(msg["e"]), raw_pub(self.priv)
        shared = self.priv.exchange(X25519PublicKey.from_public_bytes(peer))
        lo, hi = sorted([mine, peer])
        self.session = hkdf(shared, self.k, b"pb2 session" + lo + hi)
        self.send_n = self.recv_n = 0
        return None if msg.get("r") else self.hello(True)

    def seal(self, obj, session=None):
        if session is None:
            session = self.session is not None
        o = dict(obj, **{"from": self.role, "ts": time.time()})
        if session:
            self.send_n += 1
            o["n"], key, kind = self.send_n, self.session, 1
        else:
            o["mid"], key, kind = os.urandom(8).hex(), self.static, 0
        hdr, nonce = bytes([2, kind]), os.urandom(12)
        return b64(hdr + nonce + AESGCM(key).encrypt(nonce, json.dumps(o, ensure_ascii=False).encode(), hdr))

    def open(self, text):
        try:
            d = base64.b64decode(text)
        except Exception:
            return None
        if len(d) < 30 or d[0] != 2 or d[1] not in (0, 1):
            return None
        key = self.session if d[1] == 1 else self.static
        if key is None:
            return None
        try:
            o = json.loads(AESGCM(key).decrypt(d[2:14], d[14:], d[:2]))
        except Exception:
            return None
        if o.get("from") == self.role:
            return None
        if d[1] == 1:
            if o.get("n", 0) <= self.recv_n:
                return None
            self.recv_n = o["n"]
        else:
            if abs(time.time() - o.get("ts", 0)) > 7 * 86400 or o.get("mid") in self.seen:
                return None
            self.seen.add(o.get("mid"))
        o["_s"] = d[1] == 1
        return o


def mulaw_encode(samples):
    out = bytearray()
    for s in samples:
        sign = 0x80 if s < 0 else 0
        s = min(abs(int(s)), 32635) + 0x84
        exp, mask = 7, 0x4000
        while not (s & mask) and exp > 0:
            exp -= 1
            mask >>= 1
        out.append(~(sign | (exp << 4) | ((s >> (exp + 3)) & 0x0F)) & 0xFF)
    return bytes(out)


def mulaw_decode(data):
    out = []
    for u in data:
        u = ~u & 0xFF
        s = (((u & 0x0F) << 3) + 0x84) << ((u >> 4) & 7)
        s -= 0x84
        out.append(-s if u & 0x80 else s)
    return out


def ble_uuid(key, day):
    """Bluetooth service UUID for one UTC day ("YYYY-MM-DD"); changes daily so the phone cannot be tracked."""
    b = hkdf(key, b"", b"pb2 ble " + day.encode())[:16]
    h = b.hex()
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}".upper()
