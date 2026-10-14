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
| Box keys | `/napi/box/home`, `/napi/box/services` | `app.BoxPrefix` |

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
- The persona — name, greeting, colours (`accent`, `ground`), icon — comes from
  `GET /napi/persona` and, when it names an icon, `GET /napi/persona/icon.png`:
  a PNG of at most 256 KB, decoded with `BitmapFactory` off the main thread.
  The app has no SVG decoder; until Laib renders a persona's PNG (laib#191)
  Haus names no icon and the app shows none.

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
| pairing | `GET /napi/whoami`, `GET /napi/persona`, `GET /napi/persona/icon.png` |
| device | `GET /napi/portal/state`, `GET /napi/portal/start/addable` (without cameras: Haus draws no card for one), `POST /napi/ha/call` |
| active devices | `GET /napi/portal/active` |
| energy | `GET /napi/portal/energy`, `GET /napi/portal/entity-history` (`range` 24h or 7d) |
| camera | `POST /napi/upload` (capture) |
| tools | `GET /napi/defs/tool`, the catalog's `tool-api-path` rows (`/napi/portal/{tasks,contacts,documents,notes}`), `POST /napi/action-callback` |
| Box (admins) | `GET /napi/box/home`, `GET /napi/box/services` |
| live stream | `POST /napi/portal/watch`, `GET /napi/portal/events` |

Fields the widgets read beyond a value to show:

- `page` on every card (`/napi/portal/state`, `/napi/portal/active`, the
  stream's `card_state`) and on every tool row with an id: the item's own page
  on Haus, a path (`/raeume/<entity_id>`, `/kalender/erinnerung/<uid>`,
  `/kontakte/<uid>`, `/dokumente/<id>`, `/notizen/<file>`). See (d).
- `pendingApprovals` and `pendingUpdates` of `/napi/box/home`: Haus counts a
  template update once, as an update, never as an approval. The Box widget
  shows the two numbers apart.

## (c) The live stream

One foreground service (`realtime/LiveService.kt`, type `specialUse`, a quiet
"Live-Widgets" notification) runs while the app is paired, its switch is on
(the default) and a widget the stream keeps current is placed (device, active
devices, energy, energy statistics, energy totals, tools, the Box widgets). It
listens for the screen itself. While the screen is on it holds
`GET /napi/portal/events` and reads two frames, `card_state` and `active_set`;
on screen-off it drops the connection. Once connected it posts its watch set
(`POST /napi/portal/watch`: `entity_ids`, the device widgets' entities and,
while an energy widget is placed, the energy flow's sensors; `active: true`
while an active-devices widget is placed, left out otherwise; re-posted inside
Haus's one-hour TTL, and at once when the first active-devices widget is placed
or the last one removed; a 503 while Home Assistant is down is retried with
backoff, about two minutes) and has every fed widget read again.

- `card_state` (`{entity_id, card}`) draws the device widgets of that entity
  and reads the active list again; a frame for an energy sensor reads the
  energy widgets again, at most every 10 s, Haus's sensor interval.
- `active_set` (`{entity_ids}`) comes only while the watch says `active`: at
  once when the stream opens or the watch turns active, then when the set of
  things that are on or open changes, at most every 10 s. The app reads
  `/napi/portal/active` again unless the ids are exactly the ones it last read
  (order aside). A failed read keeps the list last read.

A card and the set it changed arrive together; they cost one read of the
active list, not two. A 401 stops the service.

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
off nothing goes over the network: no stream, no poll (every widget's update
period is 0), no catch-up. Messages to people go
through their chat channel, not the app (laib ADR 0022 §3).

## (d) Pages

A widget's body tap opens one of Haus's pages in the browser: `/start` for the
house, `/box` for the Box. The app has no web shell of its own.

An item opens its own page, base + `page`: a device widget's name, a row of
the active devices, a tool row. Only a path on Haus is taken (it starts with a
single `/`, has no `..`, no backslash, no whitespace); an absolute URL or
anything else is never opened. Without a page a device and an active row open
`/start`, and a tool row keeps its tap: a row with actions opens its sheet —
with "Öffnen" as one more entry, after the actions, when it has a page — and a
row with neither does nothing. A collection row has one tap target, so active
and tool rows go through one translucent screen (`ToolActionActivity`) that
opens the page or the sheet.
