package cloud.dopp.solaris.realtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import cloud.dopp.solaris.R
import cloud.dopp.solaris.SolarisConfig
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import cloud.dopp.solaris.widget.ActiveDevicesWidgetProvider
import cloud.dopp.solaris.widget.DeviceWidgetProvider
import cloud.dopp.solaris.widget.SbOverviewWidgetProvider
import cloud.dopp.solaris.widget.SbServicesWidgetProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The brand blue, the accent every notification of this package carries (#88).
 * Android masks the small icon to a white silhouette, so the brand colour can
 * only come back through [androidx.core.app.NotificationCompat.Builder.setColor].
 *
 * Deliberately **not** moved with the shell accent (#130): a notification is seen
 * in the shade among foreign notifications, not next to the web surface, so the
 * seam argument that turned the in-app accent orange does not reach it. Mirrors
 * `@color/solaris_brand_blue`.
 */
internal const val NOTIF_ACCENT = 0xFF3B82F6.toInt()

/**
 * Embedded realtime foreground service (#48). Holds a single long-lived **SSE**
 * connection to `GET {baseUrl}/napi/portal/events` (Bearer device-token +
 * `Accept: text/event-stream`) and, on each `card_state` frame, wakes the bound
 * device widgets **from the pushed card** — no re-fetch.
 *
 * Opt-in only ([ServerStore.realtimeEnabled], default off): it runs foreground
 * with a low-importance persistent notification. Reconnects with exponential
 * backoff; a **401** stops the service (token gone), a **404** backs off long
 * (endpoint solarisbay#806 not deployed yet) rather than hammering.
 */
class RealtimeService : Service() {

    // A never-timeout client (SSE is long-lived) — read/write timeouts disabled.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS) // keep NAT/proxy paths alive
            .retryOnConnectionFailure(true)
            .build()
    }

    private val stopping = AtomicBoolean(false)
    @Volatile private var source: EventSource? = null
    @Volatile private var attempt = 0
    // When the current stream opened (elapsedRealtime; 0 = not open). Used to reset
    // the backoff ONLY after a healthy session, so a flapping server doesn't drive a
    // reconnect storm (#79).
    @Volatile private var connectedAtMs = 0L
    // Diagnostic status shown in the ongoing notification (#48) so we can tell
    // "not connected" (HTTP error) from "connected but no events" (pinning gap).
    @Volatile private var status: String = "Verbinde …"
    @Volatile private var events = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun nowTime(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date())

    override fun onBind(intent: Intent?): IBinder? = null

    // Screen-aware (#48): hold the live SSE only while the screen is on; when it's
    // off, drop the connection (battery/Doze) and let the ~N-min backstop poll run.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> onScreenOn()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // If the platform refuses foreground (background start not allowed), bail
        // cleanly rather than risk a did-not-start-in-time crash.
        if (!startForegroundNotification()) {
            stopSelf()
            return
        }
        runCatching {
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground on every (re)start; if the platform refuses (bg start
        // on Android 12+ without exemption), stop cleanly — never crash.
        if (!startForegroundNotification()) {
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
        if (!eligible()) {
            stopSelfClean()
            return START_NOT_STICKY
        }
        stopping.set(false)
        if (isInteractive()) connect() else onScreenOff()
        return START_STICKY
    }

    private fun isInteractive(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun onScreenOn() {
        RealtimePoll.cancel(this)
        stopping.set(false)
        RealtimeLog.add(this, "Bildschirm an")
        // No transient status flip — onOpen will settle it to "Live-Updates aktiv".
        connect()
    }

    private fun onScreenOff() {
        val secs = ServerStore.pollSeconds(this)
        if (secs in 1..59) {
            // Sub-minute responsiveness is only real by keeping the SSE open — an
            // alarm can't fire that fast in Doze. So < 1 min = stay live (more battery).
            RealtimePoll.cancel(this)
            RealtimeLog.add(this, "Bildschirm aus · bleibt live (${secs}s)")
            if (source == null) connect() else setStatus("Live-Updates aktiv")
            return
        }
        // 0 or ≥ 1 min: drop the live stream to save battery.
        runCatching { source?.cancel() }
        source = null
        if (secs <= 0) {
            RealtimePoll.cancel(this)
            RealtimeLog.add(this, "Bildschirm aus · Ruhemodus (aus)")
            setStatus("Ruhemodus · Bildschirm aus")
        } else {
            RealtimeLog.add(this, "Bildschirm aus · Poll alle ${secs / 60} Min")
            setStatus("Ruhemodus · prüft alle ${secs / 60} Min")
            RealtimePoll.schedule(this)
        }
    }

    override fun onDestroy() {
        stopping.set(true)
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { source?.cancel() }
        source = null
        super.onDestroy()
    }

    /** Paired + toggle on — the only condition under which we should be running. */
    private fun eligible(): Boolean =
        ServerStore.realtimeEnabled(this) &&
            ServerStore.isConfigured(this) &&
            TokenStore.isPaired(this)

    private fun connect() {
        if (stopping.get()) return
        val base = ServerStore.baseUrl(this)?.trimEnd('/') ?: run { stopSelfClean(); return }
        val token = TokenStore.get(this) ?: run { stopSelfClean(); return }
        val req = Request.Builder()
            .url(base + SolarisConfig.NAPI + "/portal/events")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()
        // Only ever one connection + one pending reconnect in flight (#79): cancel any
        // prior source and drop a queued reconnect so callbacks from a dead stream
        // can't stack into a storm.
        handler.removeCallbacks(reconnectRunnable)
        runCatching { source?.cancel() }
        connectedAtMs = 0L
        // No "Verbinde …" flip here — a quick reconnect shouldn't disturb the calm
        // "Live-Updates aktiv" notification. onOpen confirms; a sustained outage flags.
        source = EventSources.createFactory(client).newEventSource(req, listener)
    }

    /**
     * Surface a connection problem on the ongoing notification — but only after a
     * SUSTAINED outage (a few failed retries), so brief blips/reconnects don't
     * disturb the otherwise-silent "active" state. When healthy nothing shows.
     */
    private fun flagDisconnect() {
        if (attempt >= 2) setStatus("Keine Verbindung zu Solaris – neuer Versuch …")
    }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            if (eventSource !== source) return // stale callback from a superseded stream
            // Do NOT reset the backoff here — only a session that STAYS open (see
            // handleDisconnect) is healthy. Resetting on every open let an
            // accept-then-close server drive a 2s reconnect storm (#79).
            connectedAtMs = android.os.SystemClock.elapsedRealtime()
            setStatus("Live-Updates aktiv")
            RealtimeLog.add(applicationContext, "verbunden")
            // Connection healthy → register the device-widget watch-set (#48
            // Option B, solarisbay#810) and keep re-posting to refresh its TTL.
            // Off-thread + best-effort; a failed post never affects the service.
            scheduleWatchPost()
            // Catch up on what was missed while disconnected (#138). The SSE is
            // dropped with the screen, and the server pushes only on the NEXT
            // state_changed — never a snapshot on connect — so a device that
            // flipped in that window would keep its stale card until tapped.
            // Best-effort: each tile refreshes through the normal path, and a
            // failed fetch keeps the last known card rather than repainting "aus".
            runCatching { DeviceWidgetProvider.refreshAll(applicationContext) }
            // The two ServiceBay tiles have no auto-refresh at all (#137): Android
            // never updates them (updatePeriodMillis=0) and no card_state can wake
            // them, because a ServiceBay service is not an HA entity. Screen on +
            // connection up is the moment they are about to be looked at.
            runCatching { SbServicesWidgetProvider.refreshAll(applicationContext) }
            runCatching { SbOverviewWidgetProvider.refreshAll(applicationContext) }
            // Same gap, one floor down (#124): notices published while the screen
            // was off reach nobody, and `NoticeCatchUp.drain` was called from
            // exactly one place — the screen-off poll — which RETURNS EARLY once
            // the screen is on again, before it gets to the catch-up. So the
            // alarm that fires just after the phone is picked up hands back to
            // this service and fetches nothing. Ask the server's backlog here,
            // where we know the connection is up and the user is looking.
            //
            // Only `backlog`, not `drain`: `window()` opens a second stream to
            // listen briefly, which is what the poll needs and we do not — this
            // stream IS the live one. Off-thread, because both do network I/O and
            // this is the SSE callback thread.
            thread { runCatching { NoticeCatchUp.backlog(applicationContext) } }
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            // A bad frame must never crash the service.
            when (type) {
                RealtimeProtocol.EVENT_CARD_STATE -> runCatching {
                    val ev = RealtimeProtocol.parseCardState(data) ?: return
                    DeviceWidgetProvider.wakeEntity(applicationContext, ev.entityId, ev.card)
                    // A state flip can change the active roster → nudge the overview
                    // widgets, but COALESCE it (#79): a burst of card_state frames must
                    // trigger at most one /napi/portal/active refetch, not one each.
                    coalesceActiveRefresh()
                    events++
                    // Deliberately NO per-event notification update — the ongoing
                    // notification stays calm ("Live-Updates aktiv"); constant re-posts
                    // are unhealthy. State transitions still update it.
                }
                RealtimeProtocol.EVENT_HA -> runCatching {
                    // A household notice, a fired timer or a reminder (#116) →
                    // native notification on that category's own channel. Best
                    // effort and NOT an alarm channel — see EVENT_HA.
                    val ev = RealtimeProtocol.parseHa(data) ?: return
                    NoticeNotifier.post(applicationContext, ev)
                }
                RealtimeProtocol.EVENT_SERVICEBAY -> runCatching {
                    // A new ServiceBay approval (#43) → alert the admin. The verdict
                    // itself happens in the PWA (needs the Authelia session).
                    val ev = RealtimeProtocol.parseServicebay(data) ?: return
                    postApprovalNotif(ev)
                }
            }
        }

        override fun onClosed(eventSource: EventSource) {
            handleDisconnect(eventSource, "200")
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            when (response?.code) {
                401 -> {
                    // Token invalid/revoked — don't loop; stop until re-paired/re-enabled.
                    setStatus("Token abgelehnt (401)")
                    RealtimeLog.add(applicationContext, "Token abgelehnt (401) — gestoppt")
                    stopSelfClean()
                    return
                }
                404 -> {
                    // Endpoint not deployed yet (solarisbay#806) — back off long, don't spam.
                    if (eventSource !== source) return
                    source = null
                    connectedAtMs = 0L
                    setStatus("Endpoint fehlt (404) — Server nicht bereit?")
                    RealtimeLog.add(applicationContext, "Endpoint fehlt (404)")
                    scheduleReconnect(RealtimeProtocol.NOT_DEPLOYED_BACKOFF_MS)
                    return
                }
            }
            handleDisconnect(eventSource, response?.code?.toString() ?: t?.javaClass?.simpleName ?: "?")
        }
    }

    /**
     * Single disconnect path for both `onClosed` and `onFailure` (#79). Ignores
     * callbacks from a superseded stream (so overlapping sources can't each schedule
     * a reconnect), claims the current source so a second callback for it is also
     * ignored, and resets the backoff ONLY if the session was healthy — otherwise
     * `attempt` keeps climbing so a flapping server backs off 2s→4s→…→cap instead of
     * hammering the battery.
     */
    private fun handleDisconnect(eventSource: EventSource, reason: String) {
        if (eventSource !== source) return
        source = null
        if (RealtimeProtocol.healthySession(connectedAtMs, android.os.SystemClock.elapsedRealtime())) {
            attempt = 0
        }
        connectedAtMs = 0L
        RealtimeLog.add(applicationContext, "getrennt ($reason)")
        flagDisconnect()
        scheduleReconnect(RealtimeProtocol.backoffMillis(attempt++))
    }

    /** Coalesce active-overview refreshes so a card_state burst causes one refetch (#79). */
    private val activeRefreshRunnable = Runnable {
        ActiveDevicesWidgetProvider.refreshAll(applicationContext)
    }

    private fun coalesceActiveRefresh() {
        handler.removeCallbacks(activeRefreshRunnable)
        handler.postDelayed(activeRefreshRunnable, ACTIVE_REFRESH_DEBOUNCE_MS)
    }

    /**
     * (Re)start the periodic watch-set post (#48): register the current set now,
     * then re-post every [WATCH_REPOST_MS] to refresh the server's TTL as long as
     * the service stays eligible/running. Idempotent — clears any prior schedule
     * first (a reconnect just re-arms it). Cancelled in onDestroy/stopSelfClean.
     */
    private fun scheduleWatchPost() {
        handler.removeCallbacks(watchPoster)
        WatchSet.postCurrentAsync(applicationContext) // register immediately
        handler.postDelayed(watchPoster, WATCH_REPOST_MS)
    }

    private val watchPoster = object : Runnable {
        override fun run() {
            if (stopping.get() || !eligible()) return
            WatchSet.postCurrentAsync(applicationContext)
            handler.postDelayed(this, WATCH_REPOST_MS)
        }
    }

    private val reconnectRunnable = Runnable { if (!stopping.get()) connect() }

    private fun scheduleReconnect(delayMs: Long) {
        if (stopping.get()) return
        if (!eligible()) { stopSelfClean(); return }
        // Exactly one pending reconnect — drop any already queued so callbacks from
        // overlapping dead streams can't stack into a storm (#79).
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun stopSelfClean() {
        stopping.set(true)
        handler.removeCallbacksAndMessages(null)
        runCatching { source?.cancel() }
        source = null
        RealtimePoll.cancel(this)
        stopForegroundCompat()
        stopSelf()
    }

    private fun buildNotif(): Notification {
        ensureChannel()
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, OnboardingHomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NOTIF_ACCENT)
            .setContentTitle(cloud.dopp.solaris.data.PersonaStore.name(this, getString(R.string.appName)))
            .setContentText(status)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setContentIntent(tap)
            .setShowWhen(false)
            .build()
    }

    /**
     * Post a heads-up notification for a new ServiceBay approval (#43). Since this
     * event carries the approval [id], the tap deep-links **straight to that
     * approval's verdict page** in the PWA Custom Tab (owner: direct-to-verdict) —
     * where the approve/reject buttons POST over the Authelia session (the device
     * token cannot, servicebay#2249). Uses a separate DEFAULT-importance channel so
     * it alerts, unlike the silent ongoing service notification. No-op without
     * POST_NOTIFICATIONS (API 33+); never crashes the service.
     */
    private fun postApprovalNotif(ev: RealtimeProtocol.ApprovalEvent) {
        ensureApprovalChannel()
        val tap = cloud.dopp.solaris.widget.PwaLauncher.tapPending(
            this, ev.id.hashCode(),
            cloud.dopp.solaris.widget.PwaLauncher.Routes.approval(ev.id),
        )
        val body = ev.summary.ifBlank { getString(R.string.approval_notif_generic) }
        val notif = androidx.core.app.NotificationCompat.Builder(this, APPROVAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NOTIF_ACCENT)
            .setContentTitle(getString(R.string.approval_notif_title))
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(tap)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(APPROVAL_NOTIF_BASE + (ev.id.hashCode() and 0xffff), notif)
        }
    }

    private fun ensureApprovalChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(APPROVAL_CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            APPROVAL_CHANNEL_ID,
            getString(R.string.approval_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.approval_channel_desc) }
        nm.createNotificationChannel(ch)
    }

    /**
     * Go foreground. Returns false if the platform refuses — e.g.
     * `ForegroundServiceStartNotAllowedException` when a background component
     * (WorkManager rescue / idle-poll alarm) tries to (re)start the service on
     * Android 12+ without an exemption, or an FGS-type/permission issue. MUST be
     * caught: an unguarded throw here was crashing the app repeatedly ("keeps
     * stopping"). On refusal the caller stops the service cleanly instead.
     */
    private fun startForegroundNotification(): Boolean {
        return try {
            val notif = buildNotif()
            when {
                // Android 14+ caps dataSync at ~6h/24h → use specialUse (no cap) for
                // this long-lived push connection.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(NOTIF_ID, notif)
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Update the ongoing notification's text so the user (and we) can see live state. */
    /**
     * Update the ongoing notification — but only when the text actually CHANGES, so
     * the notification isn't re-posted on every incoming event (that churn is what
     * we want to avoid). Called on real state transitions, not per event.
     */
    private fun setStatus(text: String) {
        if (text == status) return
        status = text
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotif())
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.realtime_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.realtime_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL_ID = "solaris_realtime"
        private const val NOTIF_ID = 4801

        // Separate DEFAULT-importance channel + id space for approval alerts (#43).
        private const val APPROVAL_CHANNEL_ID = "solaris_approvals"
        private const val APPROVAL_NOTIF_BASE = 4900

        /** Re-post the watch-set every ~20 min to refresh the server-side TTL (#48). */
        private const val WATCH_REPOST_MS = 20L * 60L * 1000L

        /** Debounce window that coalesces a card_state burst into one overview refetch (#79). */
        private const val ACTIVE_REFRESH_DEBOUNCE_MS = 1_500L

        /** Start the service iff opt-in && paired; else make sure it's stopped. */
        fun ensure(context: Context) {
            val ctx = context.applicationContext
            if (ServerStore.realtimeEnabled(ctx) &&
                ServerStore.isConfigured(ctx) &&
                TokenStore.isPaired(ctx)
            ) {
                start(ctx)
            } else {
                stop(ctx)
            }
        }

        fun start(context: Context) {
            val ctx = context.applicationContext
            val i = Intent(ctx, RealtimeService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            }
        }

        fun stop(context: Context) {
            val ctx = context.applicationContext
            RealtimePoll.cancel(ctx)
            runCatching { ctx.stopService(Intent(ctx, RealtimeService::class.java)) }
        }

        private const val RESCUE_WORK = "solaris_realtime_rescue"

        /**
         * Schedule the periodic rescue worker (#48) — a WorkManager backstop that
         * revives the service after an OEM kill. Unique + KEEP so repeated calls are
         * idempotent; survives reboots. Called when Live-Updates is enabled.
         */
        fun scheduleRescue(context: Context) {
            val req = androidx.work.PeriodicWorkRequestBuilder<RealtimeRescueWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES,
            ).setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build(),
            ).build()
            runCatching {
                androidx.work.WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(
                        RESCUE_WORK, androidx.work.ExistingPeriodicWorkPolicy.KEEP, req,
                    )
            }
        }

        /** Cancel the rescue worker — called when Live-Updates is disabled. */
        fun cancelRescue(context: Context) {
            runCatching {
                androidx.work.WorkManager.getInstance(context.applicationContext)
                    .cancelUniqueWork(RESCUE_WORK)
            }
        }
    }
}
