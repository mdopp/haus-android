package cloud.dopp.haus.data

import android.content.Context

/**
 * Pairing with Haus, the part that decides what may be stored (#176 review).
 *
 * - A pair link (`cloud.dopp.haus://pair#token=…`) can be fired by any page or
 *   app. It counts only while a pairing the app started is [pending]: the
 *   person entered or confirmed an address and the app opened `/pair-device`
 *   there, less than [PENDING_TTL_MS] ago. Haus's redirect carries no nonce
 *   of the app's, so the pending server is what ties the link to it.
 * - The screen asks before anything is stored, naming the host.
 * - A token is stored only once Haus answers `GET /napi/whoami` for it
 *   ([check]), and only as the token of the server it was checked against
 *   ([store]).
 */
object Pairing {

    /** How long a started pairing waits for its pair link. */
    const val PENDING_TTL_MS = 15L * 60L * 1000L

    private const val PREFS = "haus_pairing"
    private const val KEY_SERVER = "server"
    private const val KEY_AT = "started_at"

    enum class Outcome { PAIRED, REFUSED, UNREACHABLE, NOT_SAVED }

    /**
     * Asks the Haus at a server whose a token is: the HTTP status, 200 only
     * with a uid, 0 when nothing came back ([ApiClient.whoami]). Replaced in tests.
     */
    @Volatile
    var whoami: (server: String, token: String) -> Int = { server, token -> ApiClient.whoami(server, token) }

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The app opens `/pair-device` at [server] now: its pair link may come back. */
    fun start(ctx: Context, server: String, now: Long = System.currentTimeMillis()) {
        p(ctx).edit().putString(KEY_SERVER, server).putLong(KEY_AT, now).apply()
    }

    /** The server a pairing waits on, or null when none was started or it is stale. */
    fun pending(ctx: Context, now: Long = System.currentTimeMillis()): String? {
        val server = p(ctx).getString(KEY_SERVER, null) ?: return null
        val at = p(ctx).getLong(KEY_AT, 0L)
        return server.takeIf { now - at in 0..PENDING_TTL_MS }
    }

    /** No pairing waits any more: declined, done, or unpaired. */
    fun cancel(ctx: Context) {
        p(ctx).edit().clear().apply()
    }

    /** Blocking, off the main thread: does the Haus at [server] know [token]? */
    fun check(server: String, token: String): Outcome =
        when (runCatching { whoami(server, token) }.getOrDefault(0)) {
            200 -> Outcome.PAIRED
            0 -> Outcome.UNREACHABLE
            else -> Outcome.REFUSED
        }

    /**
     * Store [token] as the device token for [server], which [check] accepted.
     * The token first: if it cannot be saved, nothing changes ([Outcome.NOT_SAVED]).
     * Another server than the stored one replaces it, and its persona with it.
     */
    fun store(ctx: Context, server: String, token: String): Outcome {
        if (!PairLinks.isDeviceToken(token)) return Outcome.REFUSED
        if (!TokenStore.save(ctx, token)) return Outcome.NOT_SAVED
        if (ServerStore.baseUrl(ctx) != server) {
            PersonaStore.clear(ctx)
            ServerStore.setBaseUrl(ctx, server)
        }
        cancel(ctx)
        return Outcome.PAIRED
    }
}
