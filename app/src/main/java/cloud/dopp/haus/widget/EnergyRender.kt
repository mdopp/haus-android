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
 * The one energy widget (owner, 2026-11-02; redrawn 2026-11-03 after the
 * first version on his phone): "Jetzt" and "Heute" together, filling the
 * cell, one frame, no capitals, green only for PV and feeding the grid.
 *
 * - Two cells high and more: the four flow legs now (PV / Haus / Netz / Akku,
 *   two rows of two, worded by direction) above what the counters added today
 *   (erzeugt, eingespeist, bezogen, Akku geladen, kWh). While Haus says
 *   nothing about today (a box not yet updated), the Heute part is left out
 *   and Jetzt takes the whole cell — never a row of dashes.
 * - One cell high: the grid's balance now (or the house's draw), and with a
 *   little more room one line of Heute.
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
        else buildSmall(ctx, energy, paired, onTap, onRefresh, showSub(minH), showToday(minH))
    }

    /**
     * Room for jetzt and heute as cells: two rows of cells need two cells of
     * height. A size the launcher did not tell (0) draws the full widget — the
     * placed default is 4×2. Pure, so it is JVM-testable.
     */
    fun full(minW: Int, minH: Int): Boolean = (minW == 0 && minH == 0) || minH >= 110

    /** The small tier's second line (the state while loading, "Jetzt"): only with more height than the bare 2×1. */
    fun showSub(minH: Int): Boolean = minH >= 85

    /** The small tier's line of heute: only with clearly more height than 2×1. */
    fun showToday(minH: Int): Boolean = minH >= 95

    /**
     * A leg's word in a cell: what it does, so the colour needs no legend —
     * PV, Haus, Ins Netz or Netzbezug, Akku
     * lädt or entlädt.
     */
    fun legWord(f: EnergyFlow?, fallback: String): String {
        val w = f?.watts
        return when (f?.sense) {
            "grid" -> when {
                w == null -> "Netz"
                w < 0 -> "Ins Netz"
                else -> "Netzbezug"
            }
            "battery" -> if (w == null) "Akku" else "Akku " + EnergyStyle.directionFor("battery", w)
            else -> f?.label?.ifBlank { fallback } ?: fallback
        }
    }

    /**
     * Heute in one line for the small tier: what the sun made, else what came
     * from the grid — null when Haus said nothing about today.
     */
    fun todayLine(energy: Energy?): String? {
        val slots = EnergyStyle.orderedTotals(energy?.totals.orEmpty())
        val pv = slots[0]?.today
        val drawn = slots[2]?.today
        return when {
            pv != null -> "Heute " + EnergyStyle.formatKwh(pv) + " erzeugt"
            drawn != null -> "Heute " + EnergyStyle.formatKwh(drawn) + " Netzbezug"
            else -> null
        }
    }

    /** Does Haus say anything about today? Without it the Heute part is left out. */
    fun hasToday(energy: Energy?): Boolean = energy?.totals.orEmpty().any { it.today != null }

    private fun buildSmall(
        ctx: Context,
        e: Energy?,
        paired: Boolean,
        onTap: PendingIntent,
        onRefresh: PendingIntent,
        showSub: Boolean,
        showToday: Boolean,
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_energy_small)
        v.setOnClickPendingIntent(R.id.e_root, onTap)
        v.setOnClickPendingIntent(R.id.e_refresh, onRefresh)
        v.setViewVisibility(R.id.e_today, View.GONE)
        v.setViewVisibility(R.id.e_sub, if (showSub) View.VISIBLE else View.GONE)
        if (!paired) {
            small(v, "Energie", "—", "In der App koppeln", EnergyStyle.MUTED)
            return v
        }
        val g = e?.grid ?: e?.house
        val w = g?.watts
        if (w == null) {
            small(v, "Jetzt", if (e == null) "…" else "—", if (e == null) "Wird geladen" else "Keine Daten", EnergyStyle.MUTED)
            return v
        }
        val onGrid = g.sense == "grid"
        val title = if (onGrid) legWord(g, "Netz") else "Haus"
        small(v, title, EnergyStyle.formatWatts(w), if (onGrid) "Jetzt, Netz" else "Jetzt, Verbrauch", EnergyStyle.colorFor(g.sense, w))
        val today = todayLine(e)
        if (showToday && today != null) {
            v.setTextViewText(R.id.e_today, today)
            v.setViewVisibility(R.id.e_today, View.VISIBLE)
        }
        return v
    }

    /** The compact card: the big value in [accent], its word above it, the rail and the bolt green only with green news. */
    private fun small(v: RemoteViews, title: String, value: String, sub: String, accent: Int) {
        v.setTextViewText(R.id.e_title, title)
        v.setTextViewText(R.id.e_main, value)
        v.setTextColor(R.id.e_main, accent)
        v.setTextViewText(R.id.e_sub, sub)
        // the rail and the bolt are green only with green news, else quiet
        val rail = if (accent == EnergyStyle.SUPPLY) accent else EnergyStyle.MUTED
        v.setInt(R.id.e_accent, "setColorFilter", rail)
        v.setInt(R.id.e_icon, "setColorFilter", rail)
    }

    private fun buildFull(ctx: Context, e: Energy?, paired: Boolean, onTap: PendingIntent, onRefresh: PendingIntent): RemoteViews {
        val note = when {
            !paired -> "In der App koppeln"
            e == null -> "…"
            else -> null
        }
        // Heute only when Haus says it; otherwise Jetzt alone fills the cell, large
        val withToday = note == null && hasToday(e)
        val v = RemoteViews(ctx.packageName, if (withToday) R.layout.widget_energy else R.layout.widget_energy_jetzt)
        v.setOnClickPendingIntent(R.id.e_root, onTap)
        v.setOnClickPendingIntent(R.id.e_refresh, onRefresh)
        val legs = EnergyStyle.orderedLegs(e?.pv, e?.house, e?.grid, e?.battery)
        for (i in 0..3) now(v, i, legs[i], LEG_LABELS[i], note)
        if (!withToday) return v
        val totals = EnergyStyle.orderedTotals(e?.totals.orEmpty())
        for (i in 0..3) {
            v.setTextViewText(TODAY_LABELS[i], EnergyStyle.TOTAL_LABELS[i])
            val kwh = totals[i]?.today
            v.setTextViewText(TODAY_VALUES[i], EnergyStyle.formatKwh(kwh))
            v.setTextColor(TODAY_VALUES[i], if (kwh != null) EnergyStyle.TOTAL_COLORS[i] else EnergyStyle.MUTED)
        }
        return v
    }

    /** One "jetzt" cell: the leg's label, its value in its direction's colour, and the direction word beside the label. */
    private fun now(v: RemoteViews, i: Int, f: EnergyFlow?, fallbackLabel: String, note: String?) {
        val label = legWord(f, fallbackLabel)
        if (note != null) {
            v.setTextViewText(NOW_LABELS[i], label)
            v.setTextViewText(NOW_VALUES[i], note)
            v.setTextColor(NOW_VALUES[i], EnergyStyle.MUTED)
            return
        }
        val w = f?.watts
        v.setTextViewText(NOW_LABELS[i], label)
        v.setTextViewText(NOW_VALUES[i], EnergyStyle.formatWatts(w))
        v.setTextColor(NOW_VALUES[i], EnergyStyle.colorFor(f?.sense, w))
    }

    private val LEG_LABELS = listOf("PV", "Haus", "Netz", "Akku")
    private val NOW_LABELS = intArrayOf(R.id.e_now_l0, R.id.e_now_l1, R.id.e_now_l2, R.id.e_now_l3)
    private val NOW_VALUES = intArrayOf(R.id.e_now_v0, R.id.e_now_v1, R.id.e_now_v2, R.id.e_now_v3)
    private val TODAY_LABELS = intArrayOf(R.id.e_today_l0, R.id.e_today_l1, R.id.e_today_l2, R.id.e_today_l3)
    private val TODAY_VALUES = intArrayOf(R.id.e_today_v0, R.id.e_today_v1, R.id.e_today_v2, R.id.e_today_v3)
}
