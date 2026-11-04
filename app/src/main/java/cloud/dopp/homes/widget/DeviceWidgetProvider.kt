package cloud.dopp.homes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.Card
import cloud.dopp.homes.data.Napi.KannKey
import cloud.dopp.homes.data.ScreenGate
import cloud.dopp.homes.ui.StartDevices
import cloud.dopp.homes.verbindung.Verbindung

/**
 * One device per widget (restored by the owner, 2026-11-03: the active and
 * all-devices widgets went, not this one). Each instance is bound to one Home
 * Assistant entity through [WidgetConfigActivity]; it draws the entity's
 * state as a picture ([DeviceLook]) with its name below, and the whole tile is the tap ([tapFor]): a light, a
 * switch, a cover toggles and a lock opens its chooser, through the same
 * [WidgetActionReceiver] as the start screen; anything else opens its page on
 * Homes. The live stream keeps it current (`card_state`, [wakeEntity]); a
 * failed read draws the card read last ([WidgetCache]), and while Homes
 * cannot be reached greyed, with since when ([Verbindung.stand]).
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
        // the card read last at once, then the fresh one
        val entityId = zeichne(context, appWidgetId) ?: return
        if (!ScreenGate.open(context)) return // a widget nobody sees reads nothing
        async(context, appWidgetId) {
            val fresh = runCatching { ApiClient(context.applicationContext).getCard(entityId) }.getOrNull()
            if (fresh != null) {
                WidgetCache.putCard(context, appWidgetId, fresh)
                AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, build(context, appWidgetId, fresh))
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.homes.widget.DEVICE_REFRESH"

        /** Every placed device widget draws its card read last, no read: the line to Homes changed. */
        fun zeichnen(ctx: Context) = ids(ctx).forEach { zeichne(ctx, it) }

        /** The card read last of [appWidgetId], no read; its entity, or null with none to read (unbound, gone from the box). */
        private fun zeichne(ctx: Context, appWidgetId: Int): String? {
            val mgr = AppWidgetManager.getInstance(ctx)
            val entityId = WidgetStore.entityId(ctx, appWidgetId)
            if (entityId == null) {
                runCatching { mgr.updateAppWidget(appWidgetId, unbound(ctx, appWidgetId)) }
                return null
            }
            if (WidgetFallback.weg(ctx, appWidgetId, KannKey.GERAET)) return null // the box lost its devices
            runCatching { mgr.updateAppWidget(appWidgetId, build(ctx, appWidgetId, WidgetCache.getCard(ctx, appWidgetId))) }
            return entityId
        }

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
            // Homes cannot be reached: the card read last, greyed, and since when instead of its words
            val stand = card?.let { Verbindung.stand(ctx) }
            val tint = if (stand != null) DeviceLook.OFF else DeviceLook.tint(domain, deviceClass, card)
            v.setImageViewResource(R.id.d_icon, DeviceLook.icon(domain, deviceClass, card?.state))
            v.setInt(R.id.d_icon, "setColorFilter", tint)
            v.setTextViewText(R.id.d_name, name)
            val words = card?.let { StartDevices.stateText(it) } ?: "…"
            val roomy = wide || minH >= TALL
            val (line, shown) = stateLine(card, words, roomy, stand)
            v.setTextViewText(R.id.d_state, line)
            // „Stand 08:14“ in one small cell: a little smaller than the state's words
            if (stand != null && !roomy) v.setTextViewTextSize(R.id.d_state, TypedValue.COMPLEX_UNIT_SP, STAND_SP)
            v.setViewVisibility(R.id.d_state, if (shown) View.VISIBLE else View.GONE)
            val level = DeviceLook.level(card)?.takeIf { stand == null }
            v.setViewVisibility(R.id.d_bar, if (level != null) View.VISIBLE else View.GONE)
            if (level != null) v.setProgressBar(R.id.d_bar, 100, level, false)
            // the words the picture stands for, for a screen reader
            v.setContentDescription(R.id.d_body, listOfNotNull(name, words, stand).joinToString(", "))
            val tap = tapFor(ctx, appWidgetId, card)
            v.setOnClickPendingIntent(R.id.d_root, tap)
            v.setOnClickPendingIntent(R.id.d_body, tap)
            return v
        }

        /**
         * The words under the name, and whether they show: since when ([stand])
         * while Homes cannot be reached, else the state — short in a small cell —,
         * hidden where the picture says it alone.
         */
        private fun stateLine(card: Card?, words: String, roomy: Boolean, stand: String?): Pair<String, Boolean> = when {
            stand != null -> stand to true
            roomy || card == null -> words to (roomy || DeviceLook.needsWords(card))
            else -> DeviceLook.compactWords(card, words) to DeviceLook.needsWords(card)
        }

        /** Two cells wide and no more than about one high: the picture beside the words. Pure. */
        fun wide(minW: Int, minH: Int): Boolean = minW >= 130 && minW >= minH * 3 / 2

        private const val STAND_SP = 9f

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
