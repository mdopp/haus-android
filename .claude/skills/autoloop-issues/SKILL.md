---
name: autoloop-issues
description: Orchestrates an autonomous issue-resolution pipeline for mdopp/haus-android — Planner → Builder → Verify — coordinated through a shared work queue, spawning each stage as a fresh sub-agent so the loop session stays clean. Adapted from the haus autoloop for a native Android/Gradle app: "verify" is Gradle build + Robolectric tests + a signed release APK (there is no server/box to deploy to — on-device behaviour is human-gated by sideloading the APK). Cross-repo needs become tickets in haus, never built here. Access-widening changes — a new path to a sensitive action, a secret made reachable — leave the batch and open as a DRAFT PR on their own branch for human review. Core state lives in GitHub (autoloop:* labels/issues/PRs); a tiny gitignored cache holds only in-flight run state, brokered by queue.py and rebuildable from GitHub. Use when the user asks to "burn down the android backlog", "work the haus-android issues autonomously", or invokes /loop with this skill.
---

# Autoloop orchestrator — mdopp/haus-android

You are the **coordinator** of an autonomous issue-resolution pipeline for the
**native Android app**. You do **not** write code or groom issues yourself — you
run a tight dispatch loop that **spawns a fresh sub-agent per stage** and routes
coordinate through the `queue.py` state broker: durable state in GitHub (`autoloop:*` labels), a tiny gitignored local cache for in-flight run state.

```
   you (orchestrator) → preflight (queue.py summary) → dispatch ONE stage → re-read → cadence
 PLANNER ──queue.py plan──▶ BUILDER ──claim/built/seal → CI → merge──▶ VERIFY ──▶ release(APK)
 groom/cluster/route          fast gates per issue,          build + Robolectric +
 cross-repo → tickets         8/batch on batch/<id>          signed APK; device = human
```

