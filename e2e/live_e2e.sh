#!/usr/bin/env bash
# The live widgets on a real Android (an emulator in CI, .github/workflows/live-e2e.yml).
# Needs: adb with one device, the fake Haus (e2e/fake_haus.py) on the host's 8443
# logging to $HAUS_LOG, the debug and androidTest APKs built against e2e/make_ca.sh.
#
#   1 the app starts the service: foreground (specialUse on 34+), OPEN and WATCH
#   2 screen off: CLOSE within 5 s; forced idle for 90 s: no OPEN, no WATCH
#   3 screen on: exactly one OPEN, a WATCH that says active (an active widget is
#     placed); a pushed frame makes a widget fetch; an active_set other than the
#     list last read makes exactly one read of it, the same set again none
#   4 the process killed: with the battery exemption a new OPEN within 30 s;
#     without it, no notification of a dead service. An app update starts it.
#   5 a real reboot: OPEN within 60 s of boot
#   6 the share sheet: the persona's Direct Share target is in it (#188)
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
# right after an install the widget service may not take the grant yet: up to three tries
for try in 1 2 3; do
  on appwidget grantbind --package $PKG --user 0
  on am instrument -w -e class cloud.dopp.haus.e2e.LiveE2ESetup $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/setup.txt"
  grep -q "OK (1 test)" "$OUT/setup.txt" && break
  say "setup try $try failed: $(grep -m1 AssertionError "$OUT/setup.txt")"
  sleep 5
done
grep -q "OK (1 test)" "$OUT/setup.txt" || { cat "$OUT/setup.txt"; fail "setup: pair and place widgets"; exit 1; }

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
tail -n +$(( m + 1 )) "$LOG" | cut -d' ' -f2- | grep "^WATCH" | grep -q '"active":true' \
  && pass "3 the watch says active: an active widget is placed" || fail "3 the watch does not say active"
m=$(mark)
curl -sk -X POST "https://127.0.0.1:8443/ctl/frame?entity_id=light.e2e&state=off" >/dev/null
wait_for "$m" "GET /napi/portal/active" 1 10 && pass "3 a pushed frame makes the active list fetch" || fail "3 no fetch after a frame"
sleep 5 # the frame's read lands; the list last read is empty
# each push must reach the open stream, or "no read" would prove nothing
pushed() { curl -sk -X POST "https://127.0.0.1:8443/ctl/active_set?ids=$1" | grep -q '"streams": [1-9]'; }
m=$(mark)
pushed light.e2e || fail "3 the first active_set reached no stream"
sleep 10
n=$(since "$m" "GET /napi/portal/active")
[ "$n" -eq 1 ] && pass "3 an active_set that differs: exactly one read" || fail "3 an active_set that differs: $n reads"
m=$(mark)
pushed light.e2e || fail "3 the second active_set reached no stream"
sleep 10
n=$(since "$m" "GET /napi/portal/active")
[ "$n" -eq 0 ] && pass "3 the active_set last read: no read" || fail "3 the active_set last read: $n reads"

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

# 6 --------------------------------------------------------------------------
wake
on dumpsys shortcut > "$OUT/shortcut-6.txt"
grep -q "share_persona" "$OUT/shortcut-6.txt" && pass "6 the share target is published" || fail "6 no share target among the shortcuts"
# a slow emulator may build the app list after the sheet's 1 s wait for direct
# share and drop the answer (API 31): a second sheet comes up warm
seen=no
for sheet in 1 2 3; do
  on am instrument -w -e class cloud.dopp.haus.e2e.LiveE2EShare $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/share.txt"
  for i in $(seq 1 5); do
    sleep 2
    on uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    on cat /sdcard/ui.xml > "$OUT/share-sheet.xml"
    grep -q 'text="Joshua"' "$OUT/share-sheet.xml" && { seen=yes; break; }
  done
  on screencap -p /sdcard/share.png && adb pull /sdcard/share.png "$OUT/share-sheet-$sheet.png" >/dev/null 2>&1
  [ $seen = yes ] && break
  on input keyevent KEYCODE_BACK
  sleep 2
done
[ $seen = yes ] && pass "6 Joshua is in the share sheet (sheet $sheet)" || fail "6 Joshua is not in the share sheet"
on input keyevent KEYCODE_BACK

adb logcat -d > "$OUT/logcat.txt"
cp "$LOG" "$OUT/" 2>/dev/null

if [ ${#failed[@]} -gt 0 ]; then
  say "${#failed[@]} failed:"; printf '  %s\n' "${failed[@]}"
  exit 1
fi
say "all passed on API $API"
