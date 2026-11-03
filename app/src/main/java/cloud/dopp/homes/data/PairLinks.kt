package cloud.dopp.homes.data

import android.net.Uri
import cloud.dopp.homes.HomesConfig

/**
 * Recognises what Homes hands the app to pair it: the device token, the `pair`
 * redirect deep link and the `setup` QR content — under the one scheme
 * [HomesConfig.SCHEME] and the one prefix [HomesConfig.TOKEN_PREFIX] (laib ADR
 * 0022). Nothing here throws.
 */
object PairLinks {

    /** What a `setup` QR names: the server, and a token when one rode along. */
    data class Setup(val server: String, val token: String?)

    /** True iff [token] starts with the prefix and carries something after it. */
    fun isDeviceToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val p = HomesConfig.TOKEN_PREFIX
        return token.startsWith(p) && token.length > p.length
    }

    private fun ours(uri: Uri, host: String): Boolean =
        HomesConfig.SCHEME.equals(uri.scheme, ignoreCase = true) && host.equals(uri.host, ignoreCase = true)

    /** True iff [uri] is a `pair` deep link — valid token or not. */
    fun isPairLink(uri: Uri): Boolean = ours(uri, HomesConfig.PAIR_HOST)

    /**
     * The device token of `cloud.dopp.homes://pair#token=…&id=…`, or null. The
     * token rides the fragment; the query is read as a fallback. Both are parsed
     * by hand — `getQueryParameter` throws on opaque URIs.
     */
    fun pairToken(uri: Uri): String? {
        if (!isPairLink(uri)) return null
        val token = runCatching {
            paramFrom(uri.encodedFragment, HomesConfig.PAIR_TOKEN_PARAM)
                ?: paramFrom(uri.encodedQuery, HomesConfig.PAIR_TOKEN_PARAM)
        }.getOrNull()
        return token?.takeIf { isDeviceToken(it) }
    }

    /**
     * Parse scanned QR [text] as `cloud.dopp.homes://setup?server=<url>&token=<token>`.
     * Null when it isn't one or names no server. A token that isn't Homes' is
     * dropped (the person then pairs through /pair-device instead).
     */
    fun parseSetup(text: String): Setup? {
        val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
        if (!ours(uri, HomesConfig.SETUP_HOST)) return null
        return runCatching {
            val server = uri.getQueryParameter(HomesConfig.SETUP_SERVER_PARAM)
            if (server.isNullOrBlank()) return@runCatching null
            val token = uri.getQueryParameter(HomesConfig.PAIR_TOKEN_PARAM)
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
