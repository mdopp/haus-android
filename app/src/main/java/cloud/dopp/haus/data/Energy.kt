package cloud.dopp.haus.data

/**
 * One current-power flow leg from `/api/portal/energy` — PV / Haus / Netz / Akku.
 * `watts` is the signed live reading (W); `sense` is the leg kind:
 * `supply` (PV), `draw` (house), `grid` (+import / -export),
 * `battery` (+charge / -discharge).
 */
data class EnergyFlow(
    val label: String,
    val watts: Double?,
    val unit: String,
    val sense: String,
    val entityId: String? = null,
)

/**
 * One cumulative total from `/napi/portal/energy` (`totals`) — e.g. PV-Erzeugung,
 * Einspeisung, Netzbezug, Batterie geladen. `value` is the energy amount in [unit]
 * (typically kWh); `key` groups it (`pv`/`export`/`import`/`battery`).
 */
data class EnergyTotal(
    val label: String,
    val value: Double?,
    val unit: String,
    val key: String,
    val entityId: String? = null,
)

/** The house energy picture: the live `flow` legs plus cumulative `totals`. */
data class Energy(
    val flow: List<EnergyFlow>,
    val totals: List<EnergyTotal> = emptyList(),
) {
    val pv get() = flow.firstOrNull { it.sense == "supply" }
    val house get() = flow.firstOrNull { it.sense == "draw" }
    val grid get() = flow.firstOrNull { it.sense == "grid" }
    val battery get() = flow.firstOrNull { it.sense == "battery" }
}
