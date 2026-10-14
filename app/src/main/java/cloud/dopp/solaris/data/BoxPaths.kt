package cloud.dopp.solaris.data

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a server serves the Box keys (`home`, `services`, `approvals`,
 * `upgrades`) under `/napi` (#174, laib ADR 0021).
 *
 * The neutral name is `/napi/box/…`; `/napi/servicebay/…` is the legacy name
 * every server so far speaks (solarisbay, and Haus via `internal/legacy`). The
 * app asks the new name first and falls back to the old one on a 404. It
 * remembers per server which name answered, so a server that knows only one of
 * them costs one extra request once — not one per widget refresh.
 */
object BoxPaths {
    const val BOX = "/box"
    const val LEGACY = "/servicebay"

    /**
     * One GET as the fallback sees it: the HTTP [status] (0 = no answer at all,
     * e.g. no network or no token) and the parsed [value], null when there was no
     * usable body.
     */
    class Answer<out T>(val status: Int, val value: T?)

    /** Which prefix answered for a server. */
    interface Memo {
        fun get(server: String): String?
        fun put(server: String, prefix: String)
    }

    /** The order to ask in: the remembered prefix first, the other one after it. */
    fun order(remembered: String?): List<String> =
        if (remembered == LEGACY) listOf(LEGACY, BOX) else listOf(BOX, LEGACY)

    /**
     * Ask [server] via [get] (which receives [BOX] or [LEGACY]) in [order].
     *
     * - a 2xx with a value → that value; the prefix is remembered;
     * - a 404, or a 2xx without a usable body → that name isn't served here,
     *   ask the other one;
     * - anything else (401, 403, 5xx, no answer) says nothing about the name →
     *   null at once, no fallback, the memory untouched.
     */
    fun <T> fetch(server: String, memo: Memo, get: (prefix: String) -> Answer<T>): T? {
        val remembered = memo.get(server)
        for (prefix in order(remembered)) {
            val a = get(prefix)
            val value = a.value
            if (a.status in 200..299 && value != null) {
                if (remembered != prefix) memo.put(server, prefix)
                return value
            }
            if (a.status == 404 || a.status in 200..299) continue
            return null
        }
        return null
    }

    /**
     * The memory on disk (plain prefs — a path name is no secret), with an
     * in-process cache in front. Widgets refresh in short-lived processes, so the
     * memory has to outlive them.
     */
    fun memo(ctx: Context): StoredMemo = StoredMemo(ctx.applicationContext)

    class StoredMemo internal constructor(private val ctx: Context) : Memo {
        private fun p() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun get(server: String): String? =
            cache[server] ?: p().getString(server, null)?.also { cache[server] = it }

        override fun put(server: String, prefix: String) {
            cache[server] = prefix
            p().edit().putString(server, prefix).apply()
        }

        fun clear() {
            cache.clear()
            p().edit().clear().apply()
        }
    }

    private const val PREFS = "solaris_box_paths"
    private val cache = ConcurrentHashMap<String, String>()
}
