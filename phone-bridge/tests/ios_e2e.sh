#!/usr/bin/env bash
# macOS CI: build the IPA, cross-check crypto, then run the real iOS app in the Simulator
# against the real relay and a fake Android phone, and take screenshots.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
SHOTS=$ROOT/ios/screenshots
mkdir -p "$SHOTS"

# Newest Xcode on the runner (Liquid Glass needs the iOS 26 SDK).
X=$(ls -d /Applications/Xcode_26*.app 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$X" ]; then sudo xcode-select -s "$X"; fi
xcodebuild -version

python3 -m venv /tmp/venv && . /tmp/venv/bin/activate
pip install -q -r relay/requirements.txt cryptography

echo "== crypto cross-check (Swift <-> Python reference)"
python3 tests/crypto_vector.py make i > /tmp/vec.json
swiftc -O ios/Bridge/Crypto.swift tests/CryptoCheck/main.swift -o /tmp/cryptocheck
/tmp/cryptocheck /tmp/vec.json /tmp/reply.json
python3 tests/crypto_vector.py verify /tmp/vec.json /tmp/reply.json

echo "== build device IPA (unsigned) and simulator app"
cd ios
brew list xcodegen >/dev/null 2>&1 || brew install xcodegen
xcodegen generate
xcodebuild -project PhoneBridge.xcodeproj -target PhoneBridge -configuration Release -sdk iphoneos \
  SYMROOT="$PWD/build" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" build -quiet
rm -rf Payload && mkdir Payload && cp -R build/Release-iphoneos/PhoneBridge.app Payload/
zip -qry PhoneBridge-unsigned.ipa Payload
xcodebuild -project PhoneBridge.xcodeproj -target PhoneBridge -configuration Debug -sdk iphonesimulator \
  SYMROOT="$PWD/build-sim" ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO build -quiet
APP=$PWD/build-sim/Debug-iphonesimulator/PhoneBridge.app
cd "$ROOT"

echo "== end-to-end over TLS with a pinned certificate: relay + fake Android + iOS app"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 30 -subj "/CN=phone-bridge" \
  -keyout /tmp/key.pem -out /tmp/cert.pem 2>/dev/null
PIN=$(openssl x509 -in /tmp/cert.pem -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | tr 'A-F' 'a-f')
python3 relay/relay.py --port 8765 --cert /tmp/cert.pem --key /tmp/key.pem > /tmp/relay.log 2>&1 &
KEY=$(python3 -c "import os,base64;print(base64.b64encode(os.urandom(32)).decode())")
python3 tests/fake_android.py --url wss://127.0.0.1:8765 --key "$KEY" --out /tmp/fa > /tmp/fa.log 2>&1 &
PAIR=$(python3 -c "import json,sys;print(json.dumps({'u':'wss://127.0.0.1:8765','k':sys.argv[1],'p':sys.argv[2]}).encode().hex())" "$KEY" "$PIN")

UDID=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
devs = json.load(sys.stdin)["devices"]
ver = lambda r: [int(x) for x in r.rsplit("iOS-", 1)[-1].split("-") if x.isdigit()]
ios = sorted(((r, d) for r, l in devs.items() if "iOS" in r for d in l if d["name"].startswith("iPhone")), key=lambda x: (ver(x[0]), x[1]["name"]))
pro = [x for x in ios if x[1]["name"].endswith("Pro")] or ios
print(pro[-1][1]["udid"]); print(pro[-1][0], pro[-1][1]["name"], file=sys.stderr)')
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl status_bar "$UDID" override --time "9:41" --batteryState charged --batteryLevel 100 --cellularBars 4 || true
xcrun simctl install "$UDID" "$APP"
xcrun simctl privacy "$UDID" grant microphone com.phonebridge.ios || true

shot() {
  local name=$1; shift
  xcrun simctl terminate "$UDID" com.phonebridge.ios 2>/dev/null || true
  xcrun simctl launch "$UDID" com.phonebridge.ios -pairHex "$PAIR" "$@" >/dev/null
  sleep 7
  xcrun simctl io "$UDID" screenshot "$SHOTS/$name.png" >/dev/null
  echo "shot $name"
}

shot 1-messages -tab messages
shot 2-chat -tab messages -open 9121110004
shot 3-calls -tab recents
shot 4-contacts -tab contacts
shot 4b-search -tab search
shot 5-keypad -tab keypad
shot 6-incoming-call -tab keypad -ring YES
shot 7-settings -tab settings
shot 7b-active-call -tab keypad -ring YES -autoAnswer YES
xcrun simctl ui "$UDID" appearance dark
shot 8-messages-dark -tab messages
shot 9-chat-dark -tab messages -open 9121110002

echo "== checks"
head -40 /tmp/fa.log; tail -20 /tmp/relay.log
test -f /tmp/fa/selftest_ok || { echo "FAIL: iPhone -> Android command did not arrive"; exit 1; }
test -s /tmp/fa/handshake.log || { echo "FAIL: no forward-secret handshake"; exit 1; }
if [ -s /tmp/fa/rejected.log ]; then echo "rejected frames:"; cat /tmp/fa/rejected.log; fi
grep -q '"audio": true' /tmp/fa/commands.log && echo "iPhone asked for internet call audio: OK"
if [ -s /tmp/fa/audio.log ]; then echo "audio frames from iPhone mic:"; cat /tmp/fa/audio.log; else echo "note: no mic frames (CI machine has no microphone)"; fi
echo "== pin check: a wrong pin must be refused"
BADKEY=$(python3 -c "import os,base64;print(base64.b64encode(os.urandom(32)).decode())")
BADROOM=$(python3 -c "import base64,hashlib,sys;print(hashlib.sha256(base64.b64decode(sys.argv[1])).hexdigest()[:6])" "$BADKEY")
BAD=$(python3 -c "import json,sys;print(json.dumps({'u':'wss://127.0.0.1:8765','k':sys.argv[1],'p':'00'*32}).encode().hex())" "$BADKEY")
xcrun simctl terminate "$UDID" com.phonebridge.ios 2>/dev/null || true
xcrun simctl launch "$UDID" com.phonebridge.ios -pairHex "$BAD" -tab settings >/dev/null
sleep 6
xcrun simctl io "$UDID" screenshot "$SHOTS/10-wrong-pin-refused.png" >/dev/null
if grep -q "room=$BADROOM" /tmp/relay.log; then echo "FAIL: app connected despite a wrong certificate pin"; exit 1; fi
echo "wrong pin refused: OK"
grep -q '"t": "sync"' /tmp/fa/commands.log || { echo "FAIL: no sync"; exit 1; }
echo "== dual SIM: a message and a call pick the right line"
xcrun simctl terminate "$UDID" com.phonebridge.ios 2>/dev/null || true
xcrun simctl launch "$UDID" com.phonebridge.ios -pairHex "$PAIR" -tab keypad -simTest YES >/dev/null
sleep 9
python3 - <<'PY' || { echo "FAIL: dual SIM line choice"; exit 1; }
import json
cmds = [json.loads(l) for l in open("/tmp/fa/commands.log")]
sms = [c for c in cmds if c.get("t") == "send_sms" and c.get("body") == "simtest"]
dial = [c for c in cmds if c.get("t") == "dial" and c.get("number") == "+989121110001"]
assert sms, "no message sent"
assert sms[-1].get("sim") == 2, f"a new message must use the default message line (2): {sms[-1]}"
assert dial, "no call placed"
assert dial[-1].get("sim") == 2, f"a call must use the line of the last call with that number (2), not the default (1): {dial[-1]}"
print("dual SIM OK: message on line", sms[-1]["sim"], ", call on line", dial[-1]["sim"])
PY
echo "E2E OK: iPhone app paired, synced and sent an encrypted command through the relay"
