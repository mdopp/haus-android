package cloud.dopp.homes.kann

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import cloud.dopp.homes.data.KannClient
import cloud.dopp.homes.data.ScreenGate
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.konto.HomesKonto
import cloud.dopp.homes.realtime.Live
import cloud.dopp.homes.widget.AppShortcuts
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * When the app asks Homes what the box can do (homes ADR 0001 §3): at start
 * and resume, at most once a minute, and when the live stream connects (a
 * restarted Homes may have another list) — always with the kept ETag, so an
 * unchanged answer is a 304; never while the screen is off ([ScreenGate]).
 * Each answer switches the share targets ([Teilen]), the account's parts
 * ([HomesKonto.folgen]) and the widget providers ([Anbieter]). A changed answer is said with [ACTION_GEAENDERT]
 * to the app's screens, and the placed widgets draw again.
 */
object KannLeser {

    /** The kept answer changed: a screen that shows features draws again. */
    const val ACTION_GEAENDERT = "cloud.dopp.homes.kann.GEAENDERT"

    private val laeuft = AtomicBoolean(false)

    /** Ask, off the main thread, when due; [immer] skips the minute. */
    fun lesen(ctx: Context, immer: Boolean = false) {
        val app = ctx.applicationContext
        if (!ServerStore.isConfigured(app) || !TokenStore.isPaired(app) || !ScreenGate.open(app)) return
        if (!KannStore.faellig(app, System.currentTimeMillis(), immer)) return
        if (!laeuft.compareAndSet(false, true)) return
        thread {
            try {
                if (einmal(app)) geaendert(app)
            } finally {
                laeuft.set(false)
            }
        }
    }

    /** One read, blocking; true when the kept answer changed. */
    fun einmal(ctx: Context, client: KannClient? = null): Boolean {
        val c = client ?: KannClient(ServerStore.baseUrl(ctx) ?: return false, TokenStore.get(ctx) ?: return false)
        val changed = when (val a = c.lesen(KannStore.etag(ctx))) {
            is KannClient.Antwort.Neu -> KannStore.neu(ctx, a.body, a.etag)
            KannClient.Antwort.Fehlt -> KannStore.fehlt(ctx)
            else -> return false
        }
        // the share targets, the account's parts and the widget providers follow every answer
        val kann = KannStore.kann(ctx)
        val teilen = Teilen.schalten(ctx, kann)
        HomesKonto.folgen(ctx, kann)
        val widgets = Anbieter.schalten(ctx, kann)
        return changed || teilen || widgets
    }

    /**
     * Say it: the screens draw again, the share sheet's Direct Share target
     * follows the share targets, and every placed widget draws again (one
     * whose type went says why).
     */
    fun geaendert(ctx: Context) {
        runCatching { ctx.sendBroadcast(Intent(ACTION_GEAENDERT).setPackage(ctx.packageName)) }
        AppShortcuts.refreshAsync(ctx)
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        Live.FED.forEach { provider ->
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(ctx, provider)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return@forEach
            runCatching {
                ctx.sendBroadcast(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setComponent(ComponentName(ctx, provider))
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
                )
            }
        }
    }
}
