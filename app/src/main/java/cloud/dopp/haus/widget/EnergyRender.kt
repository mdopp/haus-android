package cloud.dopp.haus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.RemoteViews
import cloud.dopp.haus.R
import cloud.dopp.haus.data.Energy
import cloud.dopp.haus.data.EnergyFlow

/**
 * Size-adaptive rendering of the "Jetzt" energy widget.
 * Small = the grid net (Bezug/Einspeisung); large = the 2×2 PV/Haus/Netz/Akku
 * flow grid, styled like the web Energy "jetzt" screen (colored value +
 * direction per leg).
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
        val large = minW >= 180 && minH >= 110

        return if (large) buildGrid(ctx, energy, paired, onTap, onRefresh)
        else buildSmall(ctx, energy, paired, onTap, onRefresh, showPv(minW, minH))
    }

    /**
     * Should the compact tier add the PV line (#25)? At the bare 1×1 / minimal
     * space only the grid balance shows; with a touch more room (taller or wider
     * than a single tight cell) we can afford the extra PV line. Pure so it's
     * JVM-testable.
     */
    fun showPv(minW: Int, minH: Int): Boolean = minH >= 110 || minW >= 140

    private fun buildSmall(
        ctx: Context,
        e: Energy?,
        paired: Boolean,
        onTap: PendingIntent,
        onRefresh: PendingIntent,
        showPv: Boolean,
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_energy_small)
        v.setOnClickPendingIntent(R.id.e_root, onTap)
        v.setOnClickPendingIntent(R.id.e_refresh, onRefresh)
        v.setViewVisibility(R.id.e_pv, android.view.View.GONE)
        if (!paired) {
            small(v, "Energie", "koppeln", "gerät koppeln", EnergyStyle.NEUTRAL)
            return v
        }
        // #25: at 1×1 / tight space show ONLY the grid balance — → in (import) or
        // ← out (export) + the watt value, color-coded (import red / export green).
        val g = e?.grid ?: e?.house
        val w = g?.watts
        if (w == null) {
            small(
                v, "Netz",
                if (e == null) "…" else "—",
                if (e == null) "wird geladen" else "keine Daten",
                EnergyStyle.NEUTRAL,
            )
            return v
        }
        val onGrid = g.sense == "grid"
        val accent = EnergyStyle.colorFor(g.sense, w)
        val title = if (onGrid) "Netz" else "Haus"
        // The direction line: the compact in/out balance for grid, else Verbrauch.
        val sub = if (onGrid) EnergyStyle.gridBalanceLabel(w) else "Verbrauch"
        small(v, title, EnergyStyle.formatWatts(w), sub, accent)
        // With a bit more space, add the PV line (#25).
        if (showPv) {
            val pv = e?.pv
            if (pv?.watts != null) {
                v.setTextViewText(R.id.e_pv, "PV " + EnergyStyle.formatWatts(pv.watts))
                v.setTextColor(R.id.e_pv, EnergyStyle.colorFor(pv.sense, pv.watts))
                v.setViewVisibility(R.id.e_pv, android.view.View.VISIBLE)
            }
        }
        return v
    }

    /**
     * Fill the compact "Jetzt" card (#25): big colored value, an all-caps label,
     * a muted direction subtitle, and the left accent rail + icon tinted to
     * [accent] so the small format reads at a glance and fills its space.
     */
    private fun small(v: RemoteViews, title: String, value: String, sub: String, accent: Int) {
        v.setTextViewText(R.id.e_title, title)
        v.setTextViewText(R.id.e_main, value)
        v.setTextColor(R.id.e_main, accent)
        v.setTextViewText(R.id.e_sub, sub)
        v.setInt(R.id.e_accent, "setColorFilter", accent)
        v.setInt(R.id.e_icon, "setColorFilter", accent)
    }

    private fun buildGrid(ctx: Context, e: Energy?, paired: Boolean, onTap: PendingIntent, onRefresh: PendingIntent): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_energy_grid)
        v.setOnClickPendingIntent(R.id.e_root, onTap)
        v.setOnClickPendingIntent(R.id.e_refresh, onRefresh)
        v.setTextViewText(R.id.e_head, "Energie · jetzt")

        val legs = EnergyStyle.orderedLegs(e?.pv, e?.house, e?.grid, e?.battery)
        val fallbackLabels = listOf("PV", "Haus", "Netz", "Akku")
        val note = when {
            !paired -> "koppeln"
            e == null -> "…"
            else -> null
        }
        for (i in 0..3) {
            cell(v, i, legs[i], fallbackLabels[i], note)
        }
        return v
    }

    private fun cell(v: RemoteViews, i: Int, f: EnergyFlow?, fallbackLabel: String, note: String?) {
        val labelId = LABEL_IDS[i]; val valueId = VALUE_IDS[i]; val dirId = DIR_IDS[i]
        v.setTextViewText(labelId, f?.label?.ifBlank { fallbackLabel } ?: fallbackLabel)
        if (note != null) {
            v.setTextViewText(valueId, note)
            v.setTextColor(valueId, EnergyStyle.NEUTRAL)
            v.setTextViewText(dirId, "")
            return
        }
        val w = f?.watts
        v.setTextViewText(valueId, EnergyStyle.formatWatts(w))
        v.setTextColor(valueId, EnergyStyle.colorFor(f?.sense, w))
        v.setTextViewText(dirId, EnergyStyle.directionFor(f?.sense, w))
    }

    private val LABEL_IDS = intArrayOf(R.id.e_l0, R.id.e_l1, R.id.e_l2, R.id.e_l3)
    private val VALUE_IDS = intArrayOf(R.id.e_v0, R.id.e_v1, R.id.e_v2, R.id.e_v3)
    private val DIR_IDS = intArrayOf(R.id.e_d0, R.id.e_d1, R.id.e_d2, R.id.e_d3)
}
