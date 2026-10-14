package cloud.dopp.solaris

/**
 * Static app identity + native-API constants. The **server URL is NOT here** —
 * it is per-install and lives in [cloud.dopp.solaris.data.ServerStore] (the app
 * is multi-tenant: each user pairs it to their own Solaris server).
 */
object SolarisConfig {
    /**
     * Android application id. It stays `cloud.dopp.solaris` for good: a new id
     * would be a new app. The legacy deep-link scheme equals it.
     */
    const val PACKAGE = "cloud.dopp.solaris"

    /**
     * Proxy-bypassed, device-token-only native API prefix (solarisbay#757).
     * All widget/pairing calls go to `<serverUrl>$NAPI/…`.
     */
    const val NAPI = "/napi"

    /** Path of the authenticated pairing page on the user's server. */
    const val PAIR_PATH = "/pair-device"

    /**
     * Derive the **ServiceBay portal** URL from the paired Solaris [base] (#50).
     *
     * The "Zugang anfordern" (request-access) CTA lives on the ServiceBay portal,
     * which sits on the **parent (apex) domain** — e.g. Solaris `chat.dopp.cloud`
     * → portal `dopp.cloud` — NOT under the Solaris host itself (appending
     * `/portal` to `chat.dopp.cloud` 404s: Solaris ≠ ServiceBay, different hosts).
     *
     * Strips the leftmost subdomain label when the host has ≥3 labels; otherwise
     * uses the host as-is. Scheme defaults to https when [base] omits one; any
     * port and path are dropped (the portal answers on its own host root). Pure /
     * Android-free so it's unit-testable.
     */
    fun portalUrl(base: String): String {
        val t = base.trim().trimEnd('/')
        val hasScheme = "://" in t
        val scheme = if (hasScheme) t.substringBefore("://") else "https"
        val rest = if (hasScheme) t.substringAfter("://") else t
        val host = rest.substringBefore('/').substringBefore(':')
        val labels = host.split('.').filter { it.isNotBlank() }
        val portalHost = if (labels.size >= 3) labels.drop(1).joinToString(".") else host
        return "$scheme://$portalHost"
    }

    /**
     * The ServiceBay **admin** UI URL, derived from the paired Solaris [base] (#45):
     * the apex domain (see [portalUrl]) with an `admin.` prefix — e.g. Solaris
     * `chat.dopp.cloud` → `admin.dopp.cloud`. That is where pending updates are
     * applied, so the update notification's tap lands there (Authelia-gated in the
     * Custom Tab), NOT on the Solaris chat. Pure / Android-free so it's testable.
     */
    fun adminUrl(base: String): String {
        val portal = portalUrl(base)
        val scheme = portal.substringBefore("://")
        val host = portal.substringAfter("://")
        return "$scheme://admin.$host"
    }

    /**
     * Device-token prefixes (#174, laib ADR 0021). Haus moves to the neutral
     * `haus_device_`; `sol_device_` is what every server minted before and stays
     * valid. Matching lives in [cloud.dopp.solaris.data.PairLinks].
     */
    const val TOKEN_PREFIX = "haus_device_"
    const val LEGACY_TOKEN_PREFIX = "sol_device_"

    /**
     * Pairing redirect deep link: `cloud.dopp.haus://pair#token=…&id=…`, or the legacy
     * `cloud.dopp.solaris://pair#token=…&id=…` (#174 — both are accepted).
     * The token rides the fragment so it never hits proxy logs.
     */
    const val PAIR_SCHEME = "cloud.dopp.haus"
    const val LEGACY_PAIR_SCHEME = "cloud.dopp.solaris"
    const val PAIR_HOST = "pair"
    const val PAIR_TOKEN_PARAM = "token"

    /**
     * QR content the server's "connect phone" page encodes (scanned in-app, not
     * launched as an intent): `cloud.dopp.haus://setup?server=<url>&token=<device token>`,
     * or the same under the legacy `cloud.dopp.solaris://` scheme.
     * `server` required, `token` optional (present → instant pair; absent → login).
     */
    const val SETUP_HOST = "setup"
    const val SETUP_SERVER_PARAM = "server"
}
