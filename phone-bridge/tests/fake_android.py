"""Fake Android phone for the end-to-end test.

Speaks the real protocol through the real relay with the real encryption, answers the
iPhone's "sync" with sample data, and records the commands the iPhone sends.
Names and numbers are fictional.
"""
import argparse, asyncio, base64, json, math, os, pathlib, sys, time
import websockets
sys.path.insert(0, os.path.dirname(__file__))
from pbcrypto import Crypto, mulaw_encode

ap = argparse.ArgumentParser()
ap.add_argument("--url", required=True)
ap.add_argument("--key", required=True)
ap.add_argument("--out", required=True)
args = ap.parse_args()
KEY = base64.b64decode(args.key)
OUT = pathlib.Path(args.out); OUT.mkdir(parents=True, exist_ok=True)
NOW = time.time(); H = 3600; D = 86400
stats = {"audio_in": 0}

CONTACTS = [
    ("p1", "Hamed Moradi", ["+989121110001"]),
    ("p2", "Sara Karimi", ["09121110002"]),
    ("p3", "Ali Rezaei", ["+989121110003"]),
    ("p4", "مامان", ["+989121110004"]),
    ("p5", "Neda Jafari", ["+989121110005"]),
    ("p6", "Babak Sadeghi", ["+989121110006"]),
    ("p7", "Daniel Brooks", ["+15550100"]),
]
SMS = [  # (id, peer, body, age_seconds, outgoing)
    ("s1", "+989121110004", "سلام عزیزم، شام میای؟", 0.4 * H, False),
    ("s2", "+989121110004", "آره، ساعت ۸ اونجام", 0.3 * H, True),
    ("s3", "+989121110001", "Meeting moved to 10 tomorrow", 3 * H, False),
    ("s4", "+989121110001", "OK, thanks!", 2.9 * H, True),
    ("s5", "+989121110002", "😂😂😂", D + 2 * H, False),
    ("s6", "+9890000259", "تا ۲۰ میلیون تخفیف ویژه با کد BFT20 فقط تا امشب", D + 5 * H, False),
    ("s7", "+989121110005", "عکسا رو فرستادم، ببین", 2 * D, False),
    ("s8", "+989121110005", "دستت درد نکنه 🙏", 2 * D - 600, True),
    ("s9", "+9810000006065", "بیت‌کوین: ۱۷.۳ میلیارد تومن (+۲.۵٪)", 3 * D, False),
]
CALLS = [  # (id, number, type, age, duration)
    ("c1", "+989121110004", "in", 1 * H, 312),
    ("c2", "+989121110003", "missed", 2 * H, 0),
    ("c3", "+989121110003", "missed", 2.2 * H, 0),
    ("c4", "+989121110001", "out", 5 * H, 95),
    ("c5", "+989351234567", "in", D + H, 40),
    ("c6", "+989121110006", "out", D + 3 * H, 0),
    ("c7", "+989121110002", "in", 3 * D, 600),
]
SIMS = [  # (subscription id, slot, name, carrier)
    (1, 0, "Irancell", "Irancell"),
    (2, 1, "Hamrah Aval", "MCI"),
]
SIM_OF = {  # which SIM each sample message and call used
    "s1": 1, "s2": 1, "s3": 2, "s4": 2, "s5": 1, "s6": 1, "s7": 2, "s8": 2, "s9": 1,
    "c1": 1, "c2": 2, "c3": 2, "c4": 2, "c5": 1, "c6": 1, "c7": 2,
}
names = {n: c[1] for c in CONTACTS for n in c[2]}

