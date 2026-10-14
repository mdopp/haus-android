package cloud.dopp.haus

import cloud.dopp.haus.data.Card
import cloud.dopp.haus.widget.ActiveSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic checks for the compact-tier grouped-by-type summary (#28): counts per
 * domain, most-populous ordering, German verbs, and the empty case.
 */
class ActiveSummaryTest {

    private fun card(domain: String, id: String = "$domain.x") =
        Card(entityId = id, name = "X", domain = domain, deviceClass = null, state = "on", unit = null)

    @Test fun emptyInputYieldsNoGroups() {
        assertEquals(emptyList<ActiveSummary.Group>(), ActiveSummary.byType(emptyList()))
    }

    @Test fun countsPerDomain() {
        val groups = ActiveSummary.byType(
            listOf(
                card("light", "light.a"), card("light", "light.b"), card("light", "light.c"),
                card("cover", "cover.a"), card("cover", "cover.b"),
                card("switch", "switch.a"),
            ),
        )
        val byDomain = groups.associateBy { it.domain }
        assertEquals(3, byDomain.getValue("light").count)
        assertEquals(2, byDomain.getValue("cover").count)
        assertEquals(1, byDomain.getValue("switch").count)
    }

    @Test fun mostPopulousFirstThenByDomainName() {
        val groups = ActiveSummary.byType(
            listOf(
                card("switch"),
                card("light", "light.a"), card("light", "light.b"), card("light", "light.c"),
                card("cover", "cover.a"), card("cover", "cover.b"),
            ),
        )
        assertEquals(listOf("light", "cover", "switch"), groups.map { it.domain })
    }

    @Test fun labelsCarryGermanVerbs() {
        val groups = ActiveSummary.byType(
            listOf(card("light"), card("cover"), card("switch"), card("climate")),
        ).associateBy { it.domain }
        assertEquals("1 an", groups.getValue("light").label)
        assertEquals("1 offen", groups.getValue("cover").label)
        assertEquals("1 an", groups.getValue("switch").label)
        assertEquals("1 aktiv", groups.getValue("climate").label)
    }

    @Test fun tiesBreakDeterministicallyByDomain() {
        val groups = ActiveSummary.byType(listOf(card("switch"), card("cover"), card("light")))
        // All count 1 → alphabetical by domain.
        assertTrue(groups.map { it.domain } == listOf("cover", "light", "switch"))
    }
}
