#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner (an emulator is up). Needs SHIZUKU_APK.
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=android/build/emulator
mkdir -p "$OUT"
adb wait-for-device
# A slow software emulator can show "app is not responding" boxes that hide the Shizuku dialog.
adb shell settings put global hide_error_dialogs 1 || true

echo "== install and start Shizuku with ADB (as a user does)"
adb install -r -g "$SHIZUKU_APK"
APKDIR=$(dirname "$(adb shell pm path moe.shizuku.privileged.api | tr -d '\r' | head -1 | sed 's/^package://')")
LIB=$(adb shell "ls $APKDIR/lib/*/libshizuku.so 2>/dev/null" | tr -d '\r' | head -1)
if [ -n "$LIB" ]; then
  adb shell "$LIB"
else
  adb shell monkey -p moe.shizuku.privileged.api 1 >/dev/null; sleep 6
  adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
fi
for i in $(seq 30); do adb shell ps -A | grep -q shizuku_server && break; sleep 1; done
adb shell ps -A | grep shizuku_server || { echo "Shizuku did not start"; exit 1; }

echo "== emulated incoming call, answered"
adb emu gsm call 5551234; sleep 3; adb emu gsm accept 5551234; sleep 2
adb shell dumpsys telephony.registry | grep -m2 mCallState

echo "== call line tests"
adb logcat -c
gradle -p android connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.phonebridge.android.ScreenshotTest
rc=$?
adb logcat -d > "$OUT/logcat.txt"
echo "== results"
grep -E 'PB-TEST|PB-CallTap|AudioRecord|AudioFlinger.*denied|SecurityException' "$OUT/logcat.txt" | tail -80
adb emu gsm cancel 5551234 || true
exit $rc
