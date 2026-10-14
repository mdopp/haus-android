package cloud.dopp.solaris.data

import android.net.Uri
import cloud.dopp.solaris.SolarisConfig

/**
 * Recognises what a server hands the app to pair it: the device token, the
 * `pair` redirect deep link and the `setup` QR content.
 *
 * Two generations of names are valid side by side (#174, laib ADR 0021):
 *  - the **neutral** ones Haus moves to — `haus_device_…`, `haus://pair`,
 *    `haus://setup`;
 *  - the **legacy** ones every installed app knows — `sol_device_…`,
 *    `cloud.dopp.solaris://pair`, `cloud.dopp.solaris://setup`.
 * Scheme and prefix are independent: either scheme may carry either prefix.
 * The legacy names stay until no paired phone runs an app without the new ones.
 */
object PairLinks {

    /** Device-token prefixes the app accepts, neutral first. */
    val TOKEN_PREFIXES = listOf(SolarisConfig.TOKEN_PREFIX, SolarisConfig.LEGACY_TOKEN_PREFIX)

    /** Deep-link schemes the app accepts for `pair` and `setup`, neutral first. */
    val SCHEMES = listOf(SolarisConfig.PAIR_SCHEME, SolarisConfig.LEGACY_PAIR_SCHEME)

    /** What a `setup` QR names: the server, and a token when one rode along. */
    data class Setup(val server: String, val token: String?)

    /** True iff [token] starts with a known prefix and carries something after it. */
    fun isDeviceToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return TOKEN_PREFIXES.any { token.startsWith(it) && token.length > it.length }
    }

    private fun ours(uri: Uri, host: String): Boolean {
        val scheme = uri.scheme ?: return false
        return SCHEMES.any { it.equals(scheme, ignoreCase = true) } &&
            host.equals(uri.host, ignoreCase = true)
    }

    /** True iff [uri] is a `pair` deep link in either scheme — ours, valid token or not. */
    fun isPairLink(uri: Uri): Boolean = ours(uri, SolarisConfig.PAIR_HOST)

    /**
     * The device token of a `pair` deep link (`<scheme>://pair#token=…&id=…`), or
     * null when [uri] isn't one or carries no valid token. The token rides the
     * fragment; the query is read as a fallback. Both are parsed by hand — never
     * `getQueryParameter`, which throws on opaque URIs — and nothing here throws.
     */
    fun pairToken(uri: Uri): String? {
        if (!isPairLink(uri)) return null
        val token = runCatching {
            paramFrom(uri.encodedFragment, SolarisConfig.PAIR_TOKEN_PARAM)
                ?: paramFrom(uri.encodedQuery, SolarisConfig.PAIR_TOKEN_PARAM)
        }.getOrNull()
        return token?.takeIf { isDeviceToken(it) }
    }

    /**
     * Parse scanned QR [text] as `<scheme>://setup?server=<url>&token=<token>`.
     * Null when it isn't a setup link or names no server. A token the app
     * doesn't recognise is dropped (then the user logs in to pair instead).
     */
    fun parseSetup(text: String): Setup? {
        val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
        if (!ours(uri, SolarisConfig.SETUP_HOST)) return null
        return runCatching {
            val server = uri.getQueryParameter(SolarisConfig.SETUP_SERVER_PARAM)
            if (server.isNullOrBlank()) return@runCatching null
            val token = uri.getQueryParameter(SolarisConfig.PAIR_TOKEN_PARAM)
            Setup(server, token?.takeIf { isDeviceToken(it) })
        }.getOrNull()
    }

    /** Pull `key=…` out of a raw `k=v&k=v` string (URL fragment or query), decoded. */
    private fun paramFrom(s: String?, key: String): String? {
        if (s.isNullOrBlank()) return null
        val raw = s.split("&")
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=") ?: return null
        val decoded = runCatching { Uri.decode(raw) }.getOrNull() ?: raw
        return decoded.ifBlank { null }
    }
}
