package cloud.dopp.haus.realtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cloud.dopp.haus.R
import cloud.dopp.haus.data.Napi
import cloud.dopp.haus.data.PersonaStore
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.data.request
import cloud.dopp.haus.ui.HomeActivity
import cloud.dopp.haus.widget.ActiveDevicesWidgetProvider
import cloud.dopp.haus.widget.BoxOverviewWidgetProvider
import cloud.dopp.haus.widget.BoxServicesWidgetProvider
import cloud.dopp.haus.widget.DeviceWidgetProvider
import cloud.dopp.haus.widget.EnergyStatsWidgetProvider
import cloud.dopp.haus.widget.EnergyTotalsWidgetProvider
import cloud.dopp.haus.widget.EnergyWidgetProvider
import cloud.dopp.haus.widget.ToolWidgetProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * When the live widgets run, and how they are started (#176: the old app's
 * mechanism, widgets only). They run while the app is paired, the switch in
 * the app is on (the default) and a widget the stream keeps current ([FED])
 * is placed; [LiveService] then decides by the screen whether to hold the
 * connection.
 */
object Live {
    private const val PREFS = "haus_live"
    private const val KEY_ON = "on"

    /** The widgets the stream keeps current: frames, refetches on frames, or the screen-on resync. */
    val FED: List<Class<out AppWidgetProvider>> = listOf(
        DeviceWidgetProvider::class.java, ActiveDevicesWidgetProvider::class.java,
        BoxOverviewWidgetProvider::class.java, BoxServicesWidgetProvider::class.java,
        EnergyWidgetProvider::class.java, EnergyStatsWidgetProvider::class.java,
        EnergyTotalsWidgetProvider::class.java, ToolWidgetProvider::class.java,
    )

    /**
     * Haus writes `: ping` every 90 s on HTTP/1.1, where OkHttp's own ping does
     * nothing. A read timeout of three pings turns a silently dead path (a router
     * reboot, a lost NAT entry) into a failure, and the service reconnects.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .build()
    }

    /** Opens the stream. Replaced in tests. */
    @Volatile
    var open: (Request, EventSourceListener) -> EventSource =
        { req, listener -> EventSources.createFactory(client).newEventSource(req, listener) }

    /** Posts the watch set. Replaced in tests. */
    @Volatile
    var postWatch: (Context) -> Unit = { WatchSet.postCurrentAsync(it) }

    /** Makes the foreground start fail, as Android refuses it outside an allowed start. Tests only. */
    @VisibleForTesting
    @Volatile
    var refuseForeground = false

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The switch in the app: on unless the person turned it off. */
    fun isEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_ON, true)

    /** Flip the switch; called from the app's screen, so it may start the service. */
    fun setEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_ON, on).apply()
        ensure(ctx)
    }

    fun fedPlaced(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return false
        return FED.any { runCatching { mgr.getAppWidgetIds(ComponentName(ctx, it)) }.getOrNull()?.isNotEmpty() == true }
    }

    fun wanted(ctx: Context): Boolean =
        isEnabled(ctx) && ServerStore.isConfigured(ctx) && TokenStore.isPaired(ctx) && fedPlaced(ctx)

    /**
     * On the battery-optimization exemption list. Only then may Android's restart
     * of the service after its process died (`START_STICKY`) go foreground on
     * Android 12+; that exemption is what made the old app recover by itself.
     */
    fun exempt(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /**
     * Start the service when it is wanted, else stop it. Android 12+ lets an app
     * start a foreground service only from an allowed start: the app's screen, a
     * configure screen, a widget tap, boot, an app update — or anywhere while the
     * app is exempt from battery optimization. So call this from those;
     * elsewhere the start throws `ForegroundServiceStartNotAllowedException`,
     * which is swallowed, as in the old app.
     */
    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        val intent = Intent(app, LiveService::class.java)
        if (wanted(app)) {
            runCatching { ContextCompat.startForegroundService(app, intent) }
        } else {
            runCatching { app.stopService(intent) }
        }
    }

    /** Look again from anywhere (a widget removed): stops the service when it is no longer wanted, never starts it. */
    fun recheck(ctx: Context) {
        val app = ctx.applicationContext
        if (!wanted(app)) runCatching { app.stopService(Intent(app, LiveService::class.java)) }
    }
}

/**
 * The live widgets' foreground service: it holds `GET /napi/portal/events`
 * (only `card_state`) while the screen is on and nothing while it is off.
 *
 * - It listens for the screen itself — a foreground component gets the
 *   broadcasts, a cached process does not.
 * - Screen on: connect. Once connected: post the watch set and have every fed
 *   widget read again, since Haus sends a card only when it changes.
 * - Screen off: drop the connection. Nothing polls; a widget nobody sees
 *   needs no update.
 * - A frame renders its device widget from the card, refetches the active
 *   list and, for an energy entity, the energy widgets — each coalesced.
 * - A 401 stops the service (the device was removed in Haus); any other loss
 *   reconnects with backoff, reset only after a healthy session.
 * - After its process died, Android restarts it (`START_STICKY`), but on
 *   Android 12+ that restart may go foreground only while the app is exempt
 *   from battery optimization ([Live.exempt]); the app asks for it once after
 *   pairing and shows the state beside its switch. Without it the restart is
 *   refused and the service ends quietly, without a notification, until the
 *   next allowed start. [BootReceiver] starts it after a reboot and after an
 *   app update. No rescue worker, alarm, wakelock or poll.
 */
