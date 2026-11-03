#!/usr/bin/env sh
# The detekt ratchet (CLAUDE.md "Code gates"). detekt's own baseline lets a
# listed finding pass but never says when one is gone, so this script runs
# `detektBaseline` and compares today's findings with app/detekt-baseline.xml,
# counted per rule and file (an entry's id carries the function's signature,
# so a renamed function moves its entry without being a new finding):
#   more in a rule and file  -> fail, the committed baseline stays as it was;
#   fewer                    -> fail until the shrunk baseline, which this
#                               run has written, is committed in the same PR;
#   the same, ids moved      -> pass, the rewritten baseline may be committed.
set -e
cd "$(dirname "$0")/.."
base=app/detekt-baseline.xml
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cp "$base" "$tmp/committed.xml"
./gradlew -q :app:detektBaseline
# "Rule:File.kt" per entry, counted
count() { grep -o '<ID>[^$<]*' "$1" | sed 's/<ID>//' | sort | uniq -c | awk '{print $2, $1}' | sort; }
count "$tmp/committed.xml" > "$tmp/old"
count "$base" > "$tmp/now"
join -a1 -a2 -e0 -o 0,1.2,2.2 "$tmp/old" "$tmp/now" > "$tmp/both"
grew=$(awk '$3 > $2 {printf "  %s: %d, baseline %d\n", $1, $3, $2}' "$tmp/both")
shrank=$(awk '$3 < $2 {printf "  %s: %d, baseline %d\n", $1, $3, $2}' "$tmp/both")
total() { grep -c '<ID>' "$1" || true; }
echo "detekt: $(total "$base") findings, baseline $(total "$tmp/committed.xml")"
if [ -n "$grew" ]; then
  echo "error: new detekt findings — fix them (./gradlew :app:detekt shows where and why):"
  echo "$grew"
  ids() { grep -o '<ID>[^<]*' "$1" | sed 's/<ID>//' | sort; }
  ids "$tmp/committed.xml" > "$tmp/oldids"
  ids "$base" | comm -13 "$tmp/oldids" - | sed 's/^/  new: /'
  cp "$tmp/committed.xml" "$base"
  exit 1
fi
if [ -n "$shrank" ]; then
  echo "error: fewer findings than the baseline lists — good; commit the shrunk $base"
  echo "(this run has written it) in this PR:"
  echo "$shrank"
  exit 1
fi
exit 0