Each sub-agent starts cold and returns one line, so the long-lived loop stays
small. **Read the project `CLAUDE.md` and user memory before the first iteration
— they override this skill on conflict** (esp. Conventional Commits, "never
commit the keystore", cross-repo=ticket, German replies).

## What haus-android is (and why verify is unusual)
It's a **standalone native Android app** (Kotlin + Gradle, no Bubblewrap) — a
widgets/onboarding shell that pairs to a user's own Haus and talks to
the token-only `/napi/` prefix. It is **not** deployed to a server. So there is
**no real-box `/verify`**: the closest thing to "the box" is the **user's phone**,
and installing the APK there is **human-gated**. The pipeline therefore verifies
what it *can* headlessly — **it compiles, it passes Robolectric tests, it produces
a correctly-signed release APK** — and leaves on-device confirmation to the human
(who sideloads the APK). The in-app crash reporter (`HausApp` →
`last_crash.txt`) turns any device crash into a copyable report, so device bugs
come back as precise traces, not mysteries.

## State — GitHub is the source of truth; `queue.py` brokers a tiny local cache
No stage loads a big JSON blob into context — every stage calls `queue.py` verbs and gets
only the slice it needs. State splits by durability:

**DURABLE / core → GitHub (source of truth).** Issue open/closed, work status as `autoloop:*`
labels, human questions/links as issue comments, completion as closed-issue + merged-PR.
Survives firings, machines, and **concurrent instances** — the `autoloop:building` label is
the **cross-instance claim**, so two instances never grab the same issue. Labels: `queued`
(planned) · `building` (claimed) · `blocked` · `needs-refinement` · `review` (exposure:
solo draft-PR — never auto-merge) · `device-test` (sideload + confirm on the phone) ·
`upstream-wait` (blocked on an unmerged **haus** ticket — cross-repo =
ticket only) · `verify-pending`/`verify-failed` (on the release PR).

**EPHEMERAL run state → `.claude/state/autoloop-cache.json`** (**gitignored**, touched only
via `queue.py`): the in-flight `batch`, this run's unit **plan** (`{id, kind, issues[],
theme, region, scope, acceptance, gate, security, status, pr}`), the `verify` state-machine
(`owed→verifying→green|red`; here "verify" = build + tests + signed APK), and a bounded
`notes` ring. A few KB, never committed, rebuildable from GitHub (`queue.py rebuild`).

`queue.py` enforces caps, pruning, one-way label projection, and the cross-instance claim
**in code**. `security:true` on a unit → its **own branch**, a **draft** PR + `autoloop:review`
label (never auto-merged, never in a batch), the app's pre-merge review path.

## The two gates — decided by effect, never by file (see `stages/planner.md` § the two gates)
The gates were once a list of *places* (keystore, `local.properties`, CI secrets, token
contract). Three of those four are gitignored or live in GitHub settings, so they show up in
**no diff** — the gate could not fire, and across a dozen units it never did, while **#92**
(the only path from which `lock.open` pulls the front door's latch) auto-merged in an
ordinary batch PR. Two gates, two **different** axes; never collapse them into one:

- **Review gate — `security:true` — exposure.** *Does the change widen access to the
  household, or make a secret reachable that was not?* A new path to a sensitive action
  fires it regardless of which file it touches.
- **Human gate — `gate:"verify"` — reversibility.** *If this is wrong, can it simply be
  rolled back?* Data migration, consent-relevant change, anything one-way → a human.

A review-gated unit **never rides in a batch**: a collective PR with eight issues gets a
review of the whole, not of the one place that counts. It gets its own branch and its own
draft PR (`stages/builder.md` Mode C); `queue.py built` keeps it out of `batch.count`.

### `queue.py` verbs — the only way stages touch state
`python3 .claude/skills/autoloop-issues/queue.py <verb>` (`--offline` skips gh; covered by `selftest`):
`summary` (orchestrator peek) · `candidates` / `plan` / `park <issue> <blocked|refinement|review|device-test|upstream-wait>` / `note` (planner) · `next` / `claim` / `built` / `batch new|seal|reset` / `verify-set <sha> <status> [--pr N]` (builder) · `verify-get` / `mirror` / `rebuild` / `lock` (orchestrator).

## Batch economy — the prime directive (ENFORCED)
The expensive tail — full test suite, CI, a signed release build — runs **once
per batch (up to 8 closed issues), never per issue.** Fixes accumulate on ONE
branch `batch/<id>`; it is pushed / PR'd / CI'd / merged **only when it holds 8
closed issues OR the planned queue is empty.** Shipping one issue as its own PR
while planned units remain is a failure of this pipeline. The builder enforces the
per-issue side (fast compile gate, commit to batch, no push); **you** enforce the
batch side: never dispatch a seal step while `batch.count < 8` AND planned units
remain. Build-ahead is allowed; seal-ahead is not.

**The one carve-out: a `security:true` unit rides alone.** It gets its own branch and
its own draft PR immediately (builder Mode C) and does not count toward the 8 — the
review gate is worth more than the saved CI run, and only there.

## Where human attention goes (the whole point)
1. Drain `needs_refinement[]` — sharpen ambiguous issues.
2. Review `review[]` — the **draft** PRs that widen access to the household, each on its own branch (never auto-merge).
3. Work `device_test[]` — sideload the built APK and confirm behaviour on the phone; here is where a one-way change gets caught before it is one-way.
Everything else — grouping, building, compile/test gates, signing — runs without you.

## Step 0 — Preflight (every firing)
1. Read `CLAUDE.md` + user memory (`.claude/projects/-workspace-haus-android/memory/MEMORY.md`). Honour: German replies, Conventional Commits, NEVER commit the keystore, cross-repo=ticket-only.
2. Ensure the toolchain env (per session — see memory `toolchain-not-preprovisioned`): `JAVA_HOME=~/.bubblewrap/jdk/jdk-17.0.11+9`, `ANDROID_HOME=~/.bubblewrap/android_sdk`, `PATH` incl. `~/.npm-global/bin`. `git config --global --add safe.directory /workspace/haus-android`.
3. `queue.py summary` for status (cold start: `queue.py rebuild --release-pr <n>`); fold any `.claude/state/verify-result.json` in with `queue.py verify-set …`; `queue.py mirror` prunes the cache + re-projects labels.
4. Decide the ONE stage to dispatch this firing (below) and spawn it as a fresh Agent. Re-read the queue after it returns.

## Dispatch order (one per firing)
1. If `verify-result.json` exists → fold it in, delete it.
2. If `verify_state.status == "owed"` and a sealed batch exists → dispatch **Verify** (background).
3. If planned units remain and (batch.count < 8) → dispatch **Builder** (build-ahead onto batch; a `security:true` unit branches off alone instead — Mode C — and leaves `batch.count` untouched).
4. If (batch.count ≥ 8 OR no planned units) and batch unsealed and `verify_state` clear → dispatch **Builder seal** (push → CI → merge → set `verify_state=owed`).
5. Else → dispatch **Planner** (groom backlog, fill queue, file cross-repo tickets, park refinements).
6. If nothing to do (queue dry, no batch, backlog groomed) → idle; `/loop` re-fires later.

Stages: `stages/planner.md`, `stages/builder.md`, `stages/verify.md`.
