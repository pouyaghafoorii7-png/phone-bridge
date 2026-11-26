"""Relay test: queueing, live forwarding, bad join rejection, presence. Run: python test_relay.py relay.py"""
import asyncio, base64, hashlib, json, os, subprocess, sys
import websockets
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
key = os.urandom(32); room = hashlib.sha256(key).hexdigest()[:32]
def seal(o):
    n = os.urandom(12); return base64.b64encode(n + AESGCM(key).encrypt(n, json.dumps(o).encode(), None)).decode()
def open_(s):
    d = base64.b64decode(s); return json.loads(AESGCM(key).decrypt(d[:12], d[12:], None))
async def main():
    p = subprocess.Popen([sys.executable, sys.argv[1], "--port", "18765"]); await asyncio.sleep(1)
    try:
        u = "ws://127.0.0.1:18765"
        a = await websockets.connect(u); await a.send(json.dumps({"join": room, "role": "a"}))
        assert await a.recv() == "#peer:0"
        await a.send(seal({"t": "sms", "from": "+98912", "body": "سلام"}))  # iPhone offline -> queued
        i = await websockets.connect(u); await i.send(json.dumps({"join": room, "role": "i"}))
        first = await i.recv(); print("queued ->", open_(first))
        assert await i.recv() == "#peer:1"; assert await a.recv() == "#peer:1"
        await i.send(seal({"t": "answer"})); print("live ->", open_(await a.recv()))
        bad = await websockets.connect(u); await bad.send('{"join":"zz","role":"a"}')
        try: await bad.recv(); print("FAIL bad join accepted")
        except websockets.ConnectionClosed as e: print("bad join rejected:", e.rcvd.code)
        await i.close(); print("after iPhone left ->", await a.recv())
        print("ALL OK")
    finally: p.terminate()
asyncio.run(main())