def batches():
    # Default call line is SIM 1, default message line SIM 2: the iPhone test checks it picks right.
    yield {"t": "sims", "items": [{"id": i, "slot": s, "name": n, "carrier": c, "number": f"+98912000000{s + 1}"}
                                  for i, s, n, c in SIMS], "call": 1, "sms": 2}
    yield {"t": "contacts_batch", "items": [{"id": i, "name": n, "numbers": nums} for i, n, nums in CONTACTS]}
    yield {"t": "calls_batch", "items": [{"id": i, "number": num, "name": names.get(num), "type": t,
                                          "ts": NOW - age, "dur": dur, "sim": SIM_OF.get(i)}
                                         for i, num, t, age, dur in CALLS]}
    yield {"t": "sms_batch", "items": [{"id": i, "peer": p, "name": names.get(p), "body": b, "ts": NOW - age,
                                        "out": o, "sim": SIM_OF.get(i)} for i, p, b, age, o in SMS]}

def log(name, obj):
    (OUT / name).open("a").write(json.dumps(obj, ensure_ascii=False) + "\n")


async def stream_tone(ws, c, seconds=4):
    """Plays a 440 Hz tone to the iPhone, like the caller's voice."""
    t = 0
    for _ in range(int(seconds / 0.04)):
        frame = [int(6000 * math.sin(2 * math.pi * 440 * (t + i) / 16000)) for i in range(640)]
        t += 640
        await ws.send(c.seal({"t": "au", "d": base64.b64encode(mulaw_encode(frame)).decode()}))
        await asyncio.sleep(0.04)


async def main():
    syncs = 0
    ssl_ctx = None
    if args.url.startswith("wss://"):
        import ssl
        ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ssl_ctx.check_hostname = False
        ssl_ctx.verify_mode = ssl.CERT_NONE  # test only; the iPhone app checks the pin
    async for ws in websockets.connect(args.url, max_size=8 << 20, ssl=ssl_ctx):
        c = Crypto(KEY, "a")
        try:
            await ws.send(json.dumps({"join": c.room, "role": "a"}))
            async for frame in ws:
                if frame == "#peer:1":
                    c.new_eph()
                    await ws.send(c.hello(False))
                    continue
                if frame.startswith("#"):
                    print("presence", frame, flush=True)
                    continue
                cmd = c.open(frame)
                if cmd is None:
                    log("rejected.log", {"len": len(frame)})
                    continue
                t = cmd.get("t")
                if t == "hello":
                    reply = c.on_hello(cmd)
                    if reply:
                        await ws.send(reply)
                    log("handshake.log", {"r": cmd.get("r")})
                    continue
                if not cmd["_s"]:
                    log("rejected.log", {"static_command": t})
                    continue
                if t == "au":
                    stats["audio_in"] += 1
                    if stats["audio_in"] in (1, 10, 50):
                        log("audio.log", {"frames_from_iphone": stats["audio_in"]})
                    continue
                print("command", {k: v for k, v in cmd.items() if k != "d"}, flush=True)
                log("commands.log", cmd)
                if t == "sync":
                    syncs += 1
                    for b in batches():
                        await ws.send(c.seal(b))
                    if syncs == 1:  # one live message after the first sync -> shows as unread
                        await asyncio.sleep(1)
                        await ws.send(c.seal({"t": "sms", "id": "live1", "peer": "+989121110002", "name": "Sara Karimi",
                                              "body": "کجایی؟ زنگ بزن", "ts": time.time(), "out": False, "sim": 1}))
                elif t == "send_sms":
                    if cmd.get("body") == "selftest":
                        (OUT / "selftest_ok").write_text("ok")
                    await ws.send(c.seal({"t": "sms_status", "id": cmd.get("id"), "ok": True}))
                elif t == "debug_ring":
                    await ws.send(c.seal({"t": "call", "state": "ringing", "number": "+989121110001",
                                          "name": "Hamed Moradi", "sim": 2}))
                elif t == "answer":
                    await ws.send(c.seal({"t": "call", "state": "active", "number": "+989121110001",
                                          "name": "Hamed Moradi", "sim": 2}))
                    if cmd.get("audio"):
                        await ws.send(c.seal({"t": "audio_start"}))
                        asyncio.ensure_future(stream_tone(ws, c, 30))
                elif t == "forward":
                    await ws.send(c.seal({"t": "forward_status", "mode": cmd.get("mode"), "ok": True}))
        except websockets.ConnectionClosed:
            continue

asyncio.run(main())
