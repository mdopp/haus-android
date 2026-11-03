#!/usr/bin/env bash
# The live widgets on a real Android (an emulator in CI, .github/workflows/live-e2e.yml).
# Needs: adb with one device, the fake Haus (e2e/fake_haus.py) on the host's 8443
# logging to $HAUS_LOG, the debug and androidTest APKs built against e2e/make_ca.sh.
#
#   1 the app starts the service: foreground (specialUse on 34+), OPEN and WATCH
#   2 screen off: CLOSE within 5 s; forced idle for 90 s: no OPEN, no WATCH
#   3 screen on: exactly one OPEN, a WATCH that names the energy widget's sensor
#     and the device widget's device;
#     a pushed frame of that sensor makes the energy widget read again, a frame
#     of anything else reads nothing
#   4 the process killed: with the battery exemption a new OPEN within 30 s;
#     without it, no notification of a dead service. An app update starts it.
#   5 a real reboot: OPEN within 60 s of boot
#   6 the share sheet: the assistant's Direct Share target "Homes" is in it (#188)
#   7 a photo shared from the camera's way: one upload in Haus's shape, „Abgelegt“
#   8 the Haus account (#206): its two switches sync through Android's SyncManager
#     from the fake Haus; the shared contacts resolve by number, the calendars are
#     read only, the reminder rings; „Kontakte übertragen“ sends none of it
#   9 push (#209): a gemeinsam frame on the open stream syncs the calendar part at
#     once, and the new entry is in the phone's calendar
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
tail -n +$(( m + 1 )) "$LOG" | cut -d' ' -f2- | grep "^WATCH" | grep -q 'sensor.e2e_power' \
  && pass "3 the watch names the energy sensor" || fail "3 the watch does not name the energy sensor"
tail -n +$(( m + 1 )) "$LOG" | cut -d' ' -f2- | grep "^WATCH" | grep -q 'light.e2e' \
  && pass "3 the watch names the device widget's device" || fail "3 the watch does not name the device widget's device"
sleep 5 # the screen-on reads land
# each push must reach the open stream, or "no read" would prove nothing
frame() { curl -sk -X POST "https://127.0.0.1:8443/ctl/frame?entity_id=$1&state=$2" | grep -q '"streams": [1-9]'; }
m=$(mark)
frame light.e2e on || fail "3 the light's frame reached no stream"
sleep 12 # past the energy widget's 10 s coalescing
n=$(since "$m" "GET /napi/portal/energy")
[ "$n" -eq 0 ] && pass "3 a frame of no watched sensor: no read" || fail "3 a frame of no watched sensor: $n reads"
m=$(mark)
frame sensor.e2e_power 900 || fail "3 the sensor's frame reached no stream"
wait_for "$m" "GET /napi/portal/energy" 1 15 && pass "3 the sensor's frame reads the energy widget again" || fail "3 no energy read after the sensor's frame"

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
grep -q "share_assistent" "$OUT/shortcut-6.txt" && pass "6 the share target is published" || fail "6 no share target among the shortcuts"
# a slow emulator may build the app list after the sheet's 1 s wait for direct
# share and drop the answer (API 31): a second sheet comes up warm
seen=no
for sheet in 1 2 3; do
  on am instrument -w -e class cloud.dopp.haus.e2e.LiveE2EShare $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/share.txt"
  for i in $(seq 1 5); do
    sleep 2
    on uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    on cat /sdcard/ui.xml > "$OUT/share-sheet.xml"
    grep -q 'text="Homes"' "$OUT/share-sheet.xml" && { seen=yes; break; }
  done
  on screencap -p /sdcard/share.png && adb pull /sdcard/share.png "$OUT/share-sheet-$sheet.png" >/dev/null 2>&1
  [ $seen = yes ] && break
  on input keyevent KEYCODE_BACK
  sleep 2
done
[ $seen = yes ] && pass "6 Homes is in the share sheet (sheet $sheet)" || fail "6 Homes is not in the share sheet"
on input keyevent KEYCODE_BACK

