# haus-android — house rules

This repo is the **Haus app**: native Kotlin home-screen widgets, the
pairing with **Haus** (`https://haus.<domain>`, repo `mdopp/haus`), the
family's surface of the box Laib runs, and on the app's start screen the
person's favorite devices (the owner's calls, 2026-11-02 and 2026-11-03: the
widgets are one device per widget, one energy widget and the tools Dokumente
and Notizen; no Box widgets; every confirmation is the one dialog,
`widget/ActionDialog.kt`). It is a widget client (laib ADR 0022):
no web shell, no messages, no approvals, no diagnostics, and for the widgets
no network while the screen is off. The widgets stay live through one
`specialUse` foreground service (`realtime/LiveService.kt`) that holds the
stream only while the screen is on. The one thing beyond the widgets is
Daten sichern (#194, laib ADR 0029; renamed 2026-11-03): it picks up the phone's WhatsApp
and Signal backups and uploads them to Haus, and, each behind its own switch
and permission, mirrors the contacts and sends the SMS and call log
age-encrypted. The seam with Haus is
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
  another, a second service, a poll (widget update periods stay 0), an
  alarm, a wakelock or a rescue worker. Recovery after a killed process rests
  on the battery-optimization exemption the person grants. `ManifestTest`
  holds this; `e2e/` proves it on emulators (`.github/workflows/live-e2e.yml`).
- One job, and only this one (the owner's call in #194): the backup pick-up,
  `sicherung/SicherungWorker.kt`, a WorkManager worker — daily on an unmetered
  network while charging, and once on „Jetzt sichern“. It never goes
  foreground, posts nothing, reads the granted folders only (storage access
  framework, no storage permission; the one writer is a restore on a new
  phone, `sicherung/SafZiel.kt`, run from its screen, not from this job) and, with their switches on, the
  contacts, SMS and call log (read permissions only), and is the one caller
  that does not pass `ScreenGate`. Without a granted folder or a switch on it
  makes no request. `ManifestTest`
  holds it to its one file; do not add a second job or reuse this one.
- Standortverlauf (the owner's call, 2026-11-02; docs/CONTRACT.md (f)):
  behind its own switch and the background-location permission,
  Android's own LocationManager delivers by PendingIntent to
  `standort/StandortEmpfaenger`, which stores the points and sends a
  batch to Haus while that delivery has the phone awake. It is no job,
  service, alarm or wakelock; keep it that way (`ManifestTest`). The
  decisions — rate per mode and situation, resting told from the fixes,
  when a batch goes — live in `standort/Plan.kt` and `standort/Ruhe.kt`,
  plain and unit-tested.

## Das Haus-Konto

- The owner's call, 2026-11-03 (#206): the household's shared contacts,
  calendars and reminders live in a real Android account „Haus“
  (AccountManager, two sync adapters for ContactsContract and
  CalendarContract), so the dialer, WhatsApp, mail and the calendar app use
  them. „Erinnerungen gehören in den normalen Kalender, nicht in ein
  Widget.“ Source: Haus's `/napi/gemeinsam/…` (vCard, iCalendar), with the
  device token. `konto/` holds it.
- Read only on the phone; writes go as the sync adapter into the account's
  own entries only. Android's SyncManager runs the adapters (the switch,
  „Jetzt abgleichen“, hourly); the app adds no job, alarm or worker for
  them. `ManifestTest` holds this.
- The other direction stays: a person's own contacts go phone → archive
  („Kontakte übertragen“), and the Haus account's never do
  (`HausKontoAusschlussTest`, `e2e` step 8).

## No Google Play services

- The owner's decision, 2026-11-02 („ohne Google“): no
  `com.google.android.gms` or Firebase library, class or permission — not
  for location, not for anything. Use the platform (LocationManager,
  SensorManager). `ManifestTest.noGooglePlayServices` fails if one
  comes back.

## The icon

- `art/icon.svg` is the one master (owner, 2026-11-02: the terminal prompt
  inside a house drawn as a green line, `#3ddc84` on `#0c120e`). Every PNG — launcher,
  adaptive foreground and monochrome, notification, store — is rendered from
  it by `scripts/icons` (`npm ci && node icons.mjs`), never drawn or resized
  by hand. mdopp/haus carries the same master for the web app's icons.

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
