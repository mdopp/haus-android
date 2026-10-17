package cloud.dopp.haus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import cloud.dopp.haus.R
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.ui.HomeActivity
import kotlin.concurrent.thread

/**
 * Energy "Gesamt" widget (#16): a 2×2 kWh totals grid — PV-Erzeugung /
 * Einspeisung / Netzbezug / Batterie geladen — from `/napi/portal/energy`
 * (`totals`). One house picture, no per-instance config; a tap opens the app.
 */
class EnergyTotalsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, it) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        // The live service feeds this widget: with the last one gone it may stop.
        cloud.dopp.haus.realtime.Live.recheck(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?,
    ) = refresh(context, appWidgetId)

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

    private fun scaffold(context: Context, id: Int, note: String?): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_energy_totals)
        v.setOnClickPendingIntent(R.id.t_root, tapPending(context, id))
        v.setOnClickPendingIntent(R.id.t_refresh, refreshPending(context, id))
        for (i in 0..3) {
            v.setTextViewText(LABEL_IDS[i], EnergyStyle.TOTAL_LABELS[i])
            v.setTextViewText(VALUE_IDS[i], note ?: "—")
            v.setTextColor(VALUE_IDS[i], if (note != null) EnergyStyle.NEUTRAL else EnergyStyle.TOTAL_COLORS[i])
        }
        return v
    }

    private fun refresh(context: Context, appWidgetId: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        val paired = ServerStore.isConfigured(context) && TokenStore.isPaired(context)
        if (!paired) {
            mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, context.getString(R.string.totals_pair)))
            return
        }
        mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, context.getString(R.string.totals_loading)))
        val pending = goAsync()
        thread {
            try {
                val energy = try {
                    ApiClient(context.applicationContext).getEnergy()
                } catch (e: Exception) {
                    null
                }
                // the flow's sensors join the watch set: their frames read this widget again
                energy?.let { e -> cloud.dopp.haus.realtime.WatchSet.rememberEnergy(context, e.flow.mapNotNull { it.entityId }) }
                val totals = energy?.let { EnergyStyle.orderedTotals(it.totals) }
                val v = scaffold(context, appWidgetId, null)
                if (totals != null && totals.any { it != null }) {
                    for (i in 0..3) {
                        val t = totals[i]
                        v.setTextViewText(LABEL_IDS[i], t?.label?.ifBlank { EnergyStyle.TOTAL_LABELS[i] } ?: EnergyStyle.TOTAL_LABELS[i])
                        v.setTextViewText(VALUE_IDS[i], EnergyStyle.formatKwh(t?.value))
                        v.setTextColor(VALUE_IDS[i], EnergyStyle.TOTAL_COLORS[i])
                    }
                } else {
                    for (i in 0..3) {
                        v.setTextViewText(VALUE_IDS[i], context.getString(R.string.totals_nodata))
                        v.setTextColor(VALUE_IDS[i], EnergyStyle.NEUTRAL)
                    }
                }
                mgr.updateAppWidget(appWidgetId, v)
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
            } finally {
                pending?.finish()
            }
        }
    }

    private fun tapPending(context: Context, appWidgetId: Int): PendingIntent =
        // Body tap opens Haus's start page (#27).
        HausPages.tap(context, appWidgetId, HausPages.START, WidgetKind.ENERGY_TOTALS)

    /** Broadcast our own [ACTION_REFRESH] to re-fetch this instance (#26). */
    private fun refreshPending(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, EnergyTotalsWidgetProvider::class.java)
            .setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, appWidgetId + 730_000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.haus.widget.TOTALS_REFRESH"
        private val LABEL_IDS = intArrayOf(R.id.t_l0, R.id.t_l1, R.id.t_l2, R.id.t_l3)
        private val VALUE_IDS = intArrayOf(R.id.t_v0, R.id.t_v1, R.id.t_v2, R.id.t_v3)
    }
}
