package cloud.dopp.haus

/**
 * The names the app and Haus share (laib ADR 0022 §1; Haus's `internal/app`).
 * One set and no other: the old app's names are not accepted anywhere (§4).
 *
 * The **server is not here** — it is per install and lives in
 * [cloud.dopp.haus.data.ServerStore]: the person enters Haus's address.
 */
object HausConfig {
    /** The deep-link scheme, named after the domain: a bare `haus://` could be taken by any app. */
    const val SCHEME = "cloud.dopp.haus"

    /** Every device token Haus mints starts with this; the app checks it before storing one. */
    const val TOKEN_PREFIX = "haus_device_"

    /**
     * Haus's pairing page (behind Authelia): GET asks, POST mints a device and
     * redirects to `cloud.dopp.haus://pair#token=…&id=…`.
     */
    const val PAIR_PATH = "/pair-device"

    /** `cloud.dopp.haus://pair#token=…&id=…` — the token rides the fragment, never a proxy log. */
    const val PAIR_HOST = "pair"
    const val PAIR_TOKEN_PARAM = "token"

    /**
     * What the QR on Haus's device page encodes (scanned in-app, never an intent
     * filter): `cloud.dopp.haus://setup?server=<url>&token=<device token>`.
     */
    const val SETUP_HOST = "setup"
    const val SETUP_SERVER_PARAM = "server"
}
