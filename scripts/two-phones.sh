#!/usr/bin/env bash
# Records docs/two-phones.{mp4,gif}: two emulators, one relay, the README story.
#
# Needs: Docker, an Android SDK with the emulator (ANDROID_SDK_ROOT, default
# ~/Android/Sdk), KVM, two AVDs (AVD_A / AVD_B, default fl-a / fl-b), and the
# APKs and CLI already built (make apk cli). Takes about three minutes.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
AVD_A=${AVD_A:-fl-a}
AVD_B=${AVD_B:-fl-b}
ADB="$SDK/platform-tools/adb"
PORT=8787
WORK=$(mktemp -d)
RELAY=fieldlog-demo-relay
APP=io.github.ahmedhashim.fieldlog

cleanup() {
  docker rm -f "$RELAY" >/dev/null 2>&1 || true
  for s in emulator-5580 emulator-5582; do "$ADB" -s $s emu kill >/dev/null 2>&1 || true; done
}
trap cleanup EXIT

fieldlog() {
  docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/w" -v "$WORK:/work" -w /w eclipse-temurin:17-jdk \
    cli/build/install/fieldlog/bin/fieldlog "$@"
}

echo "== relay, seeded with three holes from strata's example site"
fieldlog import examples/small-site.csv -o /work/seed.ops --as desk --site Riverside
docker run -d --name "$RELAY" --network host -u "$(id -u):$(id -g)" -v "$PWD:/w" -v "$WORK:/work" -w /w \
  eclipse-temurin:17-jdk cli/build/install/fieldlog/bin/fieldlog relay --port $PORT --data /work/relay.ops >/dev/null
for _ in $(seq 30); do curl -sf "http://127.0.0.1:$PORT/" >/dev/null && break; sleep 1; done
docker run --rm --network host -u "$(id -u):$(id -g)" -v "$PWD:/w" -v "$WORK:/work" -w /w eclipse-temurin:17-jdk \
  cli/build/install/fieldlog/bin/fieldlog push /work/seed.ops --relay "http://127.0.0.1:$PORT"

echo "== emulators"
for pair in "$AVD_A:5580" "$AVD_B:5582"; do
  avd=${pair%%:*}; port=${pair##*:}
  "$SDK/emulator/emulator" -avd "$avd" -port "$port" -no-window -no-snapshot -no-audio -no-boot-anim \
    -gpu swiftshader_indirect >"$WORK/$avd.log" 2>&1 &
done
for s in emulator-5580 emulator-5582; do
  "$ADB" -s $s wait-for-device
  until [ "$("$ADB" -s $s shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" -s $s shell settings put global $k 0
  done
  "$ADB" -s $s uninstall $APP >/dev/null 2>&1 || true
  "$ADB" -s $s uninstall $APP.test >/dev/null 2>&1 || true
  "$ADB" -s $s install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
  "$ADB" -s $s install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
  "$ADB" -s $s shell input keyevent KEYCODE_WAKEUP
  "$ADB" -s $s logcat -c
done

# A cold-booted emulator is busy for a while; let it settle, then clear any "isn't responding" dialog.
sleep 60
for s in emulator-5580 emulator-5582; do
  "$ADB" -s $s shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null
done

echo "== recording"
run() {
  "$ADB" -s "$1" shell am instrument -w -e role "$2" -e class fieldlog.app.DemoScript \
    $APP.test/androidx.test.runner.AndroidJUnitRunner >"$WORK/$2.txt"
}
marker() {  # wait until both phones have logged "$1"
  for _ in $(seq 240); do
    n=0
    for s in emulator-5580 emulator-5582; do "$ADB" -s $s logcat -d -s fieldlog-demo:I | grep -q "$1" && n=$((n + 1)); done
    [ $n = 2 ] && return 0
    sleep 0.5
  done
  echo "timed out waiting for $1"; return 1
}
run emulator-5580 pixel-a & A=$!
run emulator-5582 pixel-b & B=$!
# Start recording once both have the site from the relay.
marker READY
for s in emulator-5580 emulator-5582; do
  "$ADB" -s $s shell rm -f /sdcard/demo.mp4
  "$ADB" -s $s shell screenrecord --bit-rate 6000000 /sdcard/demo.mp4 &
done
# Stop recording when both scripts reach their last line, before the app closes.
marker END
sleep 2
for s in emulator-5580 emulator-5582; do "$ADB" -s $s shell pkill -INT screenrecord || true; done
wait $A $B || true
sleep 3
cat "$WORK/pixel-a.txt" "$WORK/pixel-b.txt"
grep -q '^OK (1 test)' "$WORK/pixel-a.txt" && grep -q '^OK (1 test)' "$WORK/pixel-b.txt" || { echo "a phone's script failed"; exit 1; }
"$ADB" -s emulator-5580 pull /sdcard/demo.mp4 "$WORK/a.mp4" >/dev/null
"$ADB" -s emulator-5582 pull /sdcard/demo.mp4 "$WORK/b.mp4" >/dev/null

echo "== composing docs/two-phones.{mp4,gif}"
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$WORK:/work" -v "$PWD/docs:/docs" -v "$PWD/scripts:/scripts" \
  --entrypoint bash ghcr.io/charmbracelet/vhs:v0.10.0 /scripts/compose.sh
echo "relay holds $(curl -s "http://127.0.0.1:$PORT/")"
