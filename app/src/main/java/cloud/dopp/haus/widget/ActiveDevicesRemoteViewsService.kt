package cloud.dopp.haus.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import cloud.dopp.haus.R
import cloud.dopp.haus.data.Card

/**
 * Backs the scrollable active-devices ListView (#28) with a per-row [RemoteViews]
 * so the large widget shows **every** active device and scrolls — the standard
 * Android collection-widget pattern that a fixed set of slots could never do.
 *
 * The factory renders from the persisted cache ([WidgetStore.activeCacheJson]) so
 * a fresh draw is **instant** (stale-then-fresh): the provider fires the async
 * `listActive()`, and on completion updates the cache + calls
 * `notifyAppWidgetViewDataChanged`, which re-runs [onDataSetChanged] here.
 *
 * NB: the real speed fix is a server-side batch/active endpoint;
 * this cache only hides the per-entity fan-out latency behind the last-known list.
 */
class ActiveDevicesRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ActiveDevicesFactory(applicationContext)
}

private class ActiveDevicesFactory(
    private val ctx: Context,
) : RemoteViewsService.RemoteViewsFactory {

    private var cards: List<Card> = emptyList()

    override fun onCreate() {}

    /** Reload the cached list. Called on create and on notifyAppWidgetViewDataChanged. */
    override fun onDataSetChanged() {
        cards = ActiveCache.decode(WidgetStore.activeCacheJson(ctx))
    }

    override fun onDestroy() {
        cards = emptyList()
    }

    override fun getCount(): Int = cards.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = RemoteViews(ctx.packageName, R.layout.item_active_row)
        val card = cards.getOrNull(position)
            ?: return row.also { it.setViewVisibility(R.id.av_item_root, View.GONE) }

        // A lock row is drawn by its state (#91), like the device tile — the row
        // already spells the state out next to it, the glyph makes it scannable.
        row.setImageViewResource(R.id.av_item_icon, DeviceIcons.forState(card.domain, card.state))
        row.setInt(R.id.av_item_icon, "setColorFilter", accentFor(card.domain))
        row.setTextViewText(R.id.av_item_name, card.name.ifBlank { card.entityId })
        row.setTextViewText(R.id.av_item_state, ActiveDevicesRender.stateLabel(card))
        // Lock badge (#38/#84): sensitive devices (garage/door/gate covers, locks,
        // alarm panels) need a confirm.
        row.setViewVisibility(R.id.av_item_lock, if (card.isSensitive) View.VISIBLE else View.GONE)

        // A row completes the ListView's PendingIntentTemplate (set in the
        // provider): a tap opens the house page, like the tile itself.
        row.setOnClickFillInIntent(R.id.av_item_root, Intent())
        return row
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false

    /** Accent state color per domain — mirrors [ActiveDevicesRender]. */
    private fun accentFor(domain: String): Int = when (domain) {
        "light" -> 0xFFFFC107.toInt()
        "cover" -> 0xFF7E9CFF.toInt()
        "switch" -> 0xFF66BB6A.toInt()
        "climate" -> 0xFFFF8A65.toInt()
        // A lock in this list is unsecured (#84) — amber, not the calm green.
        "lock" -> 0xFFFFA726.toInt()
        else -> 0xFF66BB6A.toInt()
    }
}
