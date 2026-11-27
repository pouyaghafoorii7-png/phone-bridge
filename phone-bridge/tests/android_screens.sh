#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner on a phone-sized emulator (pixel_6, 411 dp wide).
# Takes the screenshots of the app (ScreenshotTest) and collects them. Never fails the build.
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=android/build/screens
mkdir -p "$OUT"
adb wait-for-device
# A slow software emulator can show "app is not responding" boxes over the pictures.
adb shell settings put global hide_error_dialogs 1 || true
adb logcat -c
gradle -p android connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.phonebridge.android.ScreenshotTest \
  || echo "screenshot run failed (this does not fail the build)"
adb pull /data/local/tmp/ghafoorii-shots/. "$OUT/" || true
adb logcat -d > "$OUT/logcat.txt" || true
ls -la "$OUT" || true
adb logcat -d | grep -E 'PB-TEST' | tail -30 || true
exit 0
