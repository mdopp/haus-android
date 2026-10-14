#!/usr/bin/env bash
# The live widgets on a real Android (an emulator in CI, .github/workflows/live-e2e.yml).
# Needs: adb with one device, the fake Haus (e2e/fake_haus.py) on the host's 8443
# logging to $HAUS_LOG, the debug and androidTest APKs built against e2e/make_ca.sh.
#
#   1 the app starts the service: foreground (specialUse on 34+), OPEN and WATCH
#   2 screen off: CLOSE within 5 s; forced idle for 90 s: no OPEN, no WATCH
#   3 screen on: exactly one OPEN, a WATCH; a pushed frame makes a widget fetch
#   4 the process killed: with the battery exemption a new OPEN within 30 s;
#     without it, no notification of a dead service. An app update starts it.
#   5 a real reboot: OPEN within 60 s of boot
set -uo pipefail
LOG=${HAUS_LOG:-haus.log}
OUT=${E2E_OUT:-e2e-out}
APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
PKG=cloud.dopp.haus
SVC=$PKG/.realtime.LiveService
mkdir -p "$OUT"
failed=()

say() { echo "[$(date +%T)] $*"; }
pass() { say "PASS $1"; }
fail() { say "FAIL $1"; failed+=("$1"); }
on() { adb shell "$@" | tr -d '\r'; }
mark() { wc -l < "$LOG" | tr -d ' '; }
# lines after mark $1 whose event is $2 (the text after the timestamp starts with it)
since() { tail -n +$(( $1 + 1 )) "$LOG" | cut -d' ' -f2- | grep -c "^$2" || true; }
wait_for() { # mark event min seconds
  local i
  for i in $(seq 1 "$4"); do [ "$(since "$1" "$2")" -ge "$3" ] && return 0; sleep 1; done
  return 1
}
fg() { on dumpsys activity services "$SVC" | grep -q "isForeground=true"; }
ours_shown() { on dumpsys notification --noredact | grep -q "pkg=$PKG"; }
dump() {
  on dumpsys activity services "$SVC" > "$OUT/services-$1.txt"
  on dumpsys notification --noredact > "$OUT/notification-$1.txt"
}
wake() { on input keyevent KEYCODE_WAKEUP; on wm dismiss-keyguard >/dev/null 2>&1 || true; }
pid() { on pidof $PKG; }
booted() {
  local i
  for i in $(seq 1 180); do [ "$(on getprop sys.boot_completed)" = 1 ] && return 0; sleep 1; done
  return 1
}

API=$(on getprop ro.build.version.sdk)
say "API $API"
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
on svc power stayon true
on settings put system screen_off_timeout 1800000
on locksettings set-disabled true >/dev/null 2>&1 || true
adb install -r -g "$APK" >/dev/null || { fail "install"; exit 1; }
adb install -r "$TEST_APK" >/dev/null || { fail "install tests"; exit 1; }
on appwidget grantbind --package $PKG --user 0
on am instrument -w -e class cloud.dopp.haus.e2e.LiveE2ESetup $PKG.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/setup.txt"
grep -q "OK (1 test)" "$OUT/setup.txt" || { fail "setup: pair and place widgets"; exit 1; }

# 1 --------------------------------------------------------------------------
wake
m=$(mark)
on am start -W -n $PKG/.ui.HomeActivity >/dev/null
wait_for "$m" OPEN 1 20 && pass "1 the app's start opens the stream" || fail "1 no OPEN after the app's start"
wait_for "$m" WATCH 1 20 && pass "1 the watch set is posted" || fail "1 no WATCH"
on input keyevent KEYCODE_HOME
sleep 2
dump 1
fg && pass "1 the service is in the foreground" || fail "1 not in the foreground"
if [ "$API" -ge 34 ]; then
  grep -qiE "types=(0x)?40000000|foregroundServiceType=(0x)?40000000|specialUse" "$OUT/services-1.txt" \
    && pass "1 type specialUse" || fail "1 not specialUse"
fi
grep -q "live_widgets" "$OUT/notification-1.txt" && pass "1 the notification is on Live-Widgets" || fail "1 no Live-Widgets notification"

