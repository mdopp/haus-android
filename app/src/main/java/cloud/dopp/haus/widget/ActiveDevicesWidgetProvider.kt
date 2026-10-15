package cloud.dopp.haus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.Card
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import kotlin.concurrent.thread

/**
 * The household **active-devices overview** widget (#28): size-adaptive: small =
 * a compact count ("3 an"); large = a bounded list of the currently on/open
 * devices (icon + name + state), styled like the picker rows. Not bound to a
 * single entity — it reads the whole roster and filters to the active ones via
 * [ApiClient.listActive]. Tap → the household start page with every pinned device
 * ([HausPages.START], #167); a row opens its device's own page (#178); a
 * discreet refresh icon re-fetches. Haus's `active_set` frame tells the live
 * service when the list changed.
 */
class ActiveDevicesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?,
    ) = refresh(context, appWidgetId)

    override fun onDeleted(context: Context, ids: IntArray) {
        // The live service feeds this widget: with the last one gone it may stop.
        cloud.dopp.haus.realtime.Live.recheck(context)
    }

    /** The first one placed: the watch says `active` now, so Haus sends the active set (#178). */
    override fun onEnabled(context: Context) = cloud.dopp.haus.realtime.Live.postWatch(context)

    /** The last one gone: the watch stops asking for it. */
    override fun onDisabled(context: Context) = cloud.dopp.haus.realtime.Live.postWatch(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            cloud.dopp.haus.realtime.Live.ensure(context) // a widget tap: an allowed start
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) refresh(context, id)
        }
    }

    private fun refresh(context: Context, appWidgetId: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        val tier = tierFor(context, appWidgetId)

        // Immediate loading scaffold, then async fetch. A render failure must yield
        // a clean fallback, not an uncaught throw ("kann nicht geladen werden", #32).
        try {
            mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, tier, null))
        } catch (t: Throwable) {
            WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
        }

        val paired = ServerStore.isConfigured(context) && TokenStore.isPaired(context)
        if (!paired) {
            // Not paired: render an empty (0) state; tap bounces to onboarding.
            try {
                WidgetStore.setActiveCacheJson(context, ActiveCache.encode(emptyList()))
                mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, tier, emptyList()))
                if (tier == ActiveDevicesRender.Tier.LARGE) {
                    mgr.notifyAppWidgetViewDataChanged(appWidgetId, R_av_list)
                }
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
            }
            return
        }

        val pending = goAsync()
        thread {
            try {
                val active = try {
                    ApiClient(context.applicationContext).listActive()
                } catch (e: Exception) {
                    null
                }
                // Persist the fresh list so the collection factory renders it (stale→fresh),
                // then tell the ListView to reload. A failed read is no news (#120): the
                // list last read stays, and the live service compares active_set to it.
                if (active != null) {
                    WidgetStore.setActiveCacheJson(context, ActiveCache.encode(active))
                }
                val shown = active ?: WidgetStore.activeCacheJson(context)?.let(ActiveCache::decode)
                mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, tier, shown))
                if (tier == ActiveDevicesRender.Tier.LARGE) {
                    mgr.notifyAppWidgetViewDataChanged(appWidgetId, R_av_list)
                }
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
            } finally {
                pending?.finish()
            }
        }
    }

    private fun tierFor(context: Context, appWidgetId: Int): ActiveDevicesRender.Tier =
        ActiveDevicesRender.sizeTier(minWidth(context, appWidgetId), minHeight(context, appWidgetId))

    private fun minWidth(context: Context, appWidgetId: Int): Int =
        AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)

    private fun minHeight(context: Context, appWidgetId: Int): Int =
        AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)

    private fun scaffold(
        context: Context, appWidgetId: Int, tier: ActiveDevicesRender.Tier, active: List<Card>?,
    ): RemoteViews {
        val v = when (tier) {
            ActiveDevicesRender.Tier.LARGE -> largeCollection(context, appWidgetId, active)
            ActiveDevicesRender.Tier.SMALL -> ActiveDevicesRender.small(context, active)
        }
        v.setOnClickPendingIntent(
            // The device page, not the chat (#167). This tile is about devices;
            // landing in an empty conversation answered nothing.
            R_av_root, HausPages.tap(context, appWidgetId, HausPages.START),
        )
        v.setOnClickPendingIntent(R_av_refresh, refreshPending(context, appWidgetId))
        return v
    }

    /**
     * Build the large tier as a scrollable collection (#28): header + a ListView
     * bound to [ActiveDevicesRemoteViewsService] via setRemoteAdapter, an empty view,
     * and a PendingIntentTemplate so per-row fill-in taps open the row's page. The service
     * intent carries the appWidgetId (unique data Uri) so distinct instances don't
     * share an adapter.
     */
    private fun largeCollection(context: Context, appWidgetId: Int, active: List<Card>?): RemoteViews {
        val v = ActiveDevicesRender.large(context, active)

        val svc = Intent(context, ActiveDevicesRemoteViewsService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .setData(Uri.parse("haus://active/$appWidgetId"))
        v.setRemoteAdapter(R_av_list, svc)
        v.setEmptyView(R_av_list, R_av_empty)

        // Template merged with each row's fill-in (set in the factory): the row's page.
        v.setPendingIntentTemplate(R_av_list, ToolActionActivity.template(context, 300_000 + appWidgetId))
        return v
    }

    private fun refreshPending(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, ActiveDevicesWidgetProvider::class.java)
            .setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, 200_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.haus.widget.ACTIVE_REFRESH"

        // Shared ids across both layouts (small + large use the same root/refresh ids).
        private val R_av_root = cloud.dopp.haus.R.id.av_root
        private val R_av_refresh = cloud.dopp.haus.R.id.av_refresh

        // Large-tier collection ids.
        private val R_av_list = cloud.dopp.haus.R.id.av_list
        private val R_av_empty = cloud.dopp.haus.R.id.av_empty
    }
}
