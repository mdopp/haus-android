# homes-android

**Homes** for Android: the household's home-screen widgets and the pairing with
its server, [Homes](https://github.com/mdopp/homes). Nothing else (laib ADR 0022):
no web shell, no messages, no approvals, no diagnostics.

- **Start screen:** your favorite devices first (what you pinned with ☆ on
  Homes), a tap switches one; below them „Alle Geräte“, Homes' devices page.
- **Widgets:** one device per widget (its state as a picture; a tap switches it), one energy widget
  (the flow now as a picture with the battery's charge, and heute; a tap opens Homes' Energie page), and the household's
  documents or notes. One look for every confirmation.
- **Share → Homes:** a link becomes a note; a photo (or several) from the
  camera app goes into Homes' document inbox, HEIC made JPEG on the phone.
- **Pairing:** enter Homes' address, confirm on `/pair-device`, done. The
  assistant's name — „Homes“ unless the box names another — comes from
  `/napi/assistent`; its look (green on a dark ground) and icon are the app's.
- **Current while you look:** one foreground service (`specialUse`, with a
  quiet "Live-Widgets" notification) holds the live stream
  (`/napi/portal/events`: `card_state` for the energy sensors) while the
  screen is on, drops it
  when the screen goes off and re-reads every widget on screen-on. No poll.
  On by default once paired; a switch in the app turns it off. That it comes
  back by itself after the app was killed is what the battery-optimization
  exemption guarantees; the app asks for it once.

- **Daten sichern:** daily, on WLAN while charging, the phone's
  WhatsApp and Signal backups go to the box. Two optional switches, off by
  default, each on its own card and asking for its own permission: „Kontakte“
  (READ_CONTACTS; into the person's address book „Handy-Kontakte“ on the box)
  and „SMS und Anrufe“ (READ_SMS, READ_CALL_LOG; age-encrypted
  on the phone for the person's archive). On Android 13+ a sideloaded app
  must first get App-Info → ⋮ → „Eingeschränkte Einstellungen zulassen“ for
  SMS and calls; the screen says so.

The seam with Homes is [`docs/CONTRACT.md`](docs/CONTRACT.md); the visual
standard is [`docs/DESIGN.md`](docs/DESIGN.md).

## Build

Plain Gradle (`./gradlew :app:assembleDebug`, `./gradlew :app:testDebugUnitTest`).
CI builds and tests every pull request; a `v*` tag builds a signed release when
the signing secrets are set. Each merge to `main` publishes a signed APK as a
pre-release `v<next patch>-dev.<run>`; the newest five are kept, none is marked
latest. Every signed build takes `versionCode` from the workflow's run number,
so each installs over the one before.

## Test builds on a phone

Until the app is on Play, a phone follows the releases with
[Obtainium](https://github.com/ImranR98/Obtainium): add
`https://github.com/mdopp/homes-android`, turn on *Include prereleases*, and
allow Obtainium to install apps. Dev builds and releases carry the same key,
so the phone moves between them without reinstalling. A debug build has a
different key and must be uninstalled first; the app is then paired again.

**Coming from Haus** (laib#1021): the app was called Haus. Homes has its own
package id (`cloud.dopp.homes`), so it is a new app beside it: add it in
Obtainium as above, pair it, place the widgets again, then remove Haus and its
Obtainium entry.
