# CONTRACT — the interface with Haus

The seam between this repo (the Haus app) and its server, **Haus**
(`mdopp/haus`, served at `https://haus.<domain>`). Haus is the contract: the
app calls exactly the paths Haus serves, under the names Haus's
`internal/app` declares (laib ADR 0022). One set of names, nothing older next
to them. Any change here is a **cross-repo change** with `mdopp/haus`.

| | Value | Haus |
|---|---|---|
| package id | `cloud.dopp.haus` | — |
| device-token prefix | `haus_device_` | `app.TokenPrefix` |
| pair deep link | `cloud.dopp.haus://pair#token=…&id=…` | `app.Scheme`, `pages/pair.go` |
| setup QR content | `cloud.dopp.haus://setup?server=…&token=…` | `pages/geraete.go` |

The scheme is named after the domain on purpose: a bare `haus://` could be
registered by any other app on the phone and catch the token in the redirect.

## (a) Pairing

- The person enters Haus's address (or scans the QR on Box → Geräte). The app
  opens `<haus>/pair-device` in a Custom Tab, where their Authelia session is;
  they confirm; Haus mints a device and redirects to the pair deep link with
  the token in the **fragment** (never in a proxy log).
- Only `https://` addresses: an `http://` one is refused with a message, typed
  or scanned.
- The pair link is taken only while a pairing the app started is pending (it
  opened `/pair-device` less than 15 minutes ago); Haus's redirect carries no
  nonce of the app's, so the pending server is what ties the link to it. Any
  other pair link pairs nothing.
- Nothing replaces server or token without a dialog that names the host: the
  pair link, a scanned address and a scanned setup QR all ask first.
- Before the token is stored, the app checks it: the prefix `haus_device_`,
  then `GET /napi/whoami` against that server (200 with a `uid`). Then it is
  stored encrypted on the device; if that write fails, the app says so and
  stays unpaired.
- The setup QR is QR content, not a link: the manifest has an intent filter for
  `cloud.dopp.haus://pair` only, so no web page can point the app at a foreign
  server. `server` is required; `token` is optional (present → paired once
  confirmed and checked; absent → the app opens `/pair-device` there).
- The assistant's name comes from `GET /napi/assistent` → `{"name": "Homes"}`
  (the box's one setting, Laib's box.yml `assistant`; „Homes“ when unset). The
  app keeps the last name in plain prefs, forgets it on unpairing and shows
  „Homes“ until Haus answered. Nothing else about the assistant comes from the
  box: its colours are fixed in the app (`terminal_green` `#3DDC84` on
  `terminal_ground` `#0C120E`) and its icon is the app's own launcher icon.
  (This replaced the per-box persona of laib ADR 0020, owner 2026-11-02.)

## (b) The `/napi` paths the app calls

Every request carries `Authorization: Bearer haus_device_…`; Haus answers 401
without one and a JSON 404 for a path it does not serve. Each call is declared
as data in `app/src/main/java/cloud/dopp/haus/data/Napi.kt` — method, path,
query parameters (and the values Haus takes), body fields, the reply keys the
app reads — and `data/NapiRequest.kt` is the one place a request is built from
it. `NapiContractTest` holds every declaration to a copy of Haus's handlers,
each row naming the Haus file and lines it came from.

