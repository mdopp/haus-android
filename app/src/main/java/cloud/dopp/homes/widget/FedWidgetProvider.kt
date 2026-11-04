package cloud.dopp.homes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import cloud.dopp.homes.realtime.Live
import kotlin.concurrent.thread

/**
 * What the three widget providers share: each draws an instance in [refresh]
 * on an update, a resize and its own [refreshAction] broadcast, reads off the
 * main thread in [async], and a failure there draws [WidgetFallback] with a
 * refresh tap. [refreshCode] keeps each kind's refresh taps apart.
 */
abstract class FedWidgetProvider(
    private val refreshAction: String,
    private val refreshCode: Int,
    /** A refresh broadcast is a widget tap, an allowed start of the live service. */
    private val tapStartsLive: Boolean,
) : AppWidgetProvider() {

    protected abstract fun refresh(context: Context, appWidgetId: Int)

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        refresh(context, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != refreshAction) return
        if (tapStartsLive) Live.ensure(context)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) refresh(context, id)
    }

    /** [read] off the main thread while the broadcast stays alive; a failure draws the fallback. */
    // Anything at all, a RemoteViews error too: the fallback tile is the answer, never "cannot load widget" (#32).
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    protected fun async(context: Context, appWidgetId: Int, read: () -> Unit) {
        val pending = goAsync()
        thread {
            try {
                read()
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
            } finally {
                pending?.finish()
            }
        }
    }

    /** A tap that refreshes [appWidgetId]. */
    fun refreshPending(context: Context, appWidgetId: Int): PendingIntent = PendingIntent.getBroadcast(
        context, refreshCode + appWidgetId, refreshIntent(context, javaClass, refreshAction, appWidgetId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /** The broadcast that has [provider] refresh [appWidgetId]. */
        fun refreshIntent(context: Context, provider: Class<*>, action: String, appWidgetId: Int): Intent =
            Intent(context, provider).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }
}
