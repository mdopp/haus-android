package cloud.dopp.haus.realtime

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.widget.DeviceWidgetProvider
import cloud.dopp.haus.widget.WidgetStore
import kotlin.concurrent.thread

/**
 * The native **watch-set** (#48 Option B): the set of HA
 * entities the device-widgets are bound to, registered with the server so its
 * `ha_watch` emits `card_state` for exactly those entities over the existing
 * `/napi/portal/events` SSE — **without** touching the user's web favorites.
 *
 * Only the per-entity [DeviceWidgetProvider] instances bind a single HA entity;
 * the camera/voice/active/energy widgets don't, so they're left out of the set.
 *
 * Everything here is **best-effort** and guarded: it only posts when paired +
 * configured, runs off the caller's thread, and swallows any failure — a failed
 * watch-set post must never affect the widgets or the realtime service. It does
 * nothing useful until the old server is live (404 → false, ignored).
 */
object WatchSet {

    /**
     * The current device-widget entity set: the bound entity of every live
     * [DeviceWidgetProvider] instance, non-null/blank, de-duplicated.
     */
    fun currentEntities(ctx: Context): List<String> {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val ids = mgr.getAppWidgetIds(ComponentName(app, DeviceWidgetProvider::class.java))
        return ids.toList()
            .mapNotNull { id -> WidgetStore.entityId(app, id) }
            .filter { eid -> eid.isNotBlank() }
            .distinct()
    }

    /** Paired + configured — the only state in which a post can succeed. */
    private fun eligible(ctx: Context): Boolean =
        ServerStore.isConfigured(ctx) && TokenStore.isPaired(ctx)

    /**
     * Post the current widget entity set to the server on a **background thread**
     * (never on the main / SSE-callback thread). No-op when not paired. Any
     * failure is swallowed. Call after connect / bind / unbind / periodically.
     */
    fun postCurrentAsync(ctx: Context) {
        val app = ctx.applicationContext
        if (!eligible(app)) return
        thread {
            runCatching {
                val entities = currentEntities(app)
                ApiClient(app).postWatch(entities)
            }
        }
    }
}
