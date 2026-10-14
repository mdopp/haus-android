package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.SbApiClient
import kotlin.concurrent.thread

/**
 * ServiceBay **service-status summary** widget (#44): three counts — healthy /
 * warning / failure — from Solaris' aggregated `/napi/box/services`
 * (BFF, ADR 0010), bucketed by [SbRender]. Deliberately NOT a per-service list and
 * NOT a control surface (start/stop hinges on solarisbay#827). Read-only; a tap
 * opens the PWA.
 */
class SbServicesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) = refresh(context, appWidgetId)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) refresh(context, id)
        }
    }

    private fun refresh(context: Context, appWidgetId: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        // Tap → the ServiceBay admin host the counts come from, not Solaris chat (#109).
        val tap = PwaLauncher.serviceBayTap(context, 340_000 + appWidgetId)
        // Seed the immediate render from the last-good cache (#46) instead of "—".
        val cached = WidgetCache.getServiceCounts(context, appWidgetId)
        try {
            mgr.updateAppWidget(appWidgetId, render(context, appWidgetId, cached, tap))
        } catch (t: Throwable) {
            WidgetFallback.show(context, appWidgetId, tap)
            return
        }
        val pending = goAsync()
        thread {
            try {
                val services = try {
                    SbApiClient(context.applicationContext).listServices()
                } catch (e: Exception) {
                    null
                }
                val counts = services?.let { SbRender.counts(it) }
                if (counts != null) WidgetCache.putServiceCounts(context, appWidgetId, counts)
                // Failed fetch → keep the last-good counts instead of blanking (#46).
                mgr.updateAppWidget(appWidgetId, render(context, appWidgetId, counts ?: cached, tap))
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, tap)
            } finally {
                pending?.finish()
            }
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetCache.clear(context, it) }
    }

    private fun render(
        context: Context,
        appWidgetId: Int,
        counts: Triple<Int, Int, Int>?,
        tap: PendingIntent,
    ): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_sb_services)
        v.setOnClickPendingIntent(R.id.svc_root, tap)
        v.setOnClickPendingIntent(R.id.svc_refresh, refreshPending(context, appWidgetId))
        if (counts == null) {
            v.setTextViewText(R.id.svc_ok, "—")
            v.setTextViewText(R.id.svc_warn, "—")
            v.setTextViewText(R.id.svc_fail, "—")
        } else {
            v.setTextViewText(R.id.svc_ok, counts.first.toString())
            v.setTextViewText(R.id.svc_warn, counts.second.toString())
            v.setTextViewText(R.id.svc_fail, counts.third.toString())
        }
        return v
    }

    private fun refreshPending(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, SbServicesWidgetProvider::class.java)
            .setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, 340_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.solaris.widget.SB_SERVICES_REFRESH"

        /**
         * Re-fetch and re-render every placed instance (#137).
         *
         * These tiles carry `updatePeriodMillis="0"` — Android never calls
         * `onUpdate` on its own — and the realtime channel cannot wake them either:
         * `card_state` is built from Home Assistant `state_changed` events, and a
         * ServiceBay service is not an HA entity. Without this the tile shows
         * whatever it read the last time someone tapped it, so a service that fell
         * over stays green on the home screen.
         *
         * Called from `RealtimeService.onOpen`, i.e. screen on and connection up —
         * the moment the tile is about to be looked at. Deliberately not on a timer:
         * polling for a surface nobody is watching costs battery for nothing.
         */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                android.content.ComponentName(context, SbServicesWidgetProvider::class.java),
            )
            for (id in ids) {
                val i = Intent(context, SbServicesWidgetProvider::class.java)
                    .setAction(ACTION_REFRESH)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                context.sendBroadcast(i)
            }
        }
    }
}
