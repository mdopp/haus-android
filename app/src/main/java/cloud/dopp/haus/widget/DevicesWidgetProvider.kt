package cloud.dopp.haus.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import cloud.dopp.haus.R

/**
 * The devices tile (#167): one tap from the home screen to **every device at
 * once** — the household start page, where each pinned device shows its live
 * state and can be operated, refreshing itself while open.
 *
 * The single-device tiles ([DeviceWidgetProvider]) already cover "one thing I
 * touch often". What was missing is the other direction: the whole set, without
 * pinning a tile per lamp. And a device tile that shows only what is currently
 * *on* ([ActiveDevicesWidgetProvider]) cannot help you switch on the lamp that
 * is off — by definition it is not in that list.
 *
 * A pure trigger tile like the camera one (#54): nothing to poll, so
 * [onUpdate] only (re)binds the tap. What the page then shows is Haus's to
 * decide — this widget is the way there, not a second opinion about it.
 */
class DevicesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val v = RemoteViews(context.packageName, R.layout.widget_devices)
            val tap = HausPages.tap(context, REQ_BASE + id, HausPages.START, WidgetKind.DEVICES)
            v.setOnClickPendingIntent(R.id.devices_root, tap)
            v.setOnClickPendingIntent(R.id.devices_icon, tap)
            try {
                mgr.updateAppWidget(id, v)
            } catch (t: Throwable) {
                WidgetFallback.show(context, id, tap)
            }
        }
    }

    private companion object {
        /** Own request-code range so a tap never collides with another widget's. */
        const val REQ_BASE = 520_000
    }
}
