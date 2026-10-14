package cloud.dopp.haus.realtime

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import cloud.dopp.haus.data.Napi
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.data.request
import cloud.dopp.haus.widget.ActiveDevicesWidgetProvider
import cloud.dopp.haus.widget.BoxOverviewWidgetProvider
import cloud.dopp.haus.widget.BoxServicesWidgetProvider
import cloud.dopp.haus.widget.DeviceWidgetProvider
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * The one live stream (laib ADR 0022 §2): `GET /napi/portal/events`, held only
 * while the screen is on, reading `card_state` frames into the device widgets.
 *
 * No service and no notification keep it: the app process listens for the
 * screen ([install], from [cloud.dopp.haus.HausApp]) for as long as Android
 * keeps it, and every widget update starts the process again. With the screen
 * off nothing runs — no poll, no catch-up. On screen-on the stream reconnects
 * and resyncs. [LiveGate] holds the rule; this object is its Android side.
 * All state changes run on the main thread.
 */
object LiveStream {

    /** Re-post the watch set well inside Haus's one-hour TTL. */
    private const val WATCH_REPOST_MS = 20L * 60L * 1000L

    /** A burst of card_state frames costs one active-list refetch, not one each (#79). */
    private const val ACTIVE_REFRESH_DEBOUNCE_MS = 1_500L

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    @Volatile private var source: EventSource? = null
    private var attempt = 0
    private var connectedAtMs = 0L
    private var installed = false

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived; Haus pings every 90 s
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private val sse = object : LiveGate.Stream {
        override fun open() = connect()
        override fun close() = disconnect()
        override fun resync() = catchUp()
    }

    private val gate = LiveGate(sse)

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    attempt = 0 // a new look at the phone starts the backoff over
                    evaluate(on = true)
                }
                Intent.ACTION_SCREEN_OFF -> evaluate(on = false)
            }
        }
    }

    /** Listen for the screen; called once per process from the Application. */
    @Synchronized
    fun install(ctx: Context) {
        val a = ctx.applicationContext
        if (installed && a === app) return
        installed = true
        app = a
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { ContextCompat.registerReceiver(app, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
        refresh(app)
    }

    /** Look again: after pairing, unpairing, or a widget placed or removed. */
    fun refresh(ctx: Context) {
        if (!installed) install(ctx)
        main.post { evaluate(on = interactive()) }
    }

    private fun evaluate(on: Boolean) = gate.screen(on, eligible())

    private fun interactive(): Boolean =
        app.getSystemService(PowerManager::class.java)?.isInteractive ?: false

    /** Paired, and at least one widget the stream feeds ([FED]) is on a home screen. */
    private fun eligible(): Boolean {
        if (!ServerStore.isConfigured(app) || !TokenStore.isPaired(app)) return false
        val mgr = AppWidgetManager.getInstance(app) ?: return false
        return FED.any { p -> runCatching { mgr.getAppWidgetIds(ComponentName(app, p)) }.getOrNull()?.isNotEmpty() == true }
    }

    /**
     * The widgets the stream keeps current: the device widgets take its frames,
     * the active list refetches on them, and all four re-read on [catchUp]. Only
     * these open the stream, and removing one of them looks again ([refresh]).
     * The static tiles, the energy and the tool widgets are not fed by it.
     */
    val FED: List<Class<*>> = listOf(
        DeviceWidgetProvider::class.java, ActiveDevicesWidgetProvider::class.java,
        BoxOverviewWidgetProvider::class.java, BoxServicesWidgetProvider::class.java,
    )

    private fun connect() {
        val base = ServerStore.baseUrl(app)?.trimEnd('/')
        val token = TokenStore.get(app)
        if (base == null || token == null) {
            gate.dropped()
            return
        }
        main.removeCallbacks(reconnect)
        runCatching { source?.cancel() }
        connectedAtMs = 0L
        source = EventSources.createFactory(client).newEventSource(Napi.EVENTS.request(base, token), listener)
    }

    private fun disconnect() {
        main.removeCallbacks(reconnect)
        main.removeCallbacks(watchRepost)
        main.removeCallbacks(activeRefresh)
        runCatching { source?.cancel() }
        source = null
        connectedAtMs = 0L
    }

    /** What changed while nobody listened: the watch set first, then every widget re-reads. */
    private fun catchUp() {
        WatchSet.postCurrentAsync(app)
        main.removeCallbacks(watchRepost)
        main.postDelayed(watchRepost, WATCH_REPOST_MS)
        runCatching { DeviceWidgetProvider.refreshAll(app) }
        runCatching { ActiveDevicesWidgetProvider.refreshAll(app) }
        runCatching { BoxServicesWidgetProvider.refreshAll(app) }
        runCatching { BoxOverviewWidgetProvider.refreshAll(app) }
    }

    private val watchRepost = object : Runnable {
        override fun run() {
            if (!gate.running) return
            WatchSet.postCurrentAsync(app)
            main.postDelayed(this, WATCH_REPOST_MS)
        }
    }

    private val activeRefresh = Runnable { runCatching { ActiveDevicesWidgetProvider.refreshAll(app) } }

    private val reconnect = Runnable { evaluate(on = interactive()) }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            main.post {
                if (eventSource !== source) return@post
                connectedAtMs = SystemClock.elapsedRealtime()
                gate.connected()
            }
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (eventSource !== source || !gate.accepts()) return
            val ev = RealtimeProtocol.frame(type, data) ?: return
            runCatching { DeviceWidgetProvider.wakeEntity(app, ev.entityId, ev.card) }
            main.post {
                main.removeCallbacks(activeRefresh)
                main.postDelayed(activeRefresh, ACTIVE_REFRESH_DEBOUNCE_MS)
            }
        }

        override fun onClosed(eventSource: EventSource) = gone(eventSource, retry = true)

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) =
            // 401: the token is gone (the device was removed in Haus) — wait for the next screen-on.
            gone(eventSource, retry = response?.code != 401)
    }

    private fun gone(eventSource: EventSource, retry: Boolean) {
        main.post {
            if (eventSource !== source) return@post
            source = null
            main.removeCallbacks(watchRepost)
            if (RealtimeProtocol.healthySession(connectedAtMs, SystemClock.elapsedRealtime())) attempt = 0
            connectedAtMs = 0L
            if (gate.dropped() && retry) {
                main.removeCallbacks(reconnect)
                main.postDelayed(reconnect, RealtimeProtocol.backoffMillis(attempt++))
            }
        }
    }
}
