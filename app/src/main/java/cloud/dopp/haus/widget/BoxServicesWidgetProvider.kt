package cloud.dopp.haus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import cloud.dopp.haus.R
import cloud.dopp.haus.data.BoxApiClient
import kotlin.concurrent.thread

/**
 * Box **service-status summary** widget (#44): three counts — healthy /
 * warning / failure — from Haus's `/napi/box/services`,
 * bucketed by [BoxRender]. Deliberately NOT a per-service list and NOT a control
 * surface. Read-only; a tap opens Haus's Box page.
 */
class BoxServicesWidgetProvider : AppWidgetProvider() {

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
            cloud.dopp.haus.realtime.Live.ensure(context) // a widget tap: an allowed start
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) refresh(context, id)
        }
    }

    private fun refresh(context: Context, appWidgetId: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        // Tap → Haus's Box page (#109).
        val tap = HausPages.tap(context, 340_000 + appWidgetId, HausPages.BOX, WidgetKind.BOX_SERVICES)
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
                    BoxApiClient(context.applicationContext).listServices()
                } catch (e: Exception) {
                    null
                }
                val counts = services?.let { BoxRender.counts(it) }
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
        // The live service feeds this widget: with the last one gone it may stop.
        cloud.dopp.haus.realtime.Live.recheck(context)
    }

    private fun render(
        context: Context,
        appWidgetId: Int,
        counts: Triple<Int, Int, Int>?,
        tap: PendingIntent,
    ): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_box_services)
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
        val i = Intent(context, BoxServicesWidgetProvider::class.java)
            .setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, 340_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.haus.widget.BOX_SERVICES_REFRESH"
    }
}
