# autoloop-issues — how to run (mdopp/haus-android)

The **orchestrator** of a multi-agent pipeline for the native Android app. It
spawns a fresh sub-agent per stage (Planner → Builder → Verify), coordinated
through `.claude/state/work-queue.json`, so the loop session stays clean.

Adapted from the haus autoloop. The big difference: haus-android is a
**phone app, not a server** — there's no box to deploy to. "Verify" is therefore
**Gradle build + Robolectric tests + a correctly-signed release APK**, and the
real on-device confirmation is **human-gated** (you sideload the APK). The in-app
crash reporter turns device crashes into copyable traces.

## Self-paced loop (recommended)
```
/loop /autoloop-issues
```
`/loop` re-fires the orchestrator on its own cadence; the work queue persists
progress between firings; each stage runs in its own sub-agent context.

## What each stage does
- **Planner** (`stages/planner.md`) — grooms/clusters open issues into units, routes **server-side needs to haus tickets** (cross-repo = ticket only), bounces underspecified issues to `needs_refinement[]` with a specific question.
- **Builder** (`stages/builder.md`) — implements one unit onto `batch/<id>` with a **fast compile gate**; at the batch boundary (8 issues or empty queue) runs the full test suite + CI, merges, and builds the signed release APK. Signing-key / device-token-contract changes open as a **draft** PR.
- **Verify** (`stages/verify.md`) — headless: compile + Robolectric + signature check (+ optional loopback JSON-shape sanity). Writes its verdict to its own file; queues on-device confirmation for the human.

## Where human attention goes
1. Drain `needs_refinement[]` — sharpen ambiguous issues.
2. Review `review[]` — signing/token-contract **draft** PRs (never auto-merge).
3. Work `device_test[]` — sideload the built APK, confirm behaviour on the phone.
Everything else runs without you.

## Releases
A release = a **signed APK** (sideload) — CI builds + signs + attaches it on a
`v*` tag (`.github/workflows/android-build.yml`), or the builder produces it
locally via the env-driven gradle signingConfig. No server deploy; Play is a
later, human-gated step (issue #3).

## Prereqs
- Toolchain per session (memory `toolchain-not-preprovisioned`): bubblewrap-provided JDK17 + Android SDK under `~/.bubblewrap`, keystore at `/workspace/haus-android/android.keystore` (gitignored).
- `gh` authenticated (with `workflow` scope to touch `.github/workflows/`).