# 7 --------------------------------------------------------------------------
wake
m=$(mark)
on am instrument -w -r -e class cloud.dopp.haus.e2e.LiveE2EPhoto $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/photo.txt"
photo=$(grep -m1 -o 'uri=content://[^ ]*' "$OUT/photo.txt" | cut -d= -f2-)
[ -n "$photo" ] || say "photo setup: $(grep -m1 -E 'Error|Exception' "$OUT/photo.txt")"
sleep 2
on am start -a android.intent.action.SEND -t image/png --eu android.intent.extra.STREAM "$photo" \
  --grant-read-uri-permission -n $PKG/.ui.ShareActivity >/dev/null
wait_for "$m" "UPLOAD" 1 30 && pass "7 the photo reaches Haus's upload" || fail "7 no upload of the shared photo"
[ "$(since "$m" "UPLOAD ok")" -eq 1 ] && [ "$(since "$m" "UPLOAD")" -eq 1 ] \
  && pass "7 exactly one upload, in Haus's shape" || fail "7 uploads: $(since "$m" "UPLOAD ok") ok of $(since "$m" "UPLOAD")"
seen=no
for i in $(seq 1 10); do
  on uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  on cat /sdcard/ui.xml > "$OUT/photo-dialog.xml"
  grep -q 'text="Abgelegt"' "$OUT/photo-dialog.xml" && { seen=yes; break; }
  sleep 2
done
on screencap -p /sdcard/photo.png && adb pull /sdcard/photo.png "$OUT/photo-dialog.png" >/dev/null 2>&1
[ $seen = yes ] && pass "7 the app says „Abgelegt“" || fail "7 no „Abgelegt“ on the screen"
on input keyevent KEYCODE_BACK

# 8 --------------------------------------------------------------------------
m=$(mark)
on am instrument -w -r -e class cloud.dopp.haus.e2e.LiveE2EKonto $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/konto.txt"
grep -q "OK (2 tests)" "$OUT/konto.txt" \
  && pass "8 the Haus account: contacts and calendars on the phone, none in the archive" \
  || { grep -m3 -E 'AssertionError|Exception' "$OUT/konto.txt"; fail "8 the Haus account"; }
[ "$(since "$m" "GEMEINSAM kontakte 304")" -ge 1 ] && pass "8 an unchanged state costs Haus's 304" || fail "8 no 304 for the unchanged contacts"

# 9 --------------------------------------------------------------------------
wake
on am instrument -w -r -e class 'cloud.dopp.haus.e2e.LiveE2EKontoPush#ein' $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/push-ein.txt"
grep -q "OK (1 test)" "$OUT/push-ein.txt" || { grep -m3 -E 'AssertionError|Exception' "$OUT/push-ein.txt"; fail "9 the calendar part on"; }
# the instrumentation ended the app's process: the app's screen, an allowed start, brings the stream back
on am start -W -n $PKG/.ui.HomeActivity >/dev/null
for i in $(seq 1 30); do curl -sk https://127.0.0.1:8443/ctl/streams | grep -q '"streams": [1-9]' && break; sleep 1; done
m=$(mark)
curl -sk "https://127.0.0.1:8443/ctl/gemeinsam?teil=kalender" | grep -q '"streams": [1-9]' && pass "9 the frame reaches the open stream" || fail "9 no open stream for the frame"
wait_for "$m" "GEMEINSAM kalender 200" 1 45 && pass "9 the frame syncs the calendar at once" || fail "9 no sync after the frame"
on am instrument -w -r -e class 'cloud.dopp.haus.e2e.LiveE2EKontoPush#danach' $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/push-danach.txt"
grep -q "OK (1 test)" "$OUT/push-danach.txt" && pass "9 the new entry is in the phone's calendar" \
  || { grep -m3 -E 'AssertionError|Exception' "$OUT/push-danach.txt"; fail "9 the new entry is not on the phone"; }

adb logcat -d > "$OUT/logcat.txt"
cp "$LOG" "$OUT/" 2>/dev/null

if [ ${#failed[@]} -gt 0 ]; then
  say "${#failed[@]} failed:"; printf '  %s\n' "${failed[@]}"
  exit 1
fi
say "all passed on API $API"
