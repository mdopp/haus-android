# CONTRACT — the interface with Haus

The seam between this repo (the Android app) and its server, **Haus**
(`mdopp/haus`, served at `https://haus.<domain>`). Both sides must agree on
these; any change here is a **cross-repo coordination**.

**History.** Until laib ADR 0021 the server was **Solaris** (`mdopp/solarisbay`,
`https://chat.dopp.cloud`), which also re-served ServiceBay's data for the
widgets. Haus took over that role. The names Solaris gave this contract are
held by every installed app, so they are **legacy names that stay valid**
next to the neutral ones (#174). Haus keeps them in `internal/legacy`; it
switches to the neutral names only after an app release that knows them is
installed on every family phone, and until then accepts both.

| | Neutral | Legacy | App behaviour |
|---|---|---|---|
| device-token prefix | `haus_device_` | `sol_device_` | stores a token with either prefix |
| pair deep link | `haus://pair#token=…&id=…` | `cloud.dopp.solaris://pair#token=…&id=…` | both open the hub; either scheme may carry either prefix |
| setup QR content | `haus://setup?server=…&token=…` | `cloud.dopp.solaris://setup?server=…&token=…` | both parsed by the in-app scanner |
| Box keys | `/napi/box/{home,services,approvals,upgrades}` | `/napi/servicebay/…` | new first, old on a 404, the answering one remembered per server |
| package id | — | `cloud.dopp.solaris` | **never changes**: a new id is a new app |

## (a) Digital Asset Links — `assetlinks.json`

- **Endpoint:** `GET <server>/.well-known/assetlinks.json`, e.g.
  `https://haus.<domain>/.well-known/assetlinks.json`.
- **Served by:** the paired server. Solaris served it from solaris-chat, with
  the fingerprints from env **`ANDROID_CERT_FINGERPRINTS`** (comma-separated →
  array elements). Haus does not serve it yet; until it does, the in-app
  surface on a Haus server falls back to a Custom Tab with a URL bar.
- **Auth:** **PUBLIC / unauthenticated.** Google's Digital Asset Links verifier
  fetches this itself and is not logged in — it **must not** sit behind
  Authelia. It is the one path on the server's host that has to bypass the
  auth gate.
- **Content:**

  ```json
  [
    {
      "relation": ["delegate_permission/common.handle_all_urls"],
      "target": {
        "namespace": "android_app",
        "package_name": "cloud.dopp.solaris",
        "sha256_cert_fingerprints": ["AA:BB:CC:..."]
      }
    }
  ]
  ```

- **Fingerprints** come from this repo's signing keys — see `docs/SETUP.md`
  steps 3–4 and 7 (upload key + Play App Signing key).
- **Package name** must stay `cloud.dopp.solaris` (the app's
  `applicationId`). Changing it breaks verification on both sides and makes a
  new app.

Failure mode: mismatch/absence → the in-app surface renders **with a URL bar**
(a plain Custom Tab) instead of full-screen.

## (b) Device token and pairing

Native surfaces (home-screen widgets, tiles, the realtime service) run
**outside** the browser, so they can't ride the Custom Tab cookies. They
authenticate with a device token minted by the server:

- **Pairing.** The user enters the server address (or scans its QR). The app
  opens `<server>/pair-device` in a Custom Tab; Authelia logs the person in,
  they confirm, and the server redirects to the pair deep link with the token
  in the **fragment** (never in proxy logs). The app checks the prefix
  (`haus_device_` or `sol_device_`) before it stores the token.
- **Setup QR.** The server's device page can mint a token and show it as a
  setup URI in a QR code. That URI is QR content, not a link: the manifest has
  intent filters for `…://pair` only, so no web page can point the app at a
  foreign server. `server` is required; `token` is optional (present → paired
  at once; absent → the app opens `/pair-device`).
- **Use.** The server **accepts** `Authorization: Bearer <device token>` on the
  token-only `/napi/` prefix, which bypasses Authelia and fails closed.
  The app stores the token encrypted on-device and sends it on every request.

The mint/accept surface is owned by the server; this repo consumes it. The
token prefixes, both deep-link schemes and the bearer rule are the contract.

## (c) Box keys

The widgets read four Box keys token-authed under `/napi`: `home` (summary
counts), `services`, `approvals`, `upgrades`. The app asks `/napi/box/<key>`
first; on a **404** it asks `/napi/servicebay/<key>` and remembers per server
which one answered. Any other failure (401, 5xx, no network) is not a reason to
fall back. A server only has to serve one of the two names; serving both is how
the switch happens without breaking older apps.

## (d) App entry / auth

- The server is chosen per install (`https://haus.<domain>`); nothing is
  hard-coded. The in-app surface opens the paired server's pages.
- Auth happens via **Authelia inside the Chrome Custom Tab** — the same cookie
  jar as the browser PWA. No separate mobile login flow.

---

**Changing any of the above** (package id, fingerprints, token prefixes,
deep-link schemes, `/napi` paths, the bearer rule, the public/unauth
requirement on assetlinks) requires a coordinated change in both
`mdopp/solaris-android` and `mdopp/haus`.
