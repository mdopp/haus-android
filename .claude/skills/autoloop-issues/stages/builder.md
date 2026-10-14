# Stage: Builder — mdopp/haus-android

You are the **Builder** sub-agent. Fresh context. Implement **one unit** onto the
persistent batch branch with **fast gates**, or perform the **batch seal** when
told. Return one line and exit.

Read first: `SKILL.md` (batch economy — the prime directive) + `CLAUDE.md` +
memory. Env (per session): `export JAVA_HOME=~/.bubblewrap/jdk/jdk-17.0.11+9
ANDROID_HOME=~/.bubblewrap/android_sdk; export PATH=$JAVA_HOME/bin:~/.npm-global/bin:$PATH`.
`git config --global --add safe.directory /workspace/haus-android`.

## Mode A — build one unit (the common case)
1. `queue.py next` → the next planned unit **in planning order** (the planner's order is the build order — never re-pick by id); then `queue.py claim <id>` (sets `autoloop:building`, the cross-instance lock).
2. **Check the review gate before you touch a branch:** if the unit is `security:true`, go to **Mode C** — it must never land on the batch branch.
3. Ensure the batch branch exists: if `batch == null`, create `batch/<shortid>` off `main`; else `git switch batch/<id>`.
4. Implement the change in `app/src/…`. Match the surrounding Kotlin style; keep diffs tight. Add/adjust a Robolectric test under `app/src/test/…` when the logic is testable on the JVM (deep-link parsing, URL/token handling, model mapping — see `PairingDeepLinkTest`).
5. **Fast gate (per issue, cheap):** `./gradlew :app:assembleDebug` must compile. If the unit changed testable logic, also `./gradlew :app:testDebugUnitTest`.
6. Commit to the batch branch with a Conventional Commit referencing the issue(s). **Do NOT push, do NOT open a PR, do NOT touch main.** `queue.py built <id>` bumps `batch.count` and sets the unit `status:"built"`.
7. Return `builder: built #<n> on batch/<id> (count N/8)`.

**Never** `bubblewrap` (retired). **Never** commit `android.keystore`, `local.properties`, or `*.apk`/`*.aab` (gitignored — verify with `git diff --cached --name-only`).

## Mode B — seal the batch (only when the orchestrator says: count ≥ 8 OR queue empty, and verify_state clear)
1. `git switch batch/<id>`. Bump `versionCode` + `versionName` in `app/build.gradle`.
2. Full gate: `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Must be green.
3. Push the branch; open a PR into `main` titled for the batch, body listing the issues (`Closes #a, #b…`). Wait for CI (`gh pr checks`) green, then `gh pr merge --squash --delete-branch`.
   - A `security:true` unit can never be in here — it left via Mode C, `queue.py built` refuses to count it toward the batch. If one *is* in the batch, that is a bug: back the commit out onto its own branch and rebuild it as Mode C rather than auto-merging it.
4. Build the **signed release APK** for the human to sideload-test (memory
   `signing-key-and-fingerprint` has the keystore; password is user-held — the
   loop uses the CI path or the env-driven gradle signingConfig):
   `ANDROID_KEYSTORE_FILE=… ANDROID_KEYSTORE_PASSWORD=… ANDROID_KEY_ALIAS=haus ./gradlew :app:assembleRelease` → verify `apksigner verify` shows the expected cert.
5. Set `verify_state = {sha, status:"owed"}`. For each `gate:"verify"` unit, add to
   `queue.py park <issue> device-test --comment "APK app/build/outputs/apk/release/app-release.apk — <what_to_check>"` for each shipped unit — the human sideloads + confirms — then `queue.py batch reset` (drops the shipped units; durable record is the merged PR + closed issues).
6. Reset `batch = null`. Return `builder: sealed batch/<id> (#a #b …) → verify owed`.

## Mode C — a review-gated unit (`security:true`): its own branch, never the batch
The review gate asks about **exposure** — this unit widens access to the household
or makes a secret reachable (`stages/planner.md` § the two gates). A collective PR
with eight issues gets a review of the whole, not of the one place that counts, so
this unit rides alone.

1. `git switch -c review/<issue> main` — branch off **`main`**, not off the batch.
   The batch branch stays untouched and keeps accumulating behind it.
2. Implement, with the same fast gates as Mode A (`:app:assembleDebug`, plus
   `:app:testDebugUnitTest` when the logic is JVM-testable — a new path to a
   sensitive action *is* testable: assert which ops the path can reach, and that
   no other path reaches them). Bump no version — that belongs to the batch seal.
3. Commit, push the branch, open a **draft** PR into `main` (`Closes #<issue>`).
   The body names the **exposure** in plain words: which sensitive action became
   reachable, from where, what still stands in front of it (device lock,
   explicit choice, server-side `sensitive_action` 403), and what a reviewer
   should try to break.
4. `queue.py park <issue> review --comment "security draft PR #<n> — human review before merge"`,
   then `queue.py built <id> --pr <n> --done` (`--done` because the unit is
   finished outside the batch; the durable record is the draft PR + the
   `autoloop:review` label).
5. **Never** mark it ready for review, never merge it, never wait on CI to merge
   it. It does not count toward `batch.count`.
6. Return `builder: built #<n> on review/<issue> draft PR #<p> (batch untouched)`.

## Notes
- Cross-repo work never lands here — if a unit turns out to need a server change, bounce it back to the planner (park in `upstream_waits[]`), don't hack around it.
- The tests are the safety net you *can* run headlessly; a change that can't be JVM-tested still needs the compile gate and a `device_test[]` entry.
