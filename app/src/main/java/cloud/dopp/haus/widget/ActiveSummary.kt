package cloud.dopp.haus.widget

import cloud.dopp.haus.data.Card

/**
 * Pure, JVM-testable grouping for the active-devices **compact** tier (#28): when
 * the widget is only 1-2 cells tall we can't fit the scrollable list, so instead
 * of a single grand total we show one **icon + count chip per domain/type**, e.g.
 * "💡 3 an · 🪟 2 offen · 🔌 1 an". The per-type icon comes from [DeviceIcons];
 * the German state verb from [verbFor]. No Android context here → unit-testable.
 */
object ActiveSummary {

    /** One rendered chip: a domain, how many of that type are active, its label. */
    data class Group(val domain: String, val count: Int, val label: String)

    /**
     * Group [active] cards by [Card.domain], most-populous first (stable by domain
     * name on ties), each carrying a short German state verb ("an"/"offen"/…).
     * Empty input → empty list.
     */
    fun byType(active: List<Card>): List<Group> =
        active
            .groupBy { it.domain }
            .map { (domain, cards) -> Group(domain, cards.size, "${cards.size} ${verbFor(domain)}") }
            .sortedWith(compareByDescending<Group> { it.count }.thenBy { it.domain })

    /** Short German state verb for a whole domain group (not a single entity). */
    fun verbFor(domain: String): String = when (domain) {
        "cover" -> "offen"
        "climate" -> "aktiv"
        // A lock only reaches this list while it is NOT locked (#84).
        "lock" -> "aufgeschlossen"
        else -> "an" // light, switch, fan, … read as "an"
    }
}
