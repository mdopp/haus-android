package cloud.dopp.haus.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.haus.R
import cloud.dopp.haus.data.Card
import java.util.Locale

/**
 * Size-adaptive [RemoteViews] for the active-devices overview widget (#28).
 * Small = a compact count ("3 an"); large = a header count + a real scrollable
 * ListView collection (backed by [ActiveDevicesRemoteViewsService]) so **every**
 * active device shows and scrolls — the ListView adapter/rows are wired in the
 * provider, this object only builds the header + empty state + the small tier.
 * Pure enough (state labels + tier pick) to be JVM-tested.
 */
object ActiveDevicesRender {

    /** The two tiers: a compact grouped summary vs. the scrollable list. */
    enum class Tier { SMALL, LARGE }

    /** Icon slots available in the compact tier for per-type chips (see layout). */
    const val CHIP_SLOTS = 5

    /**
     * Large once the host box is roughly **2 cells tall** — a 4x2 / full-width
     * 3-tall widget already fits 5-6 scrollable rows, so the list shouldn't wait
     * for a big square. Boundary lowered from 180dp to ~110dp height (device
     * feedback #28); width stays modest so a flat 1-row strip still reads as the
     * compact grouped summary.
     */
    fun sizeTier(minW: Int, minH: Int): Tier =
        if (minW >= 160 && minH >= 110) Tier.LARGE else Tier.SMALL

    /**
     * Compact-tier scaffold: a header count **plus** a grouped-by-type chip row
     * (icon + "N an/offen" per domain), not a lone grand total. [active] null →
     * loading ("…"); empty → "Alles aus". Bounded to [CHIP_SLOTS] chips with a
     * "+N" overflow, RemoteViews-safe.
     */
    fun small(ctx: Context, active: List<Card>?): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_active_small)
        val count = active?.size
        v.setTextViewText(
            R.id.av_count,
            when {
                count == null -> "…"
                count == 0 -> ctx.getString(R.string.active_none)
                else -> ctx.getString(R.string.active_count, count)
            },
        )
        renderChips(ctx, v, active)
        return v
    }

    private val CHIP_ICON = intArrayOf(
        R.id.av_chip0_icon, R.id.av_chip1_icon, R.id.av_chip2_icon,
        R.id.av_chip3_icon, R.id.av_chip4_icon,
    )
    private val CHIP_TEXT = intArrayOf(
        R.id.av_chip0_text, R.id.av_chip1_text, R.id.av_chip2_text,
        R.id.av_chip3_text, R.id.av_chip4_text,
    )

    /** Fill the bounded chip slots from the grouped-by-type summary; hide the rest. */
    private fun renderChips(ctx: Context, v: RemoteViews, active: List<Card>?) {
        val groups = if (active == null) emptyList() else ActiveSummary.byType(active)
        val shown = groups.take(CHIP_SLOTS)
        for (i in 0 until CHIP_SLOTS) {
            val g = shown.getOrNull(i)
            if (g == null) {
                v.setViewVisibility(CHIP_ICON[i], View.GONE)
                v.setViewVisibility(CHIP_TEXT[i], View.GONE)
            } else {
                v.setViewVisibility(CHIP_ICON[i], View.VISIBLE)
                v.setViewVisibility(CHIP_TEXT[i], View.VISIBLE)
                v.setImageViewResource(CHIP_ICON[i], DeviceIcons.forDomain(g.domain))
                v.setTextViewText(CHIP_TEXT[i], g.label)
            }
        }
        val overflow = groups.size - shown.size
        if (overflow > 0) {
            v.setViewVisibility(R.id.av_chip_more, View.VISIBLE)
            v.setTextViewText(R.id.av_chip_more, ctx.getString(R.string.active_types_more, overflow))
        } else {
            v.setViewVisibility(R.id.av_chip_more, View.GONE)
        }
    }

    /**
     * Large-tier scaffold: just the header count + empty-view visibility. The row
     * list itself is a ListView collection wired in the provider (setRemoteAdapter
     * + notifyAppWidgetViewDataChanged), so this no longer paints per-device rows.
     * [active] null → loading ("…"); empty → show the "Alles aus" empty view.
     */
    fun large(ctx: Context, active: List<Card>?): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_active_large)
        val count = active?.size ?: 0
        v.setTextViewText(
            R.id.av_count,
            if (active == null) "…" else ctx.getString(R.string.active_count, count),
        )
        // Empty view is driven by setEmptyView on the ListView; keep it hidden here so
        // the loading/populated states don't flash it. (The list shows it when empty.)
        v.setViewVisibility(R.id.av_empty, View.GONE)
        return v
    }

    /** Short, German state label for a row — mirrors [WidgetRender.stateLabel]. */
    fun stateLabel(card: Card): String = when (card.domain) {
        "light" -> card.brightnessPct?.let { "an · $it %" } ?: "an"
        "switch" -> "an"
        "cover" -> card.position?.let { p ->
            when {
                p >= 99 -> "offen"
                p <= 1 -> "zu"
                else -> "$p % offen"
            }
        } ?: "offen"
        // The bolt, never the door (#84) — and "unbekannt" stays visibly its own.
        "lock" -> WidgetRender.lockLabel(card.state)
        "climate" -> card.temperature?.let { String.format(Locale.GERMANY, "%.1f °", it) } ?: (card.state ?: "an")
        else -> {
            val s = card.state ?: "an"
            if (!card.unit.isNullOrBlank()) "$s ${card.unit}" else s
        }
    }
}
