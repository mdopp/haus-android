# solaris-android — house rules

This repo is the **Android app** for the house: native Kotlin home-screen
widgets, quick-settings tiles and pairing, plus a Trusted Web Activity that
shows the paired server's pages full-screen. Its server is **Haus**
(`https://haus.<domain>`, repo `mdopp/haus`), the family's surface of the box
that Laib runs. The seam between the two is `docs/CONTRACT.md`.

**History, and why old names stay.** The app was built against **Solaris**
(`mdopp/solarisbay`, served at `https://chat.dopp.cloud`), which aggregated
ServiceBay for the widgets. Haus replaces both (laib ADR 0021). The installed
app is the one thing that cannot be renamed under the family's feet, so the
old names are a contract with installed devices and stay valid next to the
new ones (#174):

| Legacy name | Neutral name | Notes |
|---|---|---|
| token prefix `sol_device_` | `haus_device_` | the app accepts both before storing a token |
| deep link `cloud.dopp.solaris://pair`, QR `cloud.dopp.solaris://setup` | `cloud.dopp.haus://pair`, `cloud.dopp.haus://setup` | both schemes, either prefix |
| `/napi/servicebay/{home,services,approvals,upgrades}` | `/napi/box/…` | new first, old on a 404, remembered per server |
| package id `cloud.dopp.solaris`, repo `solaris-android` | — | stays: a new package id is a new app |

Haus switches to the neutral names only after a release carrying them is on
every family phone; until then it speaks both. New code never takes on a
legacy name — it reaches them through `SolarisConfig`, `data/PairLinks` and
`data/BoxPaths`.

These rules apply to every session, human or agent.

## Commits

- **Conventional Commits**: `type(scope): subject` — `feat`/`fix`/`refactor`/
  `chore`/`docs`/`test`. Scope mirrors the area: `feat(twa):`, `feat(widget):`,
  `feat(tile):`, `chore(ci):`, `docs:`.
- **No parentheses in the subject** beyond the conventional `(scope)`.

## Never commit secrets

- **NEVER** commit signing keystores or Play credentials. This includes
  `*.jks`, `*.keystore`, `*.keystore.base64`, `local.properties`, and any Play
  Console service-account JSON. They are:
  - kept **local** on the machine that generated them, and
  - injected into CI via **secrets** (`ANDROID_KEYSTORE_BASE64`,
    `ANDROID_KEYSTORE_PASSWORD`).
- The keystore is not recoverable if lost — back it up **outside** the repo.
  Losing the upload key means a Play key reset.

## The contract with Haus

- The app's **signing SHA256 certificate fingerprint** must be listed in the
  server's `/.well-known/assetlinks.json`. This is the Digital Asset Links
  **domain-verification handshake** — without it the TWA shows a URL bar. See
  `docs/SETUP.md` (written for Solaris, which took the fingerprints from env
  `ANDROID_CERT_FINGERPRINTS`) and `docs/CONTRACT.md` (a).
- The server is **per install**: the user enters `https://haus.<domain>` (or
  scans its QR); nothing is hard-coded. Web auth rides the **Chrome Custom Tab
  cookies** = the existing **Authelia** session. No separate mobile login.
- Native surfaces use a **device token** (`haus_device_…`, legacy
  `sol_device_…`) instead of the browser cookies, on the token-only `/napi/`
  prefix. Contract in `docs/CONTRACT.md`.
- Any change to package id, fingerprints, deep-link schemes, the token
  contract or the `/napi` paths is a **cross-repo change** — coordinate with
  `mdopp/haus` (its `internal/legacy` holds the old names).

## Releases

- Play release = a **signed AAB** uploaded via **Play Console**. This is
  **human-gated** (Internal testing for family use; no automated publish).
- The debug/unsigned build in CI only proves the project *compiles* — it is not
  a release.

## Build

- **Bubblewrap** (Node CLI) for the TWA, or **Android Studio** for native work.
  Bubblewrap auto-downloads its own JDK + Android SDK.
- Scaffolded Gradle outputs (`/app/build/`, `.gradle/`, `build/`) are
  **gitignored** — only `twa-manifest.json` + native source are versioned.

## Verify

- An Android app **cannot be box-verified** like the server. It is verified on a
  **device or emulator** (install the APK, check it launches full-screen with no
  URL bar, notifications arrive, widgets render). If you can't run a
  device/emulator, say so explicitly — don't claim a green build is a verified
  feature.
