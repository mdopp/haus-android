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
 * Einspeisung, Netzbezug, Batterie geladen. `value` is the counter in [unit]
 * (typically kWh); `key` groups it (`pv`/`export`/`import`/`battery`); [today]
 * is the kWh it added since the house's midnight, null when Haus could not say.
 */
data class EnergyTotal(
    val label: String,
    val value: Double?,
    val unit: String,
    val key: String,
    val entityId: String? = null,
    val today: Double? = null,
)

/**
 * The house energy picture: the live `flow` legs plus cumulative `totals`,
 * and how full the battery is ([charge], percent; [chargeEntityId] its
 * sensor), null while Haus does not say.
 */
data class Energy(
    val flow: List<EnergyFlow>,
    val totals: List<EnergyTotal> = emptyList(),
    val charge: Double? = null,
    val chargeEntityId: String? = null,
) {
    val pv get() = flow.firstOrNull { it.sense == "supply" }
    val house get() = flow.firstOrNull { it.sense == "draw" }
    val grid get() = flow.firstOrNull { it.sense == "grid" }
    val battery get() = flow.firstOrNull { it.sense == "battery" }
}
