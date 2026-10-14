package cloud.dopp.haus.widget

/**
 * The Verlauf-widget history window, toggled per instance (24h ⇄ 7d). Persisted
 * as a stable [key] in [WidgetStore]; [apiRange] is the value sent to
 * `/napi/portal/entity-history?range=`. Pure — no Android APIs — so the toggle
 * mapping is unit-testable.
 */
enum class EnergyRange(val key: String, val apiRange: String, val label: String) {
    DAY("24h", "24h", "24h"),
    WEEK("7d", "7d", "7d");

    /** The other window — what a tap on the toggle switches to. */
    fun toggled(): EnergyRange = if (this == DAY) WEEK else DAY

    companion object {
        val DEFAULT = DAY

        /** Parse a persisted key back to a range, defaulting to [DEFAULT]. */
        fun fromKey(key: String?): EnergyRange =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
