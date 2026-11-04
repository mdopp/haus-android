package cloud.dopp.homes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.TokenStore

/**
 * The one energy widget (owner, 2026-11-02; it replaces "Jetzt", "Verlauf" and
 * "Gesamt"): what flows now and what came together today, from
 * `/napi/portal/energy` ([EnergyRender]). No per-instance config — one house
 * picture. A tap opens Homes' Energie page at the paired address
 * (`https://haus.<domain>/energie`); the live stream keeps it current.
 */
class EnergyWidgetProvider : FedWidgetProvider(ACTION_REFRESH, 730_000, tapStartsLive = true) {

    override fun onDeleted(context: Context, ids: IntArray) {
        // The live service feeds this widget: with the last one gone it may stop.
        cloud.dopp.homes.realtime.Live.recheck(context)
    }

    override fun refresh(context: Context, appWidgetId: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        val paired = TokenStore.isPaired(context)
        val tap = tapPending(context, appWidgetId)
        // Immediate render (loading / pair hint), then async fetch.
        mgr.updateAppWidget(appWidgetId, EnergyRender.build(context, appWidgetId, null, paired, tap))
        if (!paired) return
        async(context, appWidgetId) {
            val energy = try {
                ApiClient(context.applicationContext).getEnergy()
            } catch (e: Exception) {
                null
            }
            // the flow's sensors join the watch set: their frames read this widget again
            energy?.let { e -> cloud.dopp.homes.realtime.WatchSet.rememberEnergy(context, e.flow.mapNotNull { it.entityId } + listOfNotNull(e.chargeEntityId)) }
            mgr.updateAppWidget(appWidgetId, EnergyRender.build(context, appWidgetId, energy, true, tap))
        }
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.homes.widget.ENERGY_REFRESH"

        /** The body's tap: Homes' Energie page in the browser, counted as this widget's (#180). */
        fun tapPending(context: Context, appWidgetId: Int): PendingIntent =
            HomesPages.tap(context, appWidgetId, HomesPages.ENERGIE, WidgetKind.ENERGY)
    }
}
