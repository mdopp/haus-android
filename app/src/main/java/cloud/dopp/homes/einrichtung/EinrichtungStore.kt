package cloud.dopp.homes.einrichtung

import android.content.Context
import androidx.core.content.edit

/**
 * The last answer of `/napi/einrichtung`, kept so the start screen and the
 * launcher's shortcuts work from it offline. None kept — never read, or an
 * older Homes without the path (404) — is no [Einrichtung]: the sections and
 * the shortcuts that need it stay away, quietly.
 */
object EinrichtungStore {
    private const val PREFS = "homes_einrichtung"
    private const val KEY_BODY = "body"
    private const val KEY_GEFRAGT = "gefragt_ms"

    /** At most one read every five minutes, unless asked for at once. */
    const val ABSTAND_MS = 5 * 60_000L

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The body parsed last, so a screen that draws often parses once. */
    @Volatile
    private var parsed: Pair<String, Einrichtung?>? = null

    fun einrichtung(ctx: Context): Einrichtung? {
        val body = p(ctx).getString(KEY_BODY, null) ?: return null
        parsed?.let { (b, e) -> if (b == body) return e }
        return Einrichtung.lesen(body).also { parsed = body to it }
    }

    /** Keep a new answer; true when it says something else than the kept one. Not an answer: the old one stays. */
    @Synchronized
    fun neu(ctx: Context, body: String): Boolean {
        if (Einrichtung.lesen(body) == null) return false
        val old = p(ctx).getString(KEY_BODY, null)
        p(ctx).edit { putString(KEY_BODY, body) }
        return old != body
    }

    /** An older Homes without the path: forget; true when something was kept. */
    @Synchronized
    fun fehlt(ctx: Context): Boolean {
        val had = p(ctx).contains(KEY_BODY)
        p(ctx).edit { remove(KEY_BODY) }
        return had
    }

    /** Is a read due at [now] (ms)? A due read is marked asked, so two screens at once ask once. */
    @Synchronized
    fun faellig(ctx: Context, now: Long, immer: Boolean): Boolean {
        val last = p(ctx).getLong(KEY_GEFRAGT, 0L)
        if (!immer && now >= last && now - last < ABSTAND_MS) return false
        p(ctx).edit { putLong(KEY_GEFRAGT, now) }
        return true
    }

    /** Unpaired: nothing kept, the next Homes is asked afresh. */
    fun clear(ctx: Context) {
        p(ctx).edit { clear() }
    }
}
