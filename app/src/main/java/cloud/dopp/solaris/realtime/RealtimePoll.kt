package cloud.dopp.solaris.realtime

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.SbApiClient
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.ui.ApprovalsActivity
import cloud.dopp.solaris.widget.PwaLauncher

/**
 * Screen-off backstop for the realtime service (#48): while the screen is off the
 * live SSE is dropped (battery/Doze), and this runs a light ~4-minute poll via a
 * Doze-tolerant alarm — checks `/napi/box/home` for a rise in pending
 * approvals and alerts, then re-arms. On screen-on the service reconnects the SSE
 * and cancels this. Best-effort: deep Doze may stretch the interval beyond 4 min
 * (Android throttles allow-while-idle alarms).
 */
object RealtimePoll {
    private const val PREFS = "realtime_poll"
    private const val K_LAST_APPROVALS = "last_approvals"
    private const val K_LAST_UPDATES = "last_updates"
    private const val REQ = 48_010
    private const val APPROVAL_CHANNEL = "solaris_approvals"
    private const val UPDATES_CHANNEL = "solaris_updates"
    private const val NOTIF_ID = 4910
    private const val NOTIF_ID_UPDATES = 4911
    // Own ids for the diagnostics test notifications (#110) so a self-test never
    // replaces — or is replaced by — a real pending alert.
    private const val NOTIF_ID_TEST_APPROVALS = 4912
    private const val NOTIF_ID_TEST_UPDATES = 4913

    private fun eligible(ctx: Context): Boolean =
        ServerStore.realtimeEnabled(ctx) && ServerStore.isConfigured(ctx) && TokenStore.isPaired(ctx)

    private fun alarmPi(ctx: Context): PendingIntent {
        val i = Intent(ctx, RealtimeIdleReceiver::class.java).setAction(ACTION_POLL)
        return PendingIntent.getBroadcast(
            ctx, REQ, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Arm the next screen-off poll at the user-configured interval (Doze-tolerant,
     * inexact — no exact-alarm permission). A configured interval of 0 disables the
     * poll entirely (pure screen-on live).
     */
    fun schedule(context: Context) {
        val ctx = context.applicationContext
        val seconds = ServerStore.pollSeconds(ctx)
        if (seconds <= 0) { cancel(ctx); return }
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + seconds * 1000L
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(ctx)) }
    }

