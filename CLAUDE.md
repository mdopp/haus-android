# haus-android — house rules

This repo is the **Haus app**: native Kotlin home-screen widgets and the
pairing with **Haus** (`https://haus.<domain>`, repo `mdopp/haus`), the
family's surface of the box Laib runs. It is a widget client and nothing more
(laib ADR 0022): no web shell, no messages, no approvals, no diagnostics,
and no network while the screen is off. The widgets stay live through one
`specialUse` foreground service (`realtime/LiveService.kt`) that holds the
stream only while the screen is on. The seam with Haus is
`docs/CONTRACT.md`.

These rules apply to every session, human or agent.

## Haus is the contract

- The app calls only what Haus serves. Every call is data in `data/Napi.kt`
  (method, path, query, body fields, reply keys), `data/NapiRequest.kt` alone
  builds requests from it, and `NapiContractTest` holds it to a copy of Haus's
  handlers, file and line. A call the app needs that Haus lacks is a Haus
  issue first, never a guess here.
- One set of names: package `cloud.dopp.haus`, scheme `cloud.dopp.haus://`,
  token prefix `haus_device_`. `NamesTest` fails on any older name.
- Messages to people go through their chat channel (laib ADR 0022 §3), not the
  app. The live service's quiet notification is the only one; do not add
  another, a second service, a poll (widget update periods stay 0), a job, an
  alarm, a wakelock or a rescue worker. Recovery after a killed process rests
  on the battery-optimization exemption the person grants. `ManifestTest`
  holds this; `e2e/` proves it on emulators (`.github/workflows/live-e2e.yml`).

## Commits

- **Conventional Commits**: `type(scope): subject` — `feat`/`fix`/`refactor`/
  `chore`/`docs`/`test`. Scope mirrors the area: `feat(widget):`,
  `feat(pairing):`, `chore(ci):`, `docs:`.
- **No parentheses in the subject** beyond the conventional `(scope)`.
- In KDoc, never write `/*` (for example in a path glob): Kotlin nests block
  comments and the file stops compiling.

## Never commit secrets

- **NEVER** commit signing keystores or Play credentials (`*.jks`, `*.keystore`,
  `*.keystore.base64`, `local.properties`, any Play service-account JSON). They
  stay local and reach CI as **secrets** (`ANDROID_KEYSTORE_BASE64`,
  `ANDROID_KEYSTORE_PASSWORD`; key alias `haus`).
- A new package id is a new app: its signing key is the owner's decision
  (laib#30). Debug and CI builds need no key.

## Releases

- Play release = a **signed AAB** uploaded via **Play Console**, human-gated.
- The debug build in CI only proves the project compiles and its tests pass.

## Verify

- There is no local Android SDK here: **CI is the proof** for build and unit
  tests (Robolectric).
- A feature is verified on a **device or emulator** (install the APK, pair,
  place the widgets, watch them update while the screen is on). If you cannot
  run one, say so — a green build is not a verified feature.
