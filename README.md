# haus-android

**Haus** for Android: the household's home-screen widgets and the pairing with
its server, [Haus](https://github.com/mdopp/haus). Nothing else (laib ADR 0022):
no web shell, no notifications, no approvals, no diagnostics.

- **Widgets:** a device and all devices (with colours and brightness), energy
  (flow, totals, chart), camera capture, active devices, the household's tools
  (tasks with check-off, contacts, documents, notes) and, for admins, the Box.
- **Pairing:** enter Haus's address, confirm on `/pair-device`, done. The
  house persona (name, colours, icon) comes from `/napi/persona`.
- **Current while you look:** one live stream (`/napi/portal/events`,
  `card_state` only), held only while the screen is on.

The seam with Haus is [`docs/CONTRACT.md`](docs/CONTRACT.md); the visual
standard is [`docs/DESIGN.md`](docs/DESIGN.md).

## Build

Plain Gradle (`./gradlew :app:assembleDebug`, `./gradlew :app:testDebugUnitTest`).
CI builds and tests every pull request; a `v*` tag builds a signed release when
the signing secrets are set.
