package cloud.dopp.haus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.haus.R
import cloud.dopp.haus.data.Energy
import cloud.dopp.haus.data.EnergyFlow

/**
 * The one energy widget (owner, 2026-11-02): "jetzt" and "heute" together.
 *
 * - Two cells high and more: the four flow legs now (PV / Haus / Netz / Akku,
 *   coloured and worded by direction) above what the counters added today
 *   (erzeugt, eingespeist, bezogen, Akku geladen, kWh).
 * - One cell high: the grid's balance now (or the house's draw), and with a
 *   little more room one line of heute.
 *
 * The body opens Haus's Energie page ([HausPages.ENERGIE]); the arrows read again.
 */
object EnergyRender {

    fun build(
        ctx: Context,
        appWidgetId: Int,
        energy: Energy?,
        paired: Boolean,
        onTap: PendingIntent,
        onRefresh: PendingIntent,
    ): RemoteViews {
        val opts = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(appWidgetId)
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return if (full(minW, minH)) buildFull(ctx, energy, paired, onTap, onRefresh)
        else buildSmall(ctx, energy, paired, onTap, onRefresh, showToday(minW, minH))
    }

    /**
     * Room for jetzt and heute as cells: two rows of cells need two cells of
     * height. A size the launcher did not tell (0) draws the full widget — the
     * placed default is 4×2. Pure, so it is JVM-testable.
     */
    fun full(minW: Int, minH: Int): Boolean = (minW == 0 && minH == 0) || minH >= 100

    /** The small tier's heute line: with a touch more room than the bare 2×1. */
    fun showToday(minW: Int, minH: Int): Boolean = minH >= 70 || minW >= 140

    /**
     * Heute in one line for the small tier: what the sun made, else what came
     * from the grid — null when Haus said nothing about today.
     */
    fun todayLine(energy: Energy?): String? {
        val slots = EnergyStyle.orderedTotals(energy?.totals.orEmpty())
        val pv = slots[0]?.today
        val drawn = slots[2]?.today
        return when {
            pv != null -> "heute PV " + EnergyStyle.formatKwh(pv)
            drawn != null -> "heute Netz " + EnergyStyle.formatKwh(drawn)
            else -> null
        }
    }

    private fun buildSmall(
        ctx: Context,
        e: Energy?,
        paired: Boolean,
        onTap: PendingIntent,
        onRefresh: PendingIntent,
        showToday: Boolean,
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_energy_small)
        v.setOnClickPendingIntent(R.id.e_root, onTap)
        v.setOnClickPendingIntent(R.id.e_refresh, onRefresh)
        v.setViewVisibility(R.id.e_today, View.GONE)
        if (!paired) {
            small(v, "Energie", "koppeln", "gerät koppeln", EnergyStyle.NEUTRAL)
            return v
        }
        val g = e?.grid ?: e?.house
        val w = g?.watts
        if (w == null) {
            small(v, "Netz", if (e == null) "…" else "—", if (e == null) "wird geladen" else "keine Daten", EnergyStyle.NEUTRAL)
            return v
        }
        val onGrid = g.sense == "grid"
        val sub = if (onGrid) EnergyStyle.directionFor(g.sense, w) else "Verbrauch"
        small(v, if (onGrid) "Jetzt · Netz" else "Jetzt · Haus", EnergyStyle.formatWatts(w), sub, EnergyStyle.colorFor(g.sense, w))
        val today = todayLine(e)
        if (showToday && today != null) {
            v.setTextViewText(R.id.e_today, today)
            v.setViewVisibility(R.id.e_today, View.VISIBLE)
        }
        return v
    }

    /** The compact card: big coloured value, an all-caps label, the direction, the rail and icon in [accent]. */
    private fun small(v: RemoteViews, title: String, value: String, sub: String, accent: Int) {
        v.setTextViewText(R.id.e_title, title)
        v.setTextViewText(R.id.e_main, value)
        v.setTextColor(R.id.e_main, accent)
        v.setTextViewText(R.id.e_sub, sub)
        v.setInt(R.id.e_accent, "setColorFilter", accent)
        v.setInt(R.id.e_icon, "setColorFilter", accent)
    }

    private fun buildFull(ctx: Context, e: Energy?, paired: Boolean, onTap: PendingIntent, onRefresh: PendingIntent): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_energy)
        v.setOnClickPendingIntent(R.id.e_root, onTap)
        v.setOnClickPendingIntent(R.id.e_refresh, onRefresh)
        val note = when {
            !paired -> "koppeln"
            e == null -> "…"
            else -> null
        }
        val legs = EnergyStyle.orderedLegs(e?.pv, e?.house, e?.grid, e?.battery)
        for (i in 0..3) now(v, i, legs[i], LEG_LABELS[i], note)
        val totals = EnergyStyle.orderedTotals(e?.totals.orEmpty())
        for (i in 0..3) {
            v.setTextViewText(TODAY_LABELS[i], EnergyStyle.TOTAL_LABELS[i])
            val kwh = totals[i]?.today
            v.setTextViewText(TODAY_VALUES[i], note ?: EnergyStyle.formatKwh(kwh))
            v.setTextColor(TODAY_VALUES[i], if (note == null && kwh != null) EnergyStyle.TOTAL_COLORS[i] else EnergyStyle.NEUTRAL)
        }
        return v
    }

    /** One "jetzt" cell: the leg's label, its value in its direction's colour, and the direction word beside the label. */
    private fun now(v: RemoteViews, i: Int, f: EnergyFlow?, fallbackLabel: String, note: String?) {
        val label = f?.label?.ifBlank { fallbackLabel } ?: fallbackLabel
        if (note != null) {
            v.setTextViewText(NOW_LABELS[i], label)
            v.setTextViewText(NOW_VALUES[i], note)
            v.setTextColor(NOW_VALUES[i], EnergyStyle.NEUTRAL)
            return
        }
        val w = f?.watts
        val dir = EnergyStyle.directionFor(f?.sense, w)
        v.setTextViewText(NOW_LABELS[i], if (dir.isBlank()) label else "$label · $dir")
        v.setTextViewText(NOW_VALUES[i], EnergyStyle.formatWatts(w))
        v.setTextColor(NOW_VALUES[i], EnergyStyle.colorFor(f?.sense, w))
    }

    private val LEG_LABELS = listOf("PV", "Haus", "Netz", "Akku")
    private val NOW_LABELS = intArrayOf(R.id.e_now_l0, R.id.e_now_l1, R.id.e_now_l2, R.id.e_now_l3)
    private val NOW_VALUES = intArrayOf(R.id.e_now_v0, R.id.e_now_v1, R.id.e_now_v2, R.id.e_now_v3)
    private val TODAY_LABELS = intArrayOf(R.id.e_today_l0, R.id.e_today_l1, R.id.e_today_l2, R.id.e_today_l3)
    private val TODAY_VALUES = intArrayOf(R.id.e_today_v0, R.id.e_today_v1, R.id.e_today_v2, R.id.e_today_v3)
}
