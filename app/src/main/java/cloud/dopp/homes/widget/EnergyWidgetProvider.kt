package cloud.dopp.homes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.Napi.KannKey
import cloud.dopp.homes.data.TokenStore
import org.json.JSONObject

/**
 * The one energy widget (owner, 2026-11-02; it replaces "Jetzt", "Verlauf" and
 * "Gesamt"): what flows now and what came together today, from
 * `/napi/portal/energy` ([EnergyRender]). No per-instance config — one house
 * picture. A tap opens Homes' Energie page at the paired address
 * (`https://haus.<domain>/energie`); the live stream keeps it current. The
 * energy read last draws at once and stays when a read fails ([WidgetCache]).
 */
class EnergyWidgetProvider : FedWidgetProvider(ACTION_REFRESH, 730_000, tapStartsLive = true) {

    override fun onDeleted(context: Context, ids: IntArray) {
        // The live service feeds this widget: with the last one gone it may stop.
        cloud.dopp.homes.realtime.Live.recheck(context)
    }

    override fun refresh(context: Context, appWidgetId: Int) {
        if (!zeichne(context, appWidgetId)) return
        async(context, appWidgetId) {
            val body = ApiClient(context.applicationContext).energyBody() ?: return@async // a failed read keeps what is drawn
            val energy = ApiClient.parseEnergy(JSONObject(body)) ?: return@async
            WidgetCache.putEnergy(context, body)
            // the flow's sensors join the watch set: their frames read this widget again
            cloud.dopp.homes.realtime.WatchSet.rememberEnergy(context, energy.flow.mapNotNull { it.entityId } + listOfNotNull(energy.chargeEntityId))
            AppWidgetManager.getInstance(context)
                .updateAppWidget(appWidgetId, EnergyRender.build(context, appWidgetId, energy, true, tapPending(context, appWidgetId)))
        }
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.homes.widget.ENERGY_REFRESH"

        /** Every placed energy widget draws the energy read last, no read: the line to Homes changed. */
        fun zeichnen(ctx: Context) {
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, EnergyWidgetProvider::class.java))
                .forEach { zeichne(ctx, it) }
        }

        /** The energy read last (or the pair hint) at once; false when there is nothing to read: not paired, or gone from the box. */
        private fun zeichne(ctx: Context, appWidgetId: Int): Boolean {
            if (WidgetFallback.weg(ctx, appWidgetId, KannKey.ENERGIE)) return false // the box lost its energy
            val paired = TokenStore.isPaired(ctx)
            val e = if (paired) WidgetCache.energy(ctx) else null
            runCatching {
                AppWidgetManager.getInstance(ctx)
                    .updateAppWidget(appWidgetId, EnergyRender.build(ctx, appWidgetId, e, paired, tapPending(ctx, appWidgetId)))
            }
            return paired
        }

        /** The body's tap: Homes' Energie page in the browser, counted as this widget's (#180). */
        fun tapPending(context: Context, appWidgetId: Int): PendingIntent =
            HomesPages.tap(context, appWidgetId, HomesPages.ENERGIE, WidgetKind.ENERGY)
    }
}
