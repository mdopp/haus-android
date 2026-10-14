# Stage: Verify — mdopp/haus-android

You are the **Verify** sub-agent. Fresh context. There is **no server/box** to
deploy to — this app runs on the user's phone. So "verify" is the strongest
check achievable **headlessly** in this container, plus queuing the on-device
confirmation for the human. Write your verdict to your **own** file
`.claude/state/verify-result.json` (not the shared queue — the builder may be
building concurrently) and return one line.

Read first: `SKILL.md` + `CLAUDE.md`. Env: `export JAVA_HOME=~/.bubblewrap/jdk/jdk-17.0.11+9
ANDROID_HOME=~/.bubblewrap/android_sdk; export PATH=$JAVA_HOME/bin:$PATH`.

## What to verify (on the merged batch SHA)
1. **Compiles + tests:** `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Any failure → `red` with the failing task/log excerpt.
2. **Signed release APK:** build with the env-driven signingConfig and confirm the
   signature: `apksigner verify --print-certs app-release.apk` → the cert SHA-256
   must equal the known fingerprint (memory `signing-key-and-fingerprint`,
   `c36a41…68a62d`). Wrong/missing signature → `red`.
3. **Read-path sanity (optional, when a widget/API change is in the batch):** the
   `/napi/` endpoints are token-only, but the same handlers are reachable on the
   loopback offline path `http://127.0.0.1:8787/api/…` (memory
   `loopback-api-simulation`) — a GET there validates JSON shapes the app parses,
   catching field-name drift without a device. GETs only; never `POST /api/ha/call`.
4. **APK contents:** `aapt dump badging` — right versionCode/name, launcher =
   `OnboardingHomeActivity`, expected widget receivers present.

## Verdict
Write `.claude/state/verify-result.json`:
```json
{ "sha": "<batch merge sha>", "status": "green" | "red",
  "detail": "compile+tests+signature ok" | "<what failed>", "since": "<pass via args>" }
```
- **green** → the code is sound headlessly; the release APK is signed and ready.
  The remaining truth (does it *behave* on the phone?) is the human's
  `device_test[]` — that is expected, not a gap.
- **red** → the orchestrator re-queues the offending unit for the builder.

**On-device behaviour is human-gated** — do not claim a green build is a verified
feature (project CLAUDE.md rule). Your job is: it compiles, it's tested, it's
correctly signed, and the APK is ready for the human to sideload.

Return one line: `verify: <green|red> @ <sha> — <detail>`.
