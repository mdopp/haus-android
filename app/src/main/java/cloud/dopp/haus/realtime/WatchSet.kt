package cloud.dopp.haus.realtime

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.widget.DeviceWidgetProvider
import cloud.dopp.haus.widget.WidgetStore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The device's **watch set** (#48): the Home Assistant entities its device
 * widgets are bound to, posted to `POST /napi/portal/watch` so Haus streams
 * `card_state` for exactly those entities over `/napi/portal/events`.
 *
 * Only [DeviceWidgetProvider] instances bind one entity; the active, energy,
 * tool, camera and Box widgets do not, so they are not in the set.
 *
 * Best-effort and guarded: it posts only when paired, off the caller's thread,
 * and a failure never touches the widgets. Haus answers 503 while Home
 * Assistant is down; then the post is tried again with backoff
 * ([RealtimeProtocol.watchRetryMs]) while the screen stays on, and a newer post
 * replaces an older one's retries.
 */
object WatchSet {

    /** Bumped by every post: a retry loop that sees a newer one stops. */
    private val generation = AtomicInteger()

    /**
     * The current device-widget entity set: the bound entity of every live
     * [DeviceWidgetProvider] instance, non-null/blank, de-duplicated.
     */
    fun currentEntities(ctx: Context): List<String> {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val ids = mgr.getAppWidgetIds(ComponentName(app, DeviceWidgetProvider::class.java))
        return ids.toList()
            .mapNotNull { id -> WidgetStore.entityId(app, id) }
            .filter { eid -> eid.isNotBlank() }
            .distinct()
    }

    /** Paired + configured — the only state in which a post can succeed. */
    private fun eligible(ctx: Context): Boolean =
        ServerStore.isConfigured(ctx) && TokenStore.isPaired(ctx)

    private fun screenOn(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: false

    /**
     * Post the current widget entity set on a background thread. No-op when not
     * paired. A 503 is retried with backoff while the screen is on and no newer
     * post started; any other failure is dropped.
     */
    fun postCurrentAsync(ctx: Context) {
        val app = ctx.applicationContext
        if (!eligible(app)) return
        val mine = generation.incrementAndGet()
        thread {
            var attempt = 0
            while (true) {
                val code = runCatching { ApiClient(app).postWatch(currentEntities(app)) }.getOrDefault(0)
                val wait = RealtimeProtocol.watchRetryMs(code, attempt++) ?: break
                Thread.sleep(wait)
                if (generation.get() != mine || !eligible(app) || !screenOn(app)) break
            }
        }
    }
}
