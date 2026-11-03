package cloud.dopp.homes

/**
 * The names the app and Homes share (laib ADR 0022 §1; Homes' `internal/app`).
 * One set and no other: the old app's names are not accepted anywhere (§4).
 *
 * The **server is not here** — it is per install and lives in
 * [cloud.dopp.homes.data.ServerStore]: the person enters Homes' address.
 */
object HomesConfig {
    /** The deep-link scheme, named after the domain: a bare `homes://` could be taken by any app. */
    const val SCHEME = "cloud.dopp.homes"

    /** Every device token Homes mints starts with this; the app checks it before storing one. */
    const val TOKEN_PREFIX = "homes_device_"

    /**
     * Homes' pairing page (behind Authelia): GET asks, POST mints a device and
     * redirects to `cloud.dopp.homes://pair#token=…&id=…`.
     */
    const val PAIR_PATH = "/pair-device"

    /** `cloud.dopp.homes://pair#token=…&id=…` — the token rides the fragment, never a proxy log. */
    const val PAIR_HOST = "pair"
    const val PAIR_TOKEN_PARAM = "token"

    /**
     * What the QR on Homes' device page encodes (scanned in-app, never an intent
     * filter): `cloud.dopp.homes://setup?server=<url>&token=<device token>`.
     */
    const val SETUP_HOST = "setup"
    const val SETUP_SERVER_PARAM = "server"
}
