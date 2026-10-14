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
import cloud.dopp.haus.data.BoxApiClient
import cloud.dopp.haus.data.BoxHome
import kotlin.concurrent.thread

/**
 * Box **overview** widget (#42): a home-summary card showing services
 * up (+ failed/down), pending approvals and pending updates, from Haus's
 * `/napi/box/home`. Read-only — a tap opens Haus's Box page, where the admin
 * finds the way to Laib's own page for approvals. Config-less; the paired
 * Haus and its token are enough.
 */
class BoxOverviewWidgetProvider : AppWidgetProvider() {

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
        // Tap → Haus's Box page (#109).
        val tap = HausPages.tap(context, appWidgetId, HausPages.BOX)
        // Immediate render from the last-good cache (#46) — show the last numbers
        // instantly, not "—", even after a reboot/process death — then live fetch.
        val cached = WidgetCache.getHome(context, appWidgetId)
        try {
            mgr.updateAppWidget(appWidgetId, render(context, appWidgetId, cached, tap))
        } catch (t: Throwable) {
            WidgetFallback.show(context, appWidgetId, tap)
            return
        }
        val pending = goAsync()
        thread {
            try {
                val home = try { BoxApiClient(context.applicationContext).getHome() } catch (e: Exception) { null }
                if (home != null) WidgetCache.putHome(context, appWidgetId, home)
                // On a failed fetch keep the last-good numbers instead of blanking (#46).
                mgr.updateAppWidget(appWidgetId, render(context, appWidgetId, home ?: cached, tap))
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

    private fun render(context: Context, appWidgetId: Int, home: BoxHome?, tap: PendingIntent): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_box_overview)
        v.setOnClickPendingIntent(R.id.box_root, tap)
        v.setOnClickPendingIntent(R.id.box_refresh, refreshPending(context, appWidgetId))
        if (home == null) {
            v.setTextViewText(R.id.box_services, "—")
            v.setTextViewText(R.id.box_approvals, "—")
            v.setTextViewText(R.id.box_updates, "—")
            v.setViewVisibility(R.id.box_services_bad, View.GONE)
            return v
        }
        v.setTextViewText(R.id.box_services, home.servicesUp.toString())
        v.setTextViewText(R.id.box_approvals, home.pendingApprovals.toString())
        v.setTextViewText(R.id.box_updates, home.pendingUpdates.toString())
        val bad = home.servicesFailed + home.servicesDown
        if (bad > 0) {
            v.setViewVisibility(R.id.box_services_bad, View.VISIBLE)
            v.setTextViewText(
                R.id.box_services_bad,
                context.getString(R.string.box_overview_bad, bad),
            )
        } else {
            v.setViewVisibility(R.id.box_services_bad, View.GONE)
        }
        return v
    }

    private fun refreshPending(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, BoxOverviewWidgetProvider::class.java)
            .setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, 320_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.haus.widget.BOX_OVERVIEW_REFRESH"
    }
}
