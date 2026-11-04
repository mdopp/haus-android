package cloud.dopp.homes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.Card
import cloud.dopp.homes.data.ScreenGate
import cloud.dopp.homes.ui.StartDevices

/**
 * One device per widget (restored by the owner, 2026-11-03: the active and
 * all-devices widgets went, not this one). Each instance is bound to one Home
 * Assistant entity through [WidgetConfigActivity]; it draws the entity's
 * state as a picture ([DeviceLook]) with its name below, and the whole tile is the tap ([tapFor]): a light, a
 * switch, a cover toggles and a lock opens its chooser, through the same
 * [WidgetActionReceiver] as the start screen; anything else opens its page on
 * Homes. The live stream keeps it current (`card_state`, [wakeEntity]); a
 * failed read draws the card read last ([WidgetCache]).
 */
class DeviceWidgetProvider : FedWidgetProvider(ACTION_REFRESH, 900_000, tapStartsLive = false) {

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach {
            WidgetStore.unbind(context, it)
            WidgetCache.clear(context, it)
        }
        // one entity fewer to watch, perhaps no fed widget left
        cloud.dopp.homes.realtime.WatchSet.postCurrentAsync(context)
        cloud.dopp.homes.realtime.Live.recheck(context)
    }

    override fun refresh(context: Context, appWidgetId: Int) {
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
        async(context, appWidgetId) {
            val fresh = runCatching { ApiClient(context.applicationContext).getCard(entityId) }.getOrNull()
            if (fresh != null) {
                WidgetCache.putCard(context, appWidgetId, fresh)
                mgr.updateAppWidget(appWidgetId, build(context, appWidgetId, fresh))
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.homes.widget.DEVICE_REFRESH"

        /**
         * The tile of [appWidgetId], drawn from [card] — the read one, or none
         * yet (the bound name, the picture in the grey of nothing known).
         * Picture first (owner, 2026-11-03, [DeviceLook]); one cell high and
         * narrow, the words only where the picture cannot say it.
         */
        fun build(ctx: Context, appWidgetId: Int, card: Card?): RemoteViews {
            val opts = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(appWidgetId)
            val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val wide = wide(minW, minH)
            val v = RemoteViews(ctx.packageName, if (wide) R.layout.widget_device_wide else R.layout.widget_device)
            val domain = card?.domain ?: WidgetStore.domain(ctx, appWidgetId)
            val deviceClass = card?.deviceClass ?: WidgetStore.deviceClass(ctx, appWidgetId)
            val name = DeviceLook.shortName(WidgetStore.name(ctx, appWidgetId).ifBlank { card?.name.orEmpty() })
            val tint = DeviceLook.tint(domain, deviceClass, card)
            v.setImageViewResource(R.id.d_icon, DeviceLook.icon(domain, deviceClass, card?.state))
            v.setInt(R.id.d_icon, "setColorFilter", tint)
            v.setTextViewText(R.id.d_name, name)
            val words = card?.let { StartDevices.stateText(it) } ?: "…"
            val roomy = wide || minH >= TALL
            v.setTextViewText(R.id.d_state, if (roomy || card == null) words else DeviceLook.compactWords(card, words))
            val showWords = roomy || DeviceLook.needsWords(card)
            v.setViewVisibility(R.id.d_state, if (showWords) View.VISIBLE else View.GONE)
            val level = DeviceLook.level(card)
            v.setViewVisibility(R.id.d_bar, if (level != null) View.VISIBLE else View.GONE)
            if (level != null) v.setProgressBar(R.id.d_bar, 100, level, false)
            // the words the picture stands for, for a screen reader
            v.setContentDescription(R.id.d_body, "$name, $words")
            val tap = tapFor(ctx, appWidgetId, card)
            v.setOnClickPendingIntent(R.id.d_root, tap)
            v.setOnClickPendingIntent(R.id.d_body, tap)
            return v
        }

        /** Two cells wide and no more than about one high: the picture beside the words. Pure. */
        fun wide(minW: Int, minH: Int): Boolean = minW >= 130 && minW >= minH * 3 / 2

        /** From this height on the state is in words below the picture too. */
        const val TALL = 110

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
            v.setInt(R.id.d_icon, "setColorFilter", DeviceLook.OFF)
            v.setTextViewText(R.id.d_name, ctx.getString(R.string.widget_device_label))
            v.setTextViewText(R.id.d_state, ctx.getString(R.string.widget_device_setup))
            v.setViewVisibility(R.id.d_state, View.VISIBLE)
            val pick = Intent(ctx, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val tap = PendingIntent.getActivity(ctx, 100_000 + appWidgetId, pick, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.d_root, tap)
            v.setOnClickPendingIntent(R.id.d_body, tap)
            return v
        }

        /** Ask a bound instance to read again. */
        fun requestRefresh(ctx: Context, appWidgetId: Int) {
            ctx.sendBroadcast(refreshIntent(ctx, DeviceWidgetProvider::class.java, ACTION_REFRESH, appWidgetId))
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