# 2 --------------------------------------------------------------------------
m=$(mark)
off=$(date +%s.%N)
on input keyevent KEYCODE_SLEEP
if wait_for "$m" CLOSE 1 15; then
  closed=$(tail -n +$(( m + 1 )) "$LOG" | grep " CLOSE" | head -1 | cut -d' ' -f1)
  took=$(python3 -c "print(round($closed - $off, 1))")
  python3 -c "import sys; sys.exit(0 if $closed - $off <= 5 else 1)" \
    && pass "2 screen off closes the stream within 5 s ($took s)" || fail "2 CLOSE $took s after screen off"
else
  fail "2 no CLOSE within 15 s of screen off"
fi
on dumpsys battery unplug
on dumpsys deviceidle force-idle >/dev/null
m=$(mark)
sleep 90
[ "$(since "$m" OPEN)" -eq 0 ] && [ "$(since "$m" WATCH)" -eq 0 ] \
  && pass "2 90 s idle with the screen off: no OPEN, no WATCH" || fail "2 network with the screen off"
on dumpsys deviceidle unforce >/dev/null
on dumpsys battery reset

# 3 --------------------------------------------------------------------------
m=$(mark)
wake
wait_for "$m" OPEN 1 15 || true
sleep 8
[ "$(since "$m" OPEN)" -eq 1 ] && pass "3 screen on opens exactly one stream" || fail "3 $(since "$m" OPEN) OPENs after screen on"
[ "$(since "$m" WATCH)" -ge 1 ] && pass "3 screen on posts the watch set" || fail "3 no WATCH after screen on"
m=$(mark)
curl -sk -X POST "https://127.0.0.1:8443/ctl/frame?entity_id=light.e2e&state=off" >/dev/null
wait_for "$m" "GET /napi/portal/active" 1 10 && pass "3 a pushed frame makes the active list fetch" || fail "3 no fetch after a frame"

# 4 --------------------------------------------------------------------------
on dumpsys deviceidle whitelist +$PKG >/dev/null
m=$(mark)
on kill -9 "$(pid)"
wait_for "$m" OPEN 1 30 && pass "4 with the exemption: back by itself within 30 s" || fail "4 with the exemption: not back within 30 s"
sleep 2
fg && pass "4 with the exemption: in the foreground again" || fail "4 with the exemption: not in the foreground"
dump 4a

on dumpsys deviceidle whitelist -$PKG >/dev/null
m=$(mark)
on am start -W -n $PKG/.ui.HomeActivity >/dev/null
wait_for "$m" OPEN 1 20 || true
on input keyevent KEYCODE_HOME
sleep 40 # well past the grace Android gives an app that just left the screen
adb logcat -c
m=$(mark)
on kill -9 "$(pid)"
sleep 30
dump 4b
adb logcat -d > "$OUT/logcat-4b.txt"
say "4 without the exemption: $(since "$m" OPEN) OPEN after the kill; refusals in logcat: $(grep -ciE 'startForeground\(\) not allowed|ForegroundServiceStartNotAllowed' "$OUT/logcat-4b.txt")"
if fg; then
  pass "4 without the exemption: running (Android allowed the restart)"
elif ours_shown; then
  fail "4 without the exemption: an orphan notification of a dead service"
else
  pass "4 without the exemption: ended quietly, no orphan notification"
fi

m=$(mark)
adb install -r "$APK" >/dev/null
wait_for "$m" OPEN 1 30 && pass "4 an app update starts it again" || fail "4 not started after an app update"

# 5 --------------------------------------------------------------------------
adb logcat -d > "$OUT/logcat-before-reboot.txt"
m=$(mark)
reboot_once() { adb reboot; timeout 180 adb wait-for-device && booted; }
if reboot_once || { say "reboot: one retry"; reboot_once; }; then
  wake
  wait_for "$m" OPEN 1 60 && pass "5 after a real reboot: OPEN within 60 s" || fail "5 no OPEN within 60 s of boot"
  dump 5
else
  fail "5 the emulator did not come back from the reboot"
fi
adb logcat -d > "$OUT/logcat.txt"
cp "$LOG" "$OUT/" 2>/dev/null

if [ ${#failed[@]} -gt 0 ]; then
  say "${#failed[@]} failed:"; printf '  %s\n' "${failed[@]}"
  exit 1
fi
say "all passed on API $API"
