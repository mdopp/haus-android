package cloud.dopp.haus.widget

import cloud.dopp.haus.data.EnergyFlow
import cloud.dopp.haus.data.EnergyTotal
import java.util.Locale
import kotlin.math.abs

/**
 * Pure (context-free) energy presentation helpers shared by all three energy
 * widgets — colors, flow direction, and W/kW formatting — mirroring the Haus
 * web Energy screen. JVM-testable (no Android APIs).
 */
object EnergyStyle {
    const val SUPPLY = 0xFF66BB6A.toInt()   // green: PV / feed-in / discharge
    const val DRAW = 0xFFEF5350.toInt()     // red: consumption / import / charge
    const val NEUTRAL = 0xFFB0BEC5.toInt()
    const val BATTERY = 0xFF7E9CFF.toInt()

    /** The accent color for a flow leg by its sense + sign. */
    fun colorFor(sense: String?, watts: Double?): Int {
        val w = watts ?: return NEUTRAL
        return when (sense) {
            "supply" -> SUPPLY
            "draw" -> DRAW
            "grid" -> if (w >= 0) DRAW else SUPPLY
            "battery" -> if (w >= 0) DRAW else SUPPLY
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

    /**
     * The compact grid-balance label for the tiny "Jetzt" tier (#25): an arrow +
     * a short in/out word describing whether the house **draws from** the grid
     * (import, `w >= 0` → `→ in`) or **feeds into** it (export, `w < 0` →
     * `← out`). Pure so it's JVM-testable; color comes from [colorFor]("grid", w).
     */
    fun gridBalanceLabel(watts: Double?): String {
        val w = watts ?: return "—"
        return if (w >= 0) "→ in" else "← out"
    }

    /** Line/legend color for a leg in the Verlauf chart. */
    fun seriesColor(sense: String?): Int = when (sense) {
        "supply" -> SUPPLY
        "draw" -> DRAW
        "grid" -> 0xFFFFC107.toInt() // amber, distinct from the +/- grid flow tint
        "battery" -> BATTERY
        else -> NEUTRAL
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

    /** The four canonical "Gesamt" slots, keyed to server totals. */
    val TOTAL_SLOTS = listOf("pv", "export", "import", "battery")

    /** Fallback labels + colors for the totals grid, in [TOTAL_SLOTS] order. */
    val TOTAL_LABELS = listOf("PV", "Einspeisung", "Netzbezug", "Batterie")
    val TOTAL_COLORS = intArrayOf(SUPPLY, SUPPLY, DRAW, BATTERY)

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