    fun cancel(context: Context) {
        val ctx = context.applicationContext
        runCatching { ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmPi(ctx)) }
    }

    /**
     * One poll pass (called from [RealtimeIdleReceiver] on a worker thread). If the
     * screen came back on, hand back to the live service; otherwise check for new
     * approvals, alert, and re-arm.
     */
    fun run(context: Context) {
        val ctx = context.applicationContext
        if (!eligible(ctx)) return
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm != null && pm.isInteractive) {
            // Screen is on again → resume the live SSE, stop polling.
            RealtimeService.ensure(ctx)
            return
        }
        val home = try { SbApiClient(ctx).getHome() } catch (e: Exception) { null }
        if (home != null) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Alert only on a RISE (new since last seen) so a standing count doesn't
            // re-nag every pass; the stored baseline is updated regardless.
            val lastApprovals = prefs.getInt(K_LAST_APPROVALS, 0)
            if (home.pendingApprovals > lastApprovals && home.pendingApprovals > 0) {
                notifyApprovals(ctx, home.pendingApprovals)
            }
            val lastUpdates = prefs.getInt(K_LAST_UPDATES, 0)
            if (home.pendingUpdates > lastUpdates && home.pendingUpdates > 0) {
                notifyUpdates(ctx, home.pendingUpdates)
            }
            prefs.edit()
                .putInt(K_LAST_APPROVALS, home.pendingApprovals)
                .putInt(K_LAST_UPDATES, home.pendingUpdates)
                .apply()
        }
        // Household notices, timers and reminders (#116) live on the stream, not on
        // a count. So the pass asks the server's short backlog what it missed
        // (#124) and then listens briefly for what arrives while it is awake — the
        // catch-up and the fast path, side by side. See NoticeCatchUp for the
        // ceiling: a gap longer than the server's retention is still lost.
        runCatching { NoticeCatchUp.drain(ctx) }
        schedule(ctx) // re-arm the next pass
    }

    /**
     * Diagnostics (#110): deliver ONE clearly marked test notification per kind
     * through the **real** builders — same channel, same icon, same tap intent — so
     * the two things that have actually broken on this path (the icon #88/#89 and
     * the tap target #45/#109) are checkable in five seconds instead of waiting for
     * the box to have an update pending. The counts are the ones just fetched; when
     * nothing is pending the test shows 1 (a "0 Updates" alert would be nonsense).
     * Real alerts still carry real counts only — the invented part is the marker.
     */
    fun notifyTest(context: Context, approvals: Int, updates: Int) {
        val ctx = context.applicationContext
        notifyApprovals(ctx, approvals.coerceAtLeast(1), test = true)
        notifyUpdates(ctx, updates.coerceAtLeast(1), test = true)
    }

    /**
     * Diagnostics (#110): forget the persisted rise baseline so a **standing** count
     * counts as a rise again on the next screen-off pass. Without this a missed rise
     * never comes back — the baseline outlives it (see [run]).
     */
    fun resetBaseline(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(K_LAST_APPROVALS).remove(K_LAST_UPDATES).apply()
    }

    /** Suffix that makes a diagnostics notification unmistakably a test (#110). */
    private fun mark(ctx: Context, text: String, test: Boolean): String =
        if (test) ctx.getString(R.string.diag_test_notif_fmt, text) else text

    private fun notifyApprovals(ctx: Context, count: Int, test: Boolean = false) {
        ensureChannel(ctx)
        val id = if (test) NOTIF_ID_TEST_APPROVALS else NOTIF_ID
        val tap = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, ApprovalsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = mark(ctx, ctx.resources.getQuantityString(R.plurals.approvals_pending, count, count), test)
        val n = NotificationCompat.Builder(ctx, APPROVAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NOTIF_ACCENT)
            .setContentTitle(ctx.getString(R.string.approval_notif_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(tap)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(id, n) }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(APPROVAL_CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(APPROVAL_CHANNEL, ctx.getString(R.string.approval_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = ctx.getString(R.string.approval_channel_desc) },
        )
    }

    /**
     * Alert that ServiceBay updates are pending (#45). Informational only — the
     * update is triggered in ServiceBay (solarisbay#827), not natively; a tap opens
     * the ServiceBay surface in the PWA Custom Tab. Own DEFAULT-importance channel so
     * updates and approvals are separately mutable in system settings.
     */
    private fun notifyUpdates(ctx: Context, count: Int, test: Boolean = false) {
        ensureUpdatesChannel(ctx)
        // Tap opens the ServiceBay admin (where updates are applied), NOT Solaris
        // chat (#45). Admin is on a different host than the paired Solaris base, so
        // this is an absolute URL derived from it (admin.<apex>) — the same helper
        // the two ServiceBay widgets tap through since #109.
        val id = if (test) NOTIF_ID_TEST_UPDATES else NOTIF_ID_UPDATES
        val tap = PwaLauncher.serviceBayTap(ctx, id)
        val text = mark(ctx, ctx.resources.getQuantityString(R.plurals.updates_pending, count, count), test)
        val n = NotificationCompat.Builder(ctx, UPDATES_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NOTIF_ACCENT)
            .setContentTitle(ctx.getString(R.string.updates_notif_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(tap)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(id, n) }
    }

    private fun ensureUpdatesChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(UPDATES_CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(UPDATES_CHANNEL, ctx.getString(R.string.updates_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = ctx.getString(R.string.updates_channel_desc) },
        )
    }

    const val ACTION_POLL = "cloud.dopp.solaris.realtime.IDLE_POLL"
}
