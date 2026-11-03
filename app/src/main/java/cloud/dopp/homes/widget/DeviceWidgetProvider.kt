package cloud.dopp.homes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.Card
import cloud.dopp.homes.data.ScreenGate
import cloud.dopp.homes.ui.StartDevices
import kotlin.concurrent.thread

/**
 * One device per widget (restored by the owner, 2026-11-03: the active and
 * all-devices widgets went, not this one). Each instance is bound to one Home
 * Assistant entity through [WidgetConfigActivity]; it draws the entity's
 * icon, name and state, and the whole tile is the tap ([tapFor]): a light, a
 * switch, a cover toggles and a lock opens its chooser, through the same
 * [WidgetActionReceiver] as the start screen; anything else opens its page on
 * Homes. The live stream keeps it current (`card_state`, [wakeEntity]); a
 * failed read draws the card read last ([WidgetCache]).
 */
class DeviceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        refresh(context, appWidgetId)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach {
            WidgetStore.unbind(context, it)
            WidgetCache.clear(context, it)
        }
        // one entity fewer to watch, perhaps no fed widget left
        cloud.dopp.homes.realtime.WatchSet.postCurrentAsync(context)
        cloud.dopp.homes.realtime.Live.recheck(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) refresh(context, id)
        }
    }

    private fun refresh(context: Context, appWidgetId: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        val entityId = WidgetStore.entityId(context, appWidgetId)
        if (entityId == null) {
            runCatching { mgr.updateAppWidget(appWidgetId, unbound(context, appWidgetId)) }
            return
        }
        // the card read last at once, then the fresh one
        val cached = WidgetCache.getCard(context, appWidgetId)
        runCatching { mgr.updateAppWidget(appWidgetId, build(context, appWidgetId, cached)) }
        if (!ScreenGate.open(context)) return // a widget nobody sees reads nothing
        val pending = goAsync()
        thread {
            try {
                val fresh = runCatching { ApiClient(context.applicationContext).getCard(entityId) }.getOrNull()
                if (fresh != null) {
                    WidgetCache.putCard(context, appWidgetId, fresh)
                    mgr.updateAppWidget(appWidgetId, build(context, appWidgetId, fresh))
                }
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.homes.widget.DEVICE_REFRESH"

        /** The tile of [appWidgetId], drawn from [card] — the read one, or none yet (the bound name, "…"). */
        fun build(ctx: Context, appWidgetId: Int, card: Card?): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_device)
            val domain = card?.domain ?: WidgetStore.domain(ctx, appWidgetId)
            val name = WidgetStore.name(ctx, appWidgetId).ifBlank { card?.name.orEmpty() }
            v.setImageViewResource(R.id.d_icon, DeviceIcons.forState(domain, card?.state))
            val on = card?.isOn == true
            v.setInt(R.id.d_icon, "setColorFilter", if (on) ON else OFF)
            v.setTextViewText(R.id.d_name, name)
            v.setTextViewText(R.id.d_state, card?.let { StartDevices.stateText(it) } ?: "…")
            v.setOnClickPendingIntent(R.id.d_root, tapFor(ctx, appWidgetId, card))
            return v
        }

        /** The icon of a device that is on or open; otherwise the quiet grey. */
        const val ON = 0xFFFFC107.toInt()
        const val OFF = 0xFF9AA0AE.toInt()

        /**
         * The tile's tap: switch it as the start screen does (a lock opens its
         * chooser, with the latch only when its card names one), or, for a
         * device a tap cannot switch, its page on Homes (`von=widget.device`).
         */
        fun tapFor(ctx: Context, appWidgetId: Int, card: Card?): PendingIntent {
            val entityId = WidgetStore.entityId(ctx, appWidgetId).orEmpty()
            val domain = DeviceShortcuts.domainOf(entityId)
            if (domain !in StartDevices.ACTING) {
                return HomesPages.tap(ctx, appWidgetId, HomesPages.item(card?.page), WidgetKind.DEVICE)
            }
            val name = WidgetStore.name(ctx, appWidgetId).ifBlank { entityId }
            return PendingIntent.getBroadcast(
                ctx, 200_000 + appWidgetId,
                WidgetActionReceiver.tap(ctx, entityId, name, card?.supportedFeatures),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Not bound yet (the picker was left): the tap opens the picker. */
        private fun unbound(ctx: Context, appWidgetId: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_device)
            v.setInt(R.id.d_icon, "setColorFilter", OFF)
            v.setTextViewText(R.id.d_name, ctx.getString(R.string.widget_device_label))
            v.setTextViewText(R.id.d_state, ctx.getString(R.string.widget_device_setup))
            val pick = Intent(ctx, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(
                R.id.d_root,
                PendingIntent.getActivity(ctx, 100_000 + appWidgetId, pick, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            return v
        }

        private fun refreshPending(ctx: Context, appWidgetId: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 900_000 + appWidgetId,
                Intent(ctx, DeviceWidgetProvider::class.java).setAction(ACTION_REFRESH)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** Ask a bound instance to read again. */
        fun requestRefresh(ctx: Context, appWidgetId: Int) {
            ctx.sendBroadcast(
                Intent(ctx, DeviceWidgetProvider::class.java).setAction(ACTION_REFRESH)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
        }

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, DeviceWidgetProvider::class.java))

        /** Every widget of [entityId] reads again: after a tap, from the app or a widget. */
        fun refreshEntity(ctx: Context, entityId: String) {
            ids(ctx).filter { WidgetStore.entityId(ctx, it) == entityId }.forEach { requestRefresh(ctx, it) }
        }

        /** A `card_state` of the live stream: every widget of that entity draws it, no read. */
        fun wakeEntity(ctx: Context, entityId: String, card: Card): Int {
            val mgr = AppWidgetManager.getInstance(ctx)
            var n = 0
            for (id in ids(ctx)) {
                if (WidgetStore.entityId(ctx, id) != entityId) continue
                WidgetCache.putCard(ctx, id, card)
                runCatching { mgr.updateAppWidget(id, build(ctx, id, card)) }
                n++
            }
            return n
        }

        /** The entities the placed device widgets show: the live stream's watch set. */
        fun boundEntities(ctx: Context): List<String> = ids(ctx).toList().mapNotNull { WidgetStore.entityId(ctx, it) }
    }
}
