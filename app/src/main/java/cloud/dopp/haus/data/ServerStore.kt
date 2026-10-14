package cloud.dopp.haus.data

import android.content.Context
import java.net.URI

/**
 * The Haus this app is paired to. Multi-tenant: the URL is chosen by the person
 * (typed, or a scanned QR they confirmed), not hardcoded. Plain prefs — the URL
 * is not a secret (the device token in [TokenStore] is).
 *
 * Only https: every call carries the device token, so an `http://` address is
 * refused here, before anything is stored ([refusesHttp] lets the screen say why).
 */
object ServerStore {
    private const val PREFS = "haus_server"
    private const val KEY = "base_url"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** e.g. `https://haus.example.org` (no trailing slash), or null if unset. */
    fun baseUrl(ctx: Context): String? = p(ctx).getString(KEY, null)

    /** True iff [url] names plain http — refused, with its own message. */
    fun refusesHttp(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)

    /**
     * [url] as the app stores it: https, a host, no trailing slash. A bare host
     * gets `https://`. Null for http, another scheme, or no host.
     */
    fun normalize(url: String): String? {
        var u = url.trim().trimEnd('/')
        if (u.isEmpty() || refusesHttp(u)) return null
        if (!u.startsWith("https://", ignoreCase = true)) {
            if ("://" in u) return null
            u = "https://$u"
        }
        val host = runCatching { URI(u).host }.getOrNull()
        return if (host.isNullOrBlank()) null else u
    }

    /** The host of [url], for the dialogs that ask before pairing. */
    fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

    /** Store [url] when [normalize] takes it; true iff stored. An http address stores nothing. */
    fun setBaseUrl(ctx: Context, url: String): Boolean {
        val u = normalize(url) ?: return false
        p(ctx).edit().putString(KEY, u).apply()
        return true
    }

    fun isConfigured(ctx: Context): Boolean = !baseUrl(ctx).isNullOrBlank()

    fun host(ctx: Context): String? = baseUrl(ctx)?.let(::hostOf)

    fun clear(ctx: Context) = p(ctx).edit().remove(KEY).apply()
}
