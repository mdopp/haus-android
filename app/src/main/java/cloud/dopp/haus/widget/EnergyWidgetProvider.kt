package cloud.dopp.haus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.TokenStore
import kotlin.concurrent.thread

/**
 * House energy widget (#8). Shows the live grid net (small) or the full
 * PV/Haus/Netz/Akku flow (large). No per-instance config — one house picture.
 * A tap opens the Haus app (or pairing if not yet paired).
 */
class EnergyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, it) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        // The live service feeds this widget: with the last one gone it may stop.
        cloud.dopp.haus.realtime.Live.recheck(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        refresh(context, appWidgetId)
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
        val paired = TokenStore.isPaired(context)
        // Body tap opens Haus's start page (#27); the icon triggers a refresh (#26).
        val tap = HausPages.tap(context, appWidgetId, HausPages.START)
        val onRefresh = refreshPending(context, appWidgetId)
        // Immediate render (loading / pair hint), then async fetch.
        mgr.updateAppWidget(appWidgetId, EnergyRender.build(context, appWidgetId, null, paired, tap, onRefresh))
        if (!paired) return
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
                mgr.updateAppWidget(appWidgetId, EnergyRender.build(context, appWidgetId, energy, true, tap, onRefresh))
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, onRefresh)
            } finally {
                pending?.finish()
            }
        }
    }

    /** Broadcast our own [ACTION_REFRESH] to re-fetch this instance (#26). */
    private fun refreshPending(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, EnergyWidgetProvider::class.java)
            .setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, appWidgetId + 730_000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.haus.widget.ENERGY_REFRESH"
    }
}
