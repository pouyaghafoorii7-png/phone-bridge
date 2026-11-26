# Phone Bridge protocol v2

Roles: `a` = Android, `i` = iPhone. `K` = 32-byte pairing key from the QR code.

## Keys
- room id: `hex(SHA256(K))[:32]` (the only thing the relay learns)
- static key: `HKDF-SHA256(ikm=K, salt="", info="pb2 static", 32)`
- session key (forward secret, new on every connection):
  each side makes an ephemeral X25519 key and sends `hello`;
  `HKDF-SHA256(ikm=X25519(mine, peer), salt=K, info="pb2 session" || min(pubA,pubB) || max(pubA,pubB), 32)`

## Frame
`base64( 0x02 | kind | nonce[12] | AES-256-GCM(ciphertext || tag[16]) )`
kind 0 = static key, kind 1 = session key. AAD = the first two bytes.

Plaintext is JSON with added fields: `from` (sender role), `ts` (unix seconds),
`n` (session frames: counter from 1) or `mid` (static frames: random id).

## Receiver rules
- drop frames whose `from` is the receiver's own role (reflection)
- session frames: `n` must grow; a new handshake resets the counter
- static frames: `ts` within 7 days and `mid` not seen before
- Android runs commands only from session frames; static frames carry only `hello`
  and data events queued while the iPhone was offline

## Handshake
On `#peer:1` from the relay each side makes a new ephemeral key and sends
`{"t":"hello","e":pub,"r":false}`. On a hello, derive the session key and,
if `r` is false, answer `{"t":"hello","e":pub,"r":true}`.

## Call audio
`{"t":"dtmf","digit":"5"}` iPhone → Android: play one keypad tone into the live call.

`{"t":"au","d":base64}` session frames, 16 kHz mono G.711 mu-law, 640 samples (40 ms).

Android to iPhone, about the call sound (no reply needed):
- `{"t":"audio_start"}`: sound frames start.
- `{"t":"audio_error","why":"accessibility"}`: no Shizuku call line and no accessibility service.
- `{"t":"audio_info","kind":K,"detail":D}`:
  - `route`: `direct` (the iPhone voice goes into the call line), `speaker` or `cable`.
  - `source`: what records the caller, after the first seconds: `call line (<method>)` (Shizuku shell
    service; methods `redirect`, `telephony`, `samsung-tx`, `qcom-incall`, `downlink`), or a microphone
    source name.
  - `direct`: why the call line is not used: `missing` (Shizuku not running), `denied`, `starting`,
    `silent` (the phone gives zeros), or the error from the shell service.
  - `problem`: `mic` (nothing could open) or `silent` (all sources gave silence).

## Dual SIM (lines)
The Android phone tells the iPhone about its SIM cards; every call and message then names its SIM.
All fields are optional: a missing `sim` means "unknown or single SIM", so old and new versions work together.

Android to iPhone:
- `{"t":"sims","items":[{"id":3,"slot":0,"name":"Irancell","carrier":"Irancell","number":"+98912..."}],"call":3,"sms":4}`
  The active SIMs in slot order. `id` is Android's subscription id. `call` and `sms` are the phone's
  default voice and SMS SIM (`null` = it asks every time). Sent on `sync`, on `sims_sync`, and when a SIM changes.
- `sim` (subscription id) is added to `sms`, `call`, and the items of `sms_batch` and `calls_batch`.

iPhone to Android:
- `{"t":"sims_sync"}`: send the SIM list again.
- `dial`, `send_sms` and `forward` accept `"sim": <id>`. Without it the phone's default SIM is used.
