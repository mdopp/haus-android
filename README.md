# haus-android

**Haus** for Android: the household's home-screen widgets and the pairing with
its server, [Haus](https://github.com/mdopp/haus). Nothing else (laib ADR 0022):
no web shell, no messages, no approvals, no diagnostics.

- **Widgets:** a device and all devices (with colours and brightness), energy
  (flow, totals, chart), camera capture, active devices, the household's tools
  (tasks with check-off, contacts, documents, notes) and, for admins, the Box.
- **Pairing:** enter Haus's address, confirm on `/pair-device`, done. The
  house persona (name, colours, icon) comes from `/napi/persona`.
- **Current while you look:** one foreground service (`specialUse`, with a
  quiet "Live-Widgets" notification) holds the live stream
  (`/napi/portal/events`: `card_state`, and `active_set` for the active
  devices) while the screen is on, drops it
  when the screen goes off and re-reads every widget on screen-on. No poll.
  On by default once paired; a switch in the app turns it off. That it comes
  back by itself after the app was killed is what the battery-optimization
  exemption guarantees; the app asks for it once.

The seam with Haus is [`docs/CONTRACT.md`](docs/CONTRACT.md); the visual
standard is [`docs/DESIGN.md`](docs/DESIGN.md).

## Build

Plain Gradle (`./gradlew :app:assembleDebug`, `./gradlew :app:testDebugUnitTest`).
CI builds and tests every pull request; a `v*` tag builds a signed release when
the signing secrets are set.