| Widget | Path |
|---|---|
| pairing | `GET /napi/whoami`, `GET /napi/assistent` |
| the start screen's devices | `GET /napi/portal/favorites` (the person's ☆ things on Haus, as cards in their order; haus#213), `POST /napi/ha/call`, `GET /napi/portal/state` (a cover's or a lock's state before its toggle) |
| one device (widget, restored 2026-11-03) | `GET /napi/portal/state`, `POST /napi/ha/call`; its picker `GET /napi/portal/start/addable` |
| app-icon shortcuts | `GET /napi/portal/start/addable` (without cameras: Haus draws no card for one), `POST /napi/ha/call` |
| energy | `GET /napi/portal/energy`: the flow now and, per total, `today` (kWh since the house's midnight; haus#213) |
| Daten sichern | `GET /napi/sicherung/stand`, `POST /napi/sicherung/fehlend`, `POST /napi/sicherung/uploads`, `HEAD`/`PATCH /napi/sicherung/uploads/{id}`, `GET /napi/sicherung/schluessel`, `POST /napi/kontakte`, `GET /napi/sicherung/letzte`, `GET /napi/sicherung/letzte/{quelle}/{pfad...}`: see (e) |
| Standortverlauf | `GET /napi/standort`, `POST /napi/standort`: see (f) |
| Share → Haus, photos | `POST /napi/upload`, multipart `filename` then `file`, one photo a request → `{ok, id}`: the camera app's photos (one or several, `image/*`) into the folder Paperless consumes (owner, 2026-11-02). JPEG, PNG, WebP and GIF of at most 25 MB go as they are; HEIC/HEIF or anything larger is made a JPEG on the phone (long edge ≤ 4096). At most 20 a share. Haus's refusals are codes; the app says them in German by status (404 no folder, 413, 415, 429 busy, 503, 507 full) |
| Share → Haus | `POST /napi/merken` `{text}`: what the share sheet sent (title and link); Haus keeps the page as a note and answers `{kurz, notiz, schon, …}` or `{error}` with a German sentence (haus#120); the share sheet's Direct Share target carries the assistant's name (#188) |
| tools | `GET /napi/defs/tool`, the catalog's `tool-api-path` rows (`/napi/portal/{documents,notes}`; Haus also lists `contacts` and `task`, which the app no longer offers as a widget, owner 2026-11-02 and 2026-11-03), `POST /napi/action-callback` |
| live stream | `POST /napi/portal/watch`, `GET /napi/portal/events` |

Fields the widgets read beyond a value to show:

- `page` on every card (`/napi/portal/state`, `/napi/portal/favorites`) and
  on every tool row with an id: the item's own page on Haus, a path
  (`/raeume/<entity_id>`, `/kalender/erinnerung/<uid>`, `/dokumente/<id>`,
  `/notizen/<file>`). See (d).
- `today` on every energy total: what it added since the house's midnight,
  in kWh, by the Energie page's rule; absent when HA's history does not say,
  and then the widget shows a dash, never the lifetime count.

## (c) The live stream

One foreground service (`realtime/LiveService.kt`, type `specialUse`, a quiet
"Live-Widgets" notification) runs while the app is paired, its switch is on
(the default) and a widget the stream keeps current is placed (one device,
energy, tools; the Box widgets are gone, owner 2026-11-03). It listens for the screen itself. While the screen is on it
holds `GET /napi/portal/events` and reads one frame, `card_state`; on
screen-off it drops the connection. Once connected it posts its watch set
(`POST /napi/portal/watch`: `entity_ids`, the device widgets' devices and,
while the energy widget is placed, the energy flow's sensors; re-posted inside Haus's one-hour TTL; a 503 while
Home Assistant is down is retried with backoff, about two minutes) and has
every fed widget read again.

- `card_state` (`{entity_id, card}`) draws the device widgets of that entity
  without a read; for an energy sensor it reads the energy
  widget again, at most every 10 s, Haus's sensor interval. Any other frame
  reads nothing. The app never asks for `active_set` (no `active` in its
  watch), and a stray one is not read.

A 401 stops the service.

It starts from allowed starts only (Android 12+): the app's screen, a widget
tap, a configure screen (the fed widgets without a choice have a no-UI one,
so placing them counts), boot and an app update. After its process died,
Android restarts it. On Android 12+ that restart is a background start, which
is documented as allowed while the app is exempt from battery optimization —
asked once after pairing, shown beside the switch. (On the e2e's emulators,
API 31 to 35, the restart also went foreground without it; that is not
documented, and an OEM build may refuse it. A refused restart ends quietly,
without a notification, until the next allowed start.) A connection silent for 240 s (Haus pings every
90 s) counts as dead and is reopened. A session counts as healthy — and resets the reconnect
backoff — only after 120 s, longer than Haus's 90 s ping, so a proxy that
cuts idle streams does not make the app reconnect every minute. With the screen
off nothing goes over the network for the widgets: no stream, no poll (every
widget's update period is 0), no catch-up, and no other request: every widget
call to Haus passes `data/ScreenGate.kt`, so a widget update Android delivers
late reads nothing and keeps its last card. The one exception is the backup
job of (e). Messages to people go
through their chat channel, not the app (laib ADR 0022 §3).

## (d) Pages

A widget's body tap opens one of Haus's pages in the browser at the paired
address: `/energie` for the energy widget, `/start` for a tool widget's
header, a device's own page for a device widget whose device a tap cannot
switch (a lamp, a switch, a cover, a lock act on the tap instead). The app has
no web shell of its own; its start
screen opens Haus's pages in a Custom Tab: „Alle Geräte“ `/raeume`, a
favorite's own page on a long press (or on a tap, for a device a tap cannot
switch).

An item opens its own page, base + `page`: a favorite on the start screen, a
tool row. Only a path on Haus is taken (it starts with a single `/`, has no
`..`, no backslash, no whitespace); an absolute URL or anything else is never
opened. Without a page a favorite opens `/start`, and a tool row keeps its
tap: a row with actions opens its sheet — with "Öffnen" as one more entry,
after the actions, when it has a page — and a row with neither does nothing.
A collection row has one tap target, so tool rows go through one translucent
screen (`ToolActionActivity`) that opens the page or the sheet.

Every page a widget opens carries `von=widget.<kind>` exactly once (`?von=…`,
or `&von=…` after a query the page already has; a `von` of the page's own is
replaced), so Haus counts server-side which widget a tap came from (#180).
The kinds (`WidgetKind` in `widget/HausPages.kt`): `device`, `energy`, `tool`. The app counts nothing and makes no call for it; a
launcher shortcut and the app's own links carry no `von`.

## (d2) The start screen's devices

(Owner, 2026-11-02: the devices live in the app, the device, active-devices
and camera widgets are gone.) Paired, the app's screen shows first the
person's favorite devices — `GET /napi/portal/favorites`, the things they
pinned with ☆ on Haus, in their order — and below them „Alle Geräte“, which
opens Haus's `/raeume`. The list read last is kept and drawn at once; a read
that fails keeps it. A tap acts as the device widget's did, through
`widget/WidgetActionReceiver`: a light or a switch toggles, a cover opens or
closes (a garage, a door, a gate asks first: Haus's 403), a lock opens its
chooser (#92: „Tür öffnen“ only when the card's `supported_features` names a
latch); anything else opens its page. When the call is done the list is read
again. With no favorites the screen says in one sentence how to get some.

## (e) Daten sichern

(#194, laib ADR 0029; Haus's side is haus#207.) Under Mehr → Nachrichten
sichern the person grants two folders through the system folder picker
(`ACTION_OPEN_DOCUMENT_TREE`, a persisted read grant; no storage permission):
WhatsApp's, `Android/media/com.whatsapp/WhatsApp` (the picker opens there),
and the one they chose in Signal for its backups. The app only reads them.

One WorkManager job (`sicherung/SicherungWorker.kt`) runs daily on an
unmetered network while the phone charges and its battery is not low, and
once at once on „Jetzt sichern“ (unmetered network only). It never goes
foreground. It sends:

- **WhatsApp:** `Databases/msgstore.db.crypt15` when it is not the one sent
  last (name, size, time kept in the app's prefs), and the files under
  `Media/` Haus reports missing (`POST /napi/sicherung/fehlend`, at most 5000
  paths a call). A path with a dot-name at any level (`.Statuses`,
  `.nomedia`) is never asked about or sent: Haus refuses the whole question
  for one.
- **Signal:** the newest `signal-….backup` directly in its folder, only when
  it is newer than the one sent last. Its `pfad` is its file name.

A file changed in the last ten minutes waits for the next run (it may still
be being written).

The upload: `POST /napi/sicherung/uploads` `{quelle, pfad, groesse}` → 201
`{id, offset}`; then `PATCH /napi/sicherung/uploads/{id}` with
`Upload-Offset` and `Content-Type: application/offset+octet-stream`, 8 MiB a
piece (Haus takes 16), → `{offset, fertig}`. The id is kept per file until it
is whole; after any interruption the next run asks `HEAD …/{id}`
(`Upload-Offset`) and goes on from there. An offset equal to the size — after
HEAD (Haus keeps a finished upload known for seven days) or straight from
the creation — is a finished file. A 409, or a 408 after a partial write,
goes on from the `offset` Haus returns; a 404 starts a new upload; a 429 (the
box takes other pieces) and no answer end the run and WorkManager runs it
again with backoff. 507 (full), 503 (no archive), 403 (a device of nobody)
and 401 stop the run with a German sentence on the screen. Errors are
`{"fehler": …}`, except the 401 of the shared login check
(`{"error":"unauthorized"}`), for which the app says to pair again.

`GET /napi/sicherung/stand` → `{quellen: {whatsapp|signal|sms: {zuletzt,
dateien, bytes, wartend}}, frei_bytes}` is shown per source on the screen.
The app never asks for or stores a messenger's key: keys go into the
person's vault on the box (laib#986).

The screen's progress is the run's totals per source — files whole of all
it sends, bytes on the box of all their bytes („3.809 von 18.017 Dateien,
1,2 von 9,8 GB“), the file under way counted as far as it got — not one
file's bytes (owner, 2026-11-01).

### Sicherung zurückholen

(haus-android#197; Haus's side is haus#209, laib ADR 0029 amendment
2026-11-01.) On a new phone where the app is paired: `GET
/napi/sicherung/letzte` → `{quellen: {signal|whatsapp: [{pfad, groesse,
geaendert}]}}` (epoch ms; a WhatsApp medium as `Media/…`, its plaintext
size) is what the box keeps. Each file comes with `GET
/napi/sicherung/letzte/{quelle}/{pfad...}` (every segment URL-encoded, the
`/` kept), `Range: bytes=<n>-` → 206 with `Content-Range` (200: whole) and
an `ETag`. The person grants a folder per source with write access
(`ACTION_OPEN_DOCUMENT_TREE`, read and write persisted): for Signal any
folder (e.g. „Dokumente/Signal“), the `.backup` goes into it; for WhatsApp
`Android/media/com.whatsapp/WhatsApp` (or `Android/media/com.whatsapp`, or
`Android/media` when nothing is there yet; the rest is made), the files go
to their `pfad` below it, the media first and the database last.

- **Nothing is written over without asking.** A file already there that
  the restore did not write itself is left alone when it has the box's size,
  and otherwise only replaced on „Ersetzen“; the app keeps which files it
  wrote, with the box's ETag, in its prefs.
- **Resumable.** A file of its own goes on from its size with a Range; one
  whose ETag changed on the box (the next night's backup) starts over.
  „Anhalten“ stops after the piece being written. The run is a thread of the
  app while its screen stays on (`FLAG_KEEP_SCREEN_ON`), no job.
- **Media** come only while the person's archive is open: 423 with Haus's
  sentence, then no further medium is asked for, the database still comes,
  and the screen offers „Archiv öffnen“ (`archiv.<domain>` beside
  `haus.<domain>`). 404 is a backup gone from the box: the list is loaded
  again.
- `sicherung/SafZiel.kt` is the one file that writes a document: it makes
  folders and files and writes into them, never deletes, renames, moves or
  copies one (`ManifestTest`).

### Kontakte, SMS und Anrufe

(laib ADR 0029; Haus's side is haus#210.) Two switches on the same screen,
off until the person turns one on, each asking for its own permission when
turned on; refused, it stays off with a sentence. The same job runs them,
after the folders, only while the switch is on and the permission granted.

- **„Kontakte“** (READ_CONTACTS): every contact, read from
  `ContactsContract.Data` and written by the app as vCard 3.0 (not Android's
  2.1 export, which Radicale may refuse) with `UID:<lookup key>`, escaped
  text, lines folded at 75 octets and CRLF, all concatenated into one
  `POST /napi/kontakte` (`Content-Type: text/vcard`) → `{kontakte, neu,
  geaendert, entfernt}`. Haus mirrors the set into the person's own address
  book „Handy-Kontakte“, which the box's admin can read like the calendar.
  No contact on the phone: no request. No photos.
- **„SMS und Anrufe“** (READ_SMS, READ_CALL_LOG): the SMS and
  MMS, and the calls, newer than a high-water mark per kind (the largest
  date that reached the box; the first run takes everything), as XML in the
  format of „SMS Backup & Restore“ by SyncTech (`<smses>` and `<calls>`, what
  `msgvault import-synctech-sms` reads), written as a stream into the app's
  cache. Each file is age-encrypted on the phone (age v1, one X25519
  recipient: `GET /napi/sicherung/schluessel` → `{empfaenger: "age1…"}`, or
  404 with Haus's sentence, shown as it is), the plaintext deleted, and the
  `.age` file uploaded through the resumable upload above as `quelle` `sms`,
  `pfad` `sms-<UTC yyyyMMddTHHmmssZ>.xml.age` and `anrufe-<…>.xml.age`. A
  mark moves only once its file is whole on the box; an unfinished file is
  sent on by the next run before anything new is read.

On Android 13+ an app installed outside a store cannot even ask for SMS and
the call log ("restricted settings"): the screen then says to open App-Info →
⋮ → „Eingeschränkte Einstellungen zulassen“ and turn the switch on again.

## (f) Standortverlauf

The owner's call, 2026-11-02. Off until the person turns its switch on
(Mehr → Standortverlauf), behind its own permissions: the location with
„Immer zulassen“ (`ACCESS_FINE/COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`).
No Google Play services (the owner, 2026-11-02): the platform alone.

- The phone: Android's LocationManager, delivered by PendingIntent to
  `standort/StandortEmpfaenger` — no service, job, alarm or wakelock of the
  app's. The provider (`Anbieter`): high accuracy is GPS; balanced is the
  network provider, else the platform's fused one (Android 12+), else GPS.
  On Android 12+ the request carries quality and batching
  (`LocationRequest`), before that the plain interval. While the phone rests,
  a second request on the passive provider takes the fixes other apps cause.
- The rate (`standort/Plan.kt`, chosen, not measured): „Detailliert“ on the
  move every 15 s (GPS), resting 5 min, resting on a Wi-Fi hourly;
  „Sparsam“ on the move every 2 min (network), resting 15 min, resting on a
  Wi-Fi every 2 h. Any Wi-Fi counts (owner: „Wenn WLAN, bewegt sich
  selten“).
- Resting (`standort/Ruhe.kt`): the fixes stay within their accuracy (at
  least 30 m) of one place for 3 min (Detailliert) or 10 min (Sparsam); a
  fix less exact than 500 m counts for nothing. It ends with a fix farther
  away, or when the platform's significant-motion sensor fires — armed while
  the phone rests, one-shot, alive only as long as the app's process. A
  phone without that sensor keeps the plain interval and the screen says so.
- The points wait in a file of the app's (`standort/Puffer.kt`, at most
  50 000, the oldest dropped beyond and said on the screen) and go in
  batches of ≤ 500 when 200 wait or the oldest waited 15 min
  (Detailliert) / 30 min (Sparsam), at a location delivery that woke the
  phone anyway, or at once on „Jetzt senden“ and when the screen opens.
- `POST /napi/standort {punkte:[{zeit, breite, laenge, genauigkeit, hoehe?,
  tempo?}]}` → 200 `{angenommen, verworfen}` once Reitti has them: only then
  do they leave the phone. Any other answer keeps the batch, with a pause of
  5 min doubling to 2 h: 409 `{fehler, grund: kein_schluessel |
  schluessel_abgelehnt}`, 503 `{fehler, grund: reitti_weg |
  nicht_eingerichtet}`, 401 (pair again), no answer.
- `GET /napi/standort` → `{verbunden, seit?}`: without a connection the
  screen says in one sentence to paste the Reitti token on Haus's page
  `/standort`, and a tap opens it there. The token never reaches the phone.
