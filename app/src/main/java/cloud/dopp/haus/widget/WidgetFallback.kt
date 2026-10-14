package cloud.dopp.haus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.RemoteViews
import cloud.dopp.haus.R

/**
 * A clean "konnte nicht laden" fallback for any widget provider (#32). When a
 * data fetch or render throws, pushing this instead of letting the exception
 * escape keeps the host from showing Android's own "Widget kann nicht geladen
 * werden". A tap re-runs the provider's refresh so the user can retry.
 */
object WidgetFallback {

    /**
     * Render the error placeholder for [appWidgetId]. Never throws — any failure
     * here is swallowed (the last-good view stays on screen) since this is the
     * last line of defence. [onTap] (when given) re-triggers a refresh.
     */
    fun show(context: Context, appWidgetId: Int, onTap: PendingIntent? = null) {
        try {
            val v = RemoteViews(context.packageName, R.layout.widget_error)
            if (onTap != null) v.setOnClickPendingIntent(R.id.we_root, onTap)
            AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, v)
        } catch (t: Throwable) {
            // Nothing more we can safely do — leave the previous render in place.
        }
    }
}
