package cloud.dopp.homes.widget

import cloud.dopp.homes.data.EnergyFlow
import cloud.dopp.homes.data.EnergyTotal
import java.util.Locale
import kotlin.math.abs

/**
 * Pure (context-free) presentation helpers of the energy widget — colors, flow
 * direction, W/kW and kWh formatting — mirroring Homes' Energie page.
 * JVM-testable (no Android APIs).
 */
object EnergyStyle {
    const val SUPPLY = 0xFF3DDC84.toInt()   // green: only what the sun makes and what goes into the grid
    const val NEUTRAL = 0xFFF2F2F5.toInt()  // everything else: consumption, the battery, a missing value
    const val MUTED = 0xFF9AA0AE.toInt()    // labels, and the values while loading

    /**
     * The colour of a flow leg by its sense + sign (owner, 2026-11-03): green
     * only for PV and for feeding the grid; the house's draw, a draw from the
     * grid and the battery stay neutral — consumption is no alarm.
     */
    fun colorFor(sense: String?, watts: Double?): Int {
        val w = watts ?: return NEUTRAL
        return when (sense) {
            "supply" -> if (w > 0) SUPPLY else NEUTRAL
            "grid" -> if (w < 0) SUPPLY else NEUTRAL
            else -> NEUTRAL
        }
    }

    /** The human direction suffix (Bezug/Einspeisung/lädt/entlädt), or "". */
    fun directionFor(sense: String?, watts: Double?): String {
        val w = watts ?: return ""
        return when (sense) {
            "supply" -> "liefert"
            "grid" -> if (w >= 0) "Bezug" else "Einspeisung"
            "battery" -> if (w >= 0) "lädt" else "entlädt"
            else -> ""
        }
    }

    fun formatWatts(w: Double?): String {
        if (w == null) return "—"
        val a = abs(w)
        return if (a >= 1000) String.format(Locale.GERMANY, "%.1f kW", a / 1000.0)
        else String.format(Locale.GERMANY, "%.0f W", a)
    }

    fun formatKwh(v: Double?): String {
        if (v == null) return "—"
        return String.format(Locale.GERMANY, "%.1f kWh", v)
    }

    /** The four canonical legs in display order (PV / Haus / Netz / Akku). */
    fun orderedLegs(
        pv: EnergyFlow?, house: EnergyFlow?, grid: EnergyFlow?, battery: EnergyFlow?,
    ): List<EnergyFlow?> = listOf(pv, house, grid, battery)

    /** The four canonical "heute" slots, keyed to server totals. */
    val TOTAL_SLOTS = listOf("pv", "export", "import", "battery")

    /** The heute cells' labels and colors, in [TOTAL_SLOTS] order: short, the cells are narrow. */
    val TOTAL_LABELS = listOf("Erzeugt", "Ins Netz", "Netzbezug", "In den Akku")
    val TOTAL_COLORS = intArrayOf(SUPPLY, SUPPLY, NEUTRAL, NEUTRAL)

    /**
     * Map the server `totals` onto the four canonical slots, preserving
     * [TOTAL_SLOTS] order. Non-matching totals are ignored; missing slots are null.
     *
     * The real `/napi/portal/energy` `totals` carry **no** `key`/`sense` field
     * (#24) — only `entity_id` + a German `label`. So we classify each total by its
     * `key` synonyms first, then fall back to the `entity_id` / `label` text (e.g.
     * `senec_solar_energy` → pv, `senec_grid_export_energy` → export). The
     * discharge total ("Batterie entladen") must NOT claim the charge slot, so the
     * battery matcher requires a charge/load hint and rejects discharge.
     */
    fun orderedTotals(totals: List<EnergyTotal>): List<EnergyTotal?> =
        TOTAL_SLOTS.map { slot -> totals.firstOrNull { matchesSlot(it, slot) } }

    private fun matchesSlot(total: EnergyTotal, slot: String): Boolean {
        // Everything we can classify by: explicit key, entity_id, and the label.
        val hay = listOfNotNull(total.key, total.entityId, total.label)
            .joinToString(" ").lowercase()
        if (hay.isBlank()) return false
        fun has(vararg needles: String) = needles.any { it.isNotEmpty() && hay.contains(it) }
        return when (slot) {
            "pv" -> has("pv", "supply", "solar", "produktion", "production", "erzeug", "generation")
            "export" -> has("export", "feed_in", "feedin", "grid_export", "einspeis")
            "import" -> has("import", "grid_import", "netzbezug", "netz_bezug", "bezug", "consumption") &&
                !has("export", "einspeis")
            // charge only — reject the discharge/entladen total.
            "battery" -> has("battery", "batterie", "akku", "charge", "geladen", "laden") &&
                !has("discharge", "entladen", "entlad")
            else -> false
        }
    }
}
