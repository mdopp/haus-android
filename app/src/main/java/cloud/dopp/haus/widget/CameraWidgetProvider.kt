package cloud.dopp.haus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import cloud.dopp.haus.R
import cloud.dopp.haus.ui.CameraCaptureActivity

/**
 * The camera **trigger** widget (#36): a small tile (camera icon + "Kamera") that
 * opens the app's **native capture + upload** screen on tap — take one photo or a
 * series, name it (date/time prefilled), optionally combine into a multi-page PDF,
 * and upload to the paired Haus. It no longer jumps into Haus's pages (that
 * didn't even open the camera). No per-camera configuration, no snapshot loading.
 */
class CameraWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) = render(context, mgr, appWidgetId)

    private fun render(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
        // Tap → the native capture + upload screen (#36); no data to fetch.
        val tap = capturePending(context, appWidgetId)
        val v = RemoteViews(context.packageName, R.layout.widget_camera)
        v.setTextViewText(R.id.cam_name, context.getString(R.string.camera_widget_label))
        v.setOnClickPendingIntent(R.id.cam_root, tap)
        try {
            mgr.updateAppWidget(appWidgetId, v)
        } catch (t: Throwable) {
            // Never leave the host on "Widget kann nicht geladen werden" (#32).
            WidgetFallback.show(context, appWidgetId, tap)
        }
    }

    private fun capturePending(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, CameraCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, 300_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
