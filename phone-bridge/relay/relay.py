#!/usr/bin/env python3
"""Phone Bridge relay.

Forwards opaque, end-to-end encrypted frames between the Android phone (role "a")
and the iPhone (role "i") that share one room. The relay never sees the key:
it only sees the room id (a hash of the key) and ciphertext.

Frames:
  client -> relay, first frame : {"join": "<32 hex room id>", "role": "a" | "i"}
  client -> relay, next frames : base64 ciphertext (forwarded as-is)
  relay  -> client             : base64 ciphertext, or "#peer:1" / "#peer:0" (other side online?)
"""
import argparse
import asyncio
import json
import logging
import ssl
import string
import time
from collections import deque

import websockets

MAX_FRAME = 4 * 1024 * 1024  # bytes per frame (contacts + SMS batches)
MAX_QUEUE = 500             # queued frames per (room, role) while that side is offline
QUEUE_TTL = 7 * 86400       # seconds
MAX_PEERS_PER_ROLE = 3
MAX_ROOMS = 1000
MAX_PER_IP = 20             # open connections from one address
HEX = set(string.hexdigits.lower())

rooms: dict[str, dict[str, set]] = {}
per_ip: dict[str, int] = {}
queues: dict[tuple[str, str], deque] = {}
log = logging.getLogger("relay")


def other(role: str) -> str:
    return "i" if role == "a" else "a"


async def safe_send(ws, msg: str) -> None:
    try:
        await ws.send(msg)
    except websockets.ConnectionClosed:
        pass


async def announce(room: str) -> None:
    peers = rooms.get(room)
    if not peers:
        return
    for role in ("a", "i"):
        msg = "#peer:1" if peers[other(role)] else "#peer:0"
        for ws in list(peers[role]):
            await safe_send(ws, msg)


def parse_join(raw) -> tuple[str, str] | None:
    try:
        j = json.loads(raw)
        room, role = j["join"], j["role"]
    except (ValueError, KeyError, TypeError):
        return None
    if not isinstance(room, str) or len(room) != 32 or not set(room) <= HEX:
        return None
    if role not in ("a", "i"):
        return None
    return room, role


async def handler(ws) -> None:
    ip = (ws.remote_address or ("?",))[0]
    if per_ip.get(ip, 0) >= MAX_PER_IP:
        await ws.close(1013, "too many connections")
        return
    per_ip[ip] = per_ip.get(ip, 0) + 1
    try:
        await serve_client(ws)
    finally:
        per_ip[ip] -= 1
        if per_ip[ip] <= 0:
            per_ip.pop(ip, None)


async def serve_client(ws) -> None:
    try:
        joined = parse_join(await asyncio.wait_for(ws.recv(), timeout=10))
    except (asyncio.TimeoutError, websockets.ConnectionClosed):
        return
    if joined is None:
        await ws.close(1008, "bad join")
        return
    room, role = joined
    if room not in rooms and len(rooms) >= MAX_ROOMS:
        await ws.close(1013, "full")
        return
    peers = rooms.setdefault(room, {"a": set(), "i": set()})
    if len(peers[role]) >= MAX_PEERS_PER_ROLE:
        await ws.close(1013, "too many peers")
        return
    peers[role].add(ws)
    log.info("join room=%s.. role=%s", room[:6], role)

    # Deliver what was queued while this side was offline.
    q = queues.pop((room, role), None)
    now = time.time()
    while q:
        ts, msg = q.popleft()
        if now - ts < QUEUE_TTL:
            await safe_send(ws, msg)
    await announce(room)

    try:
        async for msg in ws:
            if not isinstance(msg, str) or msg.startswith("#"):
                continue
            targets = peers[other(role)]
            if targets:
                for t in list(targets):
                    await safe_send(t, msg)
            else:
                dq = queues.setdefault((room, other(role)), deque(maxlen=MAX_QUEUE))
                dq.append((time.time(), msg))
    except websockets.ConnectionClosed:
        pass
    finally:
        peers[role].discard(ws)
        log.info("leave room=%s.. role=%s", room[:6], role)
        if not peers["a"] and not peers["i"]:
            rooms.pop(room, None)
        else:
            await announce(room)


async def main() -> None:
    ap = argparse.ArgumentParser(description="Phone Bridge relay")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--cert", help="TLS certificate (enables wss://)")
    ap.add_argument("--key", help="TLS private key")
    args = ap.parse_args()

    ctx = None
    if args.cert:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(args.cert, args.key)

    async with websockets.serve(handler, args.host, args.port, max_size=MAX_FRAME,
                                ping_interval=20, ping_timeout=20, ssl=ctx):
        log.info("listening on %s:%d (%s)", args.host, args.port, "wss" if ctx else "ws")
        await asyncio.Future()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    asyncio.run(main())
