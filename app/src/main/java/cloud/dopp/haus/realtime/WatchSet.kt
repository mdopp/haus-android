package cloud.dopp.haus.realtime

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.ScreenGate
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.widget.EnergyWidgetProvider
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The device's **watch set** (#48): the Home Assistant entities its widgets
 * show, posted to `POST /napi/portal/watch` so Haus streams `card_state` for
 * exactly those entities over `/napi/portal/events`: while the energy widget
 * is placed, the energy flow's sensors, which Haus sends at most every 10 s.
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

    private const val PREFS = "haus_watch"
    private const val KEY_ENERGY = "energy"

    /**
     * The current watch set: the energy flow's entities while the energy widget
     * is placed; non-blank, de-duplicated.
     */
    fun currentEntities(ctx: Context): List<String> {
        val app = ctx.applicationContext
        val placed = AppWidgetManager.getInstance(app)
            .getAppWidgetIds(ComponentName(app, EnergyWidgetProvider::class.java)).isNotEmpty()
        return (if (placed) energy(app) else emptySet()).filter { it.isNotBlank() }.distinct()
    }

    /** What the watch post sends: [currentEntities]. */
    fun body(ctx: Context): JSONObject = ApiClient.watchBody(currentEntities(ctx))

    /** The energy flow's entities, as the energy widget last read them. */
    fun energy(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_ENERGY, null) ?: emptySet()

    /** Remember the energy flow's [entities] for the watch set. */
    fun rememberEnergy(ctx: Context, entities: List<String>) {
        val now = entities.filter { it.isNotBlank() }.toSet()
        if (now == energy(ctx)) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_ENERGY, now).apply()
        postCurrentAsync(ctx) // a new set: tell Haus now, not at the next re-post
    }

    /** Paired + configured — the only state in which a post can succeed. */
    private fun eligible(ctx: Context): Boolean =
        ServerStore.isConfigured(ctx) && TokenStore.isPaired(ctx)

    private fun screenOn(ctx: Context): Boolean = ScreenGate.open(ctx)

    /**
     * Post the current widget entity set on a background thread. No-op when not
     * paired or while the screen is off: the stream posts it when the screen is
     * on again (LiveService's resync). A 503 is retried with backoff while the screen is on and no newer
     * post started; any other failure is dropped.
     */
    fun postCurrentAsync(ctx: Context) {
        val app = ctx.applicationContext
        if (!eligible(app) || !screenOn(app)) return
        val mine = generation.incrementAndGet()
        thread {
            var attempt = 0
            while (true) {
                val code = runCatching { ApiClient(app).postWatch(body(app)) }.getOrDefault(0)
                val wait = RealtimeProtocol.watchRetryMs(code, attempt++) ?: break
                Thread.sleep(wait)
                if (generation.get() != mine || !eligible(app) || !screenOn(app)) break
            }
        }
    }
}
