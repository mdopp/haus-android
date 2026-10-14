package cloud.dopp.haus.data

import android.content.Context
import android.net.Uri

/**
 * The Haus this app is paired to. Multi-tenant: the URL is chosen by
 * the user at onboarding (QR/entry), not hardcoded. Plain prefs — the URL is not
 * a secret (the device token in [TokenStore] is).
 */
object ServerStore {
    private const val PREFS = "haus_server"
    private const val KEY = "base_url"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** e.g. `https://haus.example.org` (no trailing slash), or null if unset. */
    fun baseUrl(ctx: Context): String? = p(ctx).getString(KEY, null)

    fun setBaseUrl(ctx: Context, url: String) {
        var u = url.trim().trimEnd('/')
        if (u.isNotEmpty() && !u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            u = "https://$u"
        }
        p(ctx).edit().putString(KEY, u).apply()
    }

    fun isConfigured(ctx: Context): Boolean = !baseUrl(ctx).isNullOrBlank()

    fun host(ctx: Context): String? =
        baseUrl(ctx)?.let { runCatching { Uri.parse(it).host }.getOrNull() }

    fun clear(ctx: Context) = p(ctx).edit().remove(KEY).apply()
}
