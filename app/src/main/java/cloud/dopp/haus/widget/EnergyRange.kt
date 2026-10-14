package cloud.dopp.haus.widget

import cloud.dopp.haus.data.Napi

/**
 * The Verlauf-widget history window, toggled per instance (24h ⇄ 7d). Persisted
 * as a stable [key] in [WidgetStore]; [history] is the window asked of
 * `/napi/portal/entity-history`, one Haus keeps. Pure — no Android APIs — so
 * the toggle mapping is unit-testable.
 */
enum class EnergyRange(val key: String, val history: Napi.HistoryRange, val label: String) {
    DAY("24h", Napi.HistoryRange.DAY, "24h"),
    WEEK("7d", Napi.HistoryRange.WEEK, "7d");

    /** The value Haus reads as `range`. */
    val apiRange: String get() = history.wire

    /** The other window — what a tap on the toggle switches to. */
    fun toggled(): EnergyRange = if (this == DAY) WEEK else DAY

    companion object {
        val DEFAULT = DAY

        /** Parse a persisted key back to a range, defaulting to [DEFAULT]. */
        fun fromKey(key: String?): EnergyRange =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
