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
  the token in the **fragment** (never in a proxy log). The app checks the
  prefix `haus_device_` before it stores the token, encrypted on the device.
- The setup QR is QR content, not a link: the manifest has an intent filter for
  `cloud.dopp.haus://pair` only, so no web page can point the app at a foreign
  server. `server` is required; `token` is optional (present → paired at once;
  absent → the app opens `/pair-device`).
- The persona — name, greeting, colours (`accent`, `ground`), icon — comes from
  `GET /napi/persona` and `GET /napi/persona/icon.svg`.

## (b) The `/napi` paths the app calls

Every request carries `Authorization: Bearer haus_device_…`; Haus answers 401
without one and a JSON 404 for a path it does not serve. The app's paths are
spelled whole in `app/src/main/java/cloud/dopp/haus/data/Napi.kt`, and
`NapiContractTest` holds them to Haus's own route table.

| Widget | Path |
|---|---|
| devices, device | `GET /napi/portal/state`, `GET /napi/portal/start/addable`, `POST /napi/ha/call`, `GET /napi/portal/entity-history` |
| active devices | `GET /napi/portal/active` |
| energy | `GET /napi/portal/energy`, `GET /napi/portal/entity-history` |
| camera | `POST /napi/upload` (capture), `GET /napi/portal/camera/{entity_id}/snapshot` |
| tools | `GET /napi/defs/tool`, the catalog's `tool-api-path` rows (`/napi/portal/{tasks,contacts,documents,notes}`), `POST /napi/action-callback` |
| Box (admins) | `GET /napi/box/home`, `GET /napi/box/services` |
| live stream | `POST /napi/portal/watch`, `GET /napi/portal/events` |

## (c) The live stream

While the screen is on and a widget is placed, the app holds
`GET /napi/portal/events` and reads `card_state` frames only. On screen-on it
reconnects, posts its watch set (`POST /napi/portal/watch`, re-posted inside
Haus's one-hour TTL) and re-reads its widgets. With the screen off nothing runs:
no service, no notification, no poll, no catch-up. Messages to people go
through their chat channel, not the app (laib ADR 0022 §3).

## (d) Pages

A widget's body tap opens one of Haus's pages in the browser: `/start` for the
house, `/box` for the Box. The app has no web shell of its own.
