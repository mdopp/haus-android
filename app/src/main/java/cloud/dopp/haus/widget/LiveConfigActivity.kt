package cloud.dopp.haus.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import cloud.dopp.haus.realtime.Live

/**
 * The configure step of the fed widgets that need no choice (energy, its
 * statistics and totals, active devices, the Box widgets): no UI, it starts
 * the live service and confirms at once. It exists because placing a widget
 * from the launcher tray is otherwise no allowed start for a foreground
 * service on Android 12+; a visible activity, even a translucent one, is.
 */
class LiveConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        Live.ensure(applicationContext)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
