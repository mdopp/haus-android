package cloud.dopp.haus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.os.Bundle
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.Card
import cloud.dopp.haus.data.StateEpochGuard
import kotlin.concurrent.thread

/**
 * The universal, per-instance device widget. Each instance is bound (via
 * [WidgetConfigActivity]) to one HA entity; it renders that entity's live state
 * and a tap runs the appropriate service (toggle / open-close, garage confirmed
 * by [WidgetActionActivity]).
 */
class DeviceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        refresh(context, appWidgetId)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach {
            WidgetStore.unbind(context, it)
            WidgetCache.clear(context, it) // drop the last-good render cache (#46)
        }
        // The bound set shrank → refresh the watch set (#48), let the live service
        // look again (no fed widget left, no service), and drop the removed devices
        // from the app-icon long-press menu (#97).
        cloud.dopp.haus.realtime.WatchSet.postCurrentAsync(context)
        cloud.dopp.haus.realtime.Live.recheck(context)
        AppShortcuts.refreshAsync(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) refresh(context, id)
        }
    }

    private fun refresh(context: Context, appWidgetId: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        // A render/data failure must yield a clean fallback, not an uncaught throw
        // that leaves Android showing "Widget kann nicht geladen werden" (#32).
        val entityId = try {
            WidgetStore.entityId(context, appWidgetId)
        } catch (t: Throwable) {
            WidgetFallback.show(context, appWidgetId, safeRefreshTap(context, appWidgetId))
            return
        }
        if (entityId == null) {
            // Not configured (or orphaned/unbound after a reinstall) — tapping opens
            // the picker; the state field says "einrichten" instead of a cryptic "…".
            try {
                mgr.updateAppWidget(
                    appWidgetId,
                    WidgetRender.build(
                        context, appWidgetId, null, "—", "",
                        configPending(context, appWidgetId), WidgetRender.Load.UNCONFIGURED,
                    ),
                )
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, safeRefreshTap(context, appWidgetId))
            }
            return
        }
        val domain = WidgetStore.domain(context, appWidgetId)
        // Immediate render from the last-good cache (#46): show the previous state
        // instantly — even after a reboot/process death, when StateEpochGuard's
        // in-memory memory is gone — instead of a bare "lädt…". Then async fetch.
        val cached = WidgetCache.getCard(context, appWidgetId)
        try {
            mgr.updateAppWidget(
                appWidgetId,
                WidgetRender.build(
                    context, appWidgetId, cached, WidgetStore.name(context, appWidgetId),
                    domain, tapPending(context, appWidgetId, cached),
                    if (cached != null) WidgetRender.Load.LOADED else WidgetRender.Load.LOADING,
                ),
            )
        } catch (t: Throwable) {
            WidgetFallback.show(context, appWidgetId, safeRefreshTap(context, appWidgetId))
        }
        val pending = goAsync()
        thread {
            try {
                val api = ApiClient(context.applicationContext)
                // Reliable first load (#58): retry a failed/empty fetch a few times
                // (transient net / token warm-up / cold start) instead of getting
                // stuck on the placeholder. Bounded so we stay inside goAsync's window.
                var card: Card? = null
                var attempt = 0
                while (card == null && attempt < 3) {
                    card = try { api.getCard(entityId) } catch (e: Exception) { null }
                    if (card == null) {
                        attempt++
                        if (attempt < 3) try { Thread.sleep(1200) } catch (ie: InterruptedException) { break }
                    }
                }
                // Order the fetched snapshot against any newer state already
                // applied via the realtime push: a post-toggle re-fetch can return
                // a state older than a card_state that landed meanwhile, so render
                // the guard's winner (the fetched card if fresh, else the newer
                // remembered one) — never a stale overwrite (multi-device race).
                val resolved = card?.let { StateEpochGuard.resolve(it) }
                // On success cache the fresh card; on failure fall back to the
                // last known one (#46/#120) so a transient net/token hiccup never
                // flips the widget to "↻ tippen" — and never repaints a lit lamp
                // as "aus". The cache is re-read here rather than reusing the
                // snapshot taken before the fetch: a realtime push may have landed
                // meanwhile, and the newer of the two is the one to keep.
                val drawn = drawn(resolved, WidgetCache.getCard(context, appWidgetId) ?: cached)
                if (resolved != null) WidgetCache.putCard(context, appWidgetId, resolved)
                mgr.updateAppWidget(
                    appWidgetId,
                    WidgetRender.build(
                        context, appWidgetId, drawn.card, WidgetStore.name(context, appWidgetId), domain,
                        tapPending(context, appWidgetId, drawn.card), drawn.load,
                    ),
                )
            } catch (t: Throwable) {
                // Any render/transaction failure (e.g. oversized bitmap) → clean fallback.
                WidgetFallback.show(context, appWidgetId, safeRefreshTap(context, appWidgetId))
            } finally {
                pending?.finish()
            }
        }
    }

    /** A tap [PendingIntent] that re-runs this instance's refresh (fallback retry). */
    private fun safeRefreshTap(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, DeviceWidgetProvider::class.java)
            .setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, 900_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }


    private fun configPending(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, 100_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.haus.widget.REFRESH"

        /** What a refresh ends up drawing: a card (possibly the old one) and why. */
        data class Drawn(val card: Card?, val load: WidgetRender.Load)

        /**
         * Which card a finished refresh renders (#120).
         *
         * [fresh] is what this fetch brought back, [cached] the last one that ever
         * landed. The rule the flight-mode report is about: a failed fetch is **no
         * news about the device**, so the tile keeps drawing the last known card —
         * marked as stale/unreachable by [Staleness], never redrawn as if the
         * state had been read and found "aus". Only a tile that has *never* loaded
         * has nothing to draw: that state is genuinely unknown
         * ([WidgetRender.Load.FAILED] → "↻ tippen" and the neutral accent), and
         * saying so is honest where painting "aus" would not be.
         *
         * Pure → JVM-testable.
         */
        fun drawn(fresh: Card?, cached: Card?): Drawn {
            val card = fresh ?: cached
            return Drawn(card, if (card != null) WidgetRender.Load.LOADED else WidgetRender.Load.FAILED)
        }

        /** The name/body tap: the device's own page on Haus, or `/start` while none is known (#178). */
        private fun tapPending(context: Context, appWidgetId: Int, card: Card?): PendingIntent =
            HausPages.tap(context, appWidgetId, HausPages.item(card?.page), WidgetKind.DEVICE)

        /** Ask a bound instance to re-fetch and re-render (explicit self-broadcast). */
        fun requestRefresh(context: Context, appWidgetId: Int) {
            val i = Intent(context, DeviceWidgetProvider::class.java)
                .setAction(ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            context.sendBroadcast(i)
        }

        /**
         * Render one bound instance directly from an already-known [Card] — the
         * realtime `card_state` push path (#48): no re-fetch, we already have the
         * fresh state. Mirrors the async branch of [refresh] but skips the network.
         * Any failure falls back cleanly so a bad push never breaks the widget.
         */
        fun renderCard(context: Context, appWidgetId: Int, card: Card) {
            val mgr = AppWidgetManager.getInstance(context)
            val entityId = WidgetStore.entityId(context, appWidgetId) ?: return
            val tap = tapPending(context, appWidgetId, card)
            val domain = WidgetStore.domain(context, appWidgetId)
            try {
                // Persist the pushed state so a later failed poll keeps it (#46) —
                // *before* rendering, because that write is also what dates the
                // tile (#111): a push IS hearing from the server, and a tile drawn
                // from it must not be judged by the previous fetch's age.
                WidgetCache.putCard(context, appWidgetId, card)
                mgr.updateAppWidget(
                    appWidgetId,
                    WidgetRender.build(
                        context, appWidgetId, card,
                        WidgetStore.name(context, appWidgetId), domain, tap,
                    ),
                )
            } catch (t: Throwable) {
                requestRefresh(context, appWidgetId)
            }
        }

        /**
         * Wake every device-widget bound to [entityId] with the pushed [card] (#48).
         * Iterates this provider's live instances and renders the matching ones from
         * the payload — no re-fetch. Returns the count updated.
         */
        fun wakeEntity(context: Context, entityId: String, card: Card): Int {
            // Reject a stale/out-of-order push: if a newer state was already
            // applied for this entity, resolve() returns that (a different object)
            // and we skip the re-render that would flip the widget back (#race).
            if (StateEpochGuard.resolve(card) !== card) return 0
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, DeviceWidgetProvider::class.java))
            var n = 0
            for (id in ids) {
                if (WidgetStore.entityId(context, id) == entityId) {
                    renderCard(context, id, card)
                    n++
                }
            }
            return n
        }
    }
}
