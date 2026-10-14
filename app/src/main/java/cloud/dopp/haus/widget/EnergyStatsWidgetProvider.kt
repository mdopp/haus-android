package cloud.dopp.haus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.haus.R
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.Energy
import cloud.dopp.haus.data.EnergyFlow
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.ui.HomeActivity
import kotlin.concurrent.thread

/**
 * Energy "Verlauf" widget (#16): a multi-line PV/Haus/Netz/Akku history chart
 * ([ChartRenderer.multiLine]) from `/napi/portal/entity-history`, with a
 * per-instance 24h ⇄ 7d toggle. The entity ids come from `/napi/portal/energy`
 * (`flow`). The toggle is a stepped button firing [ACTION_TOGGLE] (RemoteViews
 * has no interactive toggle), which flips the persisted [EnergyRange] and
 * re-renders.
 */
class EnergyStatsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?,
    ) = refresh(context, appWidgetId)

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetStore.unbind(context, it) }
        // The live service feeds this widget: with the last one gone it may stop.
        cloud.dopp.haus.realtime.Live.recheck(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        if (intent.action == ACTION_TOGGLE || intent.action == ACTION_REFRESH) {
            cloud.dopp.haus.realtime.Live.ensure(context) // a widget tap: an allowed start
        }
        when (intent.action) {
            ACTION_TOGGLE -> {
                WidgetStore.setRange(context, id, WidgetStore.range(context, id).toggled())
                refresh(context, id)
            }
            ACTION_REFRESH -> refresh(context, id)
        }
    }

    private fun scaffold(context: Context, id: Int, range: EnergyRange, hint: String): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_energy_stats)
        v.setOnClickPendingIntent(R.id.es_root, tapPending(context, id))
        v.setOnClickPendingIntent(R.id.es_refresh, refreshPending(context, id))
        v.setOnClickPendingIntent(R.id.es_toggle, togglePending(context, id))
        v.setTextViewText(R.id.es_title, context.getString(R.string.stats_title))
        v.setTextViewText(R.id.es_toggle, range.label)
        v.setViewVisibility(R.id.es_chart, View.GONE)
        v.setTextViewText(R.id.es_hint, hint)
        return v
    }

    private fun refresh(context: Context, appWidgetId: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        val range = WidgetStore.range(context, appWidgetId)
        val paired = ServerStore.isConfigured(context) && TokenStore.isPaired(context)
        if (!paired) {
            mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, range, context.getString(R.string.stats_pair)))
            return
        }
        mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, range, context.getString(R.string.stats_loading)))
        val pending = goAsync()
        thread {
            try {
                val api = ApiClient(context.applicationContext)
                val energy = try { api.getEnergy() } catch (e: Exception) { null }
                val series = if (energy != null) fetchSeries(api, energy, range) else emptyList()
                val v = scaffold(context, appWidgetId, range, "")
                if (series.isNotEmpty()) {
                    v.setImageViewBitmap(R.id.es_chart, ChartRenderer.multiLine(640, 300, series))
                    v.setViewVisibility(R.id.es_chart, View.VISIBLE)
                    v.setTextViewText(R.id.es_hint, context.getString(R.string.stats_range, range.label))
                } else {
                    v.setViewVisibility(R.id.es_chart, View.GONE)
                    v.setTextViewText(R.id.es_hint, context.getString(R.string.stats_nodata))
                }
                mgr.updateAppWidget(appWidgetId, v)
            } catch (t: Throwable) {
                // Render/transaction failure (e.g. oversized chart bitmap) → clean fallback (#32).
                WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
            } finally {
                pending?.finish()
            }
        }
    }

    /** One line per leg with data, colored by [EnergyStyle.seriesColor]. */
    private fun fetchSeries(api: ApiClient, energy: Energy, range: EnergyRange): List<ChartRenderer.Series> {
        val legs = EnergyStyle.orderedLegs(energy.pv, energy.house, energy.grid, energy.battery)
        val out = mutableListOf<ChartRenderer.Series>()
        for (f in legs) {
            val eid = f?.entityId ?: continue
            val points = try { api.getEntityHistory(eid, range.history) } catch (e: Exception) { emptyList() }
            if (points.size >= 2) out.add(ChartRenderer.Series(points, EnergyStyle.seriesColor(f.sense)))
        }
        return out
    }

    private fun tapPending(context: Context, appWidgetId: Int): PendingIntent =
        // Body tap opens Haus's start page (#27).
        HausPages.tap(context, appWidgetId, HausPages.START)

    /** Broadcast our own [ACTION_REFRESH] to re-fetch this instance (#26). */
    private fun refreshPending(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, EnergyStatsWidgetProvider::class.java)
            .setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, appWidgetId + 730_000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun togglePending(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, EnergyStatsWidgetProvider::class.java)
            .setAction(ACTION_TOGGLE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.haus.widget.STATS_REFRESH"
        const val ACTION_TOGGLE = "cloud.dopp.haus.widget.STATS_TOGGLE"
    }
}
