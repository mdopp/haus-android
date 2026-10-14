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
  `GET /napi/persona` and `GET /napi/persona/icon.svg`.

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
| pairing | `GET /napi/whoami`, `GET /napi/persona`, `GET /napi/persona/icon.svg` |
| device | `GET /napi/portal/state`, `GET /napi/portal/start/addable` (without cameras: Haus draws no card for one), `POST /napi/ha/call` |
| active devices | `GET /napi/portal/active` |
| energy | `GET /napi/portal/energy`, `GET /napi/portal/entity-history` (`range` 24h or 7d) |
| camera | `POST /napi/upload` (capture) |
| tools | `GET /napi/defs/tool`, the catalog's `tool-api-path` rows (`/napi/portal/{tasks,contacts,documents,notes}`), `POST /napi/action-callback` |
| Box (admins) | `GET /napi/box/home`, `GET /napi/box/services` |
| live stream | `POST /napi/portal/watch`, `GET /napi/portal/events` |

## (c) The live stream

While the screen is on and a widget the stream feeds is placed (device, active
devices, the Box widgets), the app holds `GET /napi/portal/events` and reads
`card_state` frames only. On screen-on it reconnects, posts its watch set
(`POST /napi/portal/watch`, re-posted inside Haus's one-hour TTL; a 503 while
Home Assistant is down is retried with backoff, about two minutes) and
re-reads its widgets. A session counts as healthy — and resets the reconnect
backoff — only after 120 s, longer than Haus's 90 s ping, so a proxy that
cuts idle streams does not make the app reconnect every minute. With the screen off nothing runs:
no service, no notification, no poll, no catch-up. Messages to people go
through their chat channel, not the app (laib ADR 0022 §3).

## (d) Pages

A widget's body tap opens one of Haus's pages in the browser: `/start` for the
house, `/box` for the Box. The app has no web shell of its own.