class LiveService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var source: EventSource? = null
    private var attempt = 0
    private var connectedAtMs = 0L
    private var status = R.string.live_connecting
    private var inForeground = false
    private var energyQueued = false

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    attempt = 0
                    connect()
                }
                Intent.ACTION_SCREEN_OFF -> drop(R.string.live_paused)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!foreground()) {
            stopSelf() // refused: nothing to undo, nothing shown
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Live.wanted(this)) {
            stop()
            return START_NOT_STICKY
        }
        if (interactive()) connect() else drop(R.string.live_paused)
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screen) }
        runCatching { source?.cancel() }
        source = null
        super.onDestroy()
    }

    private fun interactive(): Boolean = getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun connect() {
        if (source != null) return // open or opening: SCREEN_ON and USER_PRESENT both come on unlock
        val base = ServerStore.baseUrl(this)
        val token = TokenStore.get(this)
        if (base == null || token == null) {
            stop()
            return
        }
        main.removeCallbacks(reconnect)
        connectedAtMs = 0L
        source = Live.open(Napi.EVENTS.request(base, token), listener)
    }

    private fun drop(text: Int) {
        main.removeCallbacksAndMessages(null)
        runCatching { source?.cancel() }
        source = null
        connectedAtMs = 0L
        energyQueued = false
        show(text)
    }

    private fun stop() {
        inForeground = false
        drop(R.string.live_paused)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        stopSelf()
    }

    /** What changed while nobody listened: the watch set, then every fed widget reads again. */
    private fun resync() {
        Live.postWatch(this)
        main.removeCallbacks(watchRepost)
        main.postDelayed(watchRepost, WATCH_REPOST_MS)
        Live.FED.forEach { update(it) }
    }

    /** Ask every placed [provider] widget to read again, through its own onUpdate. */
    private fun update(provider: Class<out AppWidgetProvider>) {
        val ids = AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, provider))
        if (ids.isEmpty()) return
        sendBroadcast(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setComponent(ComponentName(this, provider))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
        )
    }

    private val watchRepost = object : Runnable {
        override fun run() {
            Live.postWatch(this@LiveService)
            main.postDelayed(this, WATCH_REPOST_MS)
        }
    }

    private val activeRefresh = Runnable { update(ActiveDevicesWidgetProvider::class.java) }

    private val energyRefresh = Runnable {
        energyQueued = false
        update(EnergyWidgetProvider::class.java)
        update(EnergyTotalsWidgetProvider::class.java)
    }

    private val reconnect = Runnable { if (Live.wanted(this)) connect() else stop() }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            main.post {
                if (eventSource !== source) return@post
                connectedAtMs = SystemClock.elapsedRealtime()
                show(R.string.live_on)
                resync()
            }
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            val ev = RealtimeProtocol.frame(type, data) ?: return
            main.post {
                if (eventSource !== source) return@post
                runCatching { DeviceWidgetProvider.wakeEntity(this@LiveService, ev.entityId, ev.card) }
                main.removeCallbacks(activeRefresh)
                main.postDelayed(activeRefresh, ACTIVE_REFRESH_DEBOUNCE_MS)
                // a sensor comes every 10 s at most: at most one energy refetch in that time
                if (!energyQueued && ev.entityId in WatchSet.energy(this@LiveService)) {
                    energyQueued = true
                    main.postDelayed(energyRefresh, ENERGY_REFRESH_MS)
                }
            }
        }

        override fun onClosed(eventSource: EventSource) {
            main.post { gone(eventSource) }
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            main.post {
                if (eventSource !== source) return@post
                if (response?.code == 401) stop() else gone(eventSource)
            }
        }
    }

    private fun gone(eventSource: EventSource) {
        if (eventSource !== source) return
        source = null
        main.removeCallbacks(watchRepost)
        if (RealtimeProtocol.healthySession(connectedAtMs, SystemClock.elapsedRealtime())) attempt = 0
        connectedAtMs = 0L
        if (!interactive()) return
        if (attempt >= 2) show(R.string.live_reconnecting)
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, RealtimeProtocol.backoffMillis(attempt++))
    }

    /** Go foreground; false when Android refuses (a start outside an allowed context). */
    private fun foreground(): Boolean = try {
        check(!Live.refuseForeground) { "refused" }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        inForeground = true
        true
    } catch (e: Exception) {
        false
    }

    /** Show [text] as the connection state, re-posting only on a change and only while in the foreground. */
    private fun show(text: Int) {
        if (text == status) return
        status = text
        if (!inForeground) return
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification()) }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.live_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = getString(R.string.live_channel_desc)
                        setShowBadge(false)
                    },
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_house)
            .setContentTitle(PersonaStore.name(this, getString(R.string.appName)))
            .setContentText(getString(status))
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL = "live_widgets"
        private const val NOTIFICATION_ID = 4801

        /** Re-post the watch set well inside Haus's one-hour TTL. */
        private const val WATCH_REPOST_MS = 20L * 60L * 1000L

        /** A burst of frames costs one active-list refetch, not one each (#79). */
        private const val ACTIVE_REFRESH_DEBOUNCE_MS = 1_500L

        /** Haus sends a sensor's card at most every 10 s: the energy widgets read at most that often. */
        private const val ENERGY_REFRESH_MS = 10_000L
    }
}

/**
 * Starts the live widgets again after a reboot and after an app update (which
 * kills the process), when they are wanted. Both are allowed starts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Live.ensure(context)
        }
    }
}
