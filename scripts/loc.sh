#!/usr/bin/env sh
# One line budget per package of the app's production code (CLAUDE.md "Code
# gates"), the idea of laib's scripts/loc.sh: production lines are capped,
# test lines are counted but not capped. Lines are `wc -l` of the .kt files,
# comments and blank lines included.
#
# The caps were set at each package's size on 2026-11-03 plus 5 %. Crossing
# one means raising it in the same PR, with its reason next to the number and
# the owner's approval — or making room in the package first (the proposals
# in the PR that brought this script). The caps are tight on purpose: each
# package is at 95 % or more from the start. A new package needs its own
# line here, else CI fails.
set -e
cd "$(dirname "$0")/.."
src=app/src/main/java/cloud/dopp/homes
bad=0
kt() { find "$@" -name '*.kt' -exec cat {} + 2>/dev/null | wc -l; }
budget() { # budget <package> <cap>  — "." is the files beside the packages
  name=$1 cap=$2
  if [ "$name" = . ]; then prod=$(find $src -maxdepth 1 -name '*.kt' -exec cat {} + 2>/dev/null | wc -l)
  else prod=$(kt "$src/$name"); fi
  echo "$name: $prod of $cap"
  [ "$prod" -le "$cap" ] || { echo "error: $name over its cap of $cap lines (scripts/loc.sh)"; bad=1; }
  known="$known $name"
}
known=""
# package    cap     size on 2026-11-03, and what it is
budget widget    4938  # 4,703: the home-screen widgets, their rendering and pinning
budget sicherung 3183  # 3,031: Daten sichern — backups, contacts, SMS and calls, age
budget ui        3220  # 3,026: the activities: start screen, pairing, settings screens;
                       # 3177 -> 3200 (owner-approved, homes ADR 0001 plan): the traces and „Hier anfordern“ on the start screen, homes-android#231;
                       # 3200 -> 3220: a trace says what sharing to it would do, homes-android#232
budget data      2940  # 2,780: Homes' calls (Napi), clients, models, the token store;
                       # 2919 -> 2940 (owner-approved, homes ADR 0001 plan): /napi/kann and its client, homes-android#231
budget konto     1304  # 1,242: das Homes-Konto, the sync adapters for contacts and calendars
budget standort  1098  # 1,046: Standortverlauf — recording, batching, import
budget kann      420  #   340 on 2026-11-04: what the box can do, /napi/kann kept and read, „Hier anfordern“ (homes ADR 0001);
                       # 360 -> 420 (owner-approved, homes ADR 0001 plan): the share targets switched by teilen, homes-android#232
budget realtime   667  #   635: the live stream and its foreground service
budget .           35  #    33: HomesConfig.kt
for d in "$src"/*/; do
  p=$(basename "$d")
  case " $known " in *" $p "*) ;; *) echo "error: package $p has no cap in scripts/loc.sh"; bad=1 ;; esac
done
echo "total production: $(kt $src)  tests: $(kt app/src/test app/src/androidTest)"
exit $bad
