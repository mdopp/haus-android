package cloud.dopp.haus.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import cloud.dopp.haus.data.ApiClient

/**
 * An executed action, read as a **measurement of the connection** (#111).
 *
 * The device test of v2.33.0 in flight mode showed the gap: the tap raised its
 * toast, but the tiles did not change, because [Staleness.THRESHOLD_MS] is 90
 * minutes and a minute of flight mode cannot reach it. The threshold is right —
 * a tile only refreshes every 30 min, and a shorter window would cry wolf — so
 * the answer is not a shorter threshold but a **second input**: the action the
 * user just triggered is the one measurement that happens exactly when they are
 * looking, and it costs no extra request.
 *
 * - It **failed to reach** the server → we know *now* that it is unreachable.
 *   The tile is marked at once, no waiting. This is not a false alarm: the user
 *   has just experienced first-hand that nothing happened.
 * - It **reached** the server → we have heard from it this second. That is a
 *   fresh fetch time, and it clears an existing mark even though no regular
 *   refresh ran.
 *
 * The distinction that keeps this honest is **who answered**. A refusal the
 * server *authored* — the 403 confirm gate, a `forbidden` admin action, an
 * `unknown_action` 404 — proves it is alive; only silence (a transport error) or
 * a server that cannot serve its own endpoint (the outage's 500s, which
 * [ApiClient.call] collapses to `false`) is [Reach.UNREACHABLE]. And an action
 * that never left the phone — nothing paired, nothing configured — measured
 * nothing at all: [Reach.NOTHING] writes no verdict rather than a wrong one.
 *
 * The classifiers are pure; [record] is the only line that touches storage.
 *
 * Only the **device tile** is probed, because it is the only surface that renders
 * a freshness mark. And only a **user-triggered action** may write here: a failing
 * background refresh must never mark anything, or the 90-minute threshold — the
 * whole defence against crying wolf — would be back-doored on the first bad
 * network handover.
 */
object ActionProbe {

    /** What one executed action measured about the server. */
    enum class Reach {
        /** The server answered — with a result, or with a refusal of its own. */
        REACHED,

        /** Nothing came back, or the server could not serve the call at all. */
        UNREACHABLE,

        /** Nothing was measured: the call never left the phone. */
        NOTHING,
    }

    /** A plain [ApiClient.call] result: `false` is a non-2xx that was not the 403 gate. */
    fun ofCall(ok: Boolean): Reach = if (ok) Reach.REACHED else Reach.UNREACHABLE

    /**
     * What a throw from a call attempt measured. [ApiClient.SensitiveException] is
     * the server's own 403 — it answered, so the connection is fine and the tile
     * must not be marked. A missing server or token means the request was never
     * made; that is a setup state, not an outage.
     */
    fun ofError(e: Throwable): Reach = when (e) {
        is ApiClient.SensitiveException -> Reach.REACHED
        is ApiClient.NotConfiguredException, is ApiClient.NotPairedException -> Reach.NOTHING
        else -> Reach.UNREACHABLE
    }

    /** [ofCall] / [ofError] over the two ways one `call` can end. */
    fun of(result: Result<Boolean>): Reach =
        result.fold(onSuccess = { ofCall(it) }, onFailure = { ofError(it) })

    /**
     * Write what [reach] measured to the tile behind [appWidgetId], so the next
     * render says it. A tap that belongs to no tile — an app-icon shortcut for a
     * device that is not on the home screen (#100) — has nothing to mark, and
     * [Reach.NOTHING] writes nothing by definition.
     */
    fun record(
        ctx: Context,
        appWidgetId: Int,
        reach: Reach,
        atMs: Long = System.currentTimeMillis(),
    ) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        when (reach) {
            Reach.REACHED -> WidgetCache.noteFetch(ctx, appWidgetId, atMs)
            Reach.UNREACHABLE -> WidgetCache.noteUnreachable(ctx, appWidgetId, atMs)
            Reach.NOTHING -> Unit
        }
    }
}
