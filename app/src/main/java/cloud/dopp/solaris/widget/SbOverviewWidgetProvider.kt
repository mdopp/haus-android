package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.SbApiClient
import cloud.dopp.solaris.data.SbHome
import cloud.dopp.solaris.ui.ApprovalsActivity
import kotlin.concurrent.thread

/**
 * ServiceBay **overview** widget (#42): a home-summary card showing services
 * up (+ failed/down), pending approvals and pending updates, from Solaris'
 * aggregated `/napi/box/home` (BFF, ADR 0010). Read-only — a tap opens the
 * PWA. Config-less (like the camera/voice tiles); the bound Solaris server + token
 * are enough.
 */
class SbOverviewWidgetProvider : AppWidgetProvider() {

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
        // Tap → the ServiceBay admin host the numbers come from, not Solaris chat
        // (#109; same target as the updates notification, #45).
        val tap = PwaLauncher.serviceBayTap(context, appWidgetId)
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
                val home = try { SbApiClient(context.applicationContext).getHome() } catch (e: Exception) { null }
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
    }

    private fun render(context: Context, appWidgetId: Int, home: SbHome?, tap: PendingIntent): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_sb_overview)
        v.setOnClickPendingIntent(R.id.sb_root, tap)
        v.setOnClickPendingIntent(R.id.sb_refresh, refreshPending(context, appWidgetId))
        // The approvals count opens the in-app approvals list (#43).
        v.setOnClickPendingIntent(R.id.sb_approvals, approvalsPending(context, appWidgetId))
        if (home == null) {
            v.setTextViewText(R.id.sb_services, "—")
            v.setTextViewText(R.id.sb_approvals, "—")
            v.setTextViewText(R.id.sb_updates, "—")
            v.setViewVisibility(R.id.sb_services_bad, View.GONE)
            return v
        }
        v.setTextViewText(R.id.sb_services, home.servicesUp.toString())
        v.setTextViewText(R.id.sb_approvals, home.pendingApprovals.toString())
        v.setTextViewText(R.id.sb_updates, home.pendingUpdates.toString())
        val bad = home.servicesFailed + home.servicesDown
        if (bad > 0) {
            v.setViewVisibility(R.id.sb_services_bad, View.VISIBLE)
            v.setTextViewText(
                R.id.sb_services_bad,
                context.getString(R.string.sb_overview_bad, bad),
            )
        } else {
            v.setViewVisibility(R.id.sb_services_bad, View.GONE)
        }
        return v
    }

    private fun refreshPending(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, SbOverviewWidgetProvider::class.java)
            .setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, 320_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun approvalsPending(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, ApprovalsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, 321_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.solaris.widget.SB_OVERVIEW_REFRESH"

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
                android.content.ComponentName(context, SbOverviewWidgetProvider::class.java),
            )
            for (id in ids) {
                val i = Intent(context, SbOverviewWidgetProvider::class.java)
                    .setAction(ACTION_REFRESH)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                context.sendBroadcast(i)
            }
        }
    }
}
