package cloud.dopp.haus

import cloud.dopp.haus.data.EnergyFlow
import cloud.dopp.haus.data.EnergyTotal
import cloud.dopp.haus.widget.EnergyStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM coverage for the pure logic of the one energy widget — the flow's
 * direction words, the size tiers, the totals-slot mapping and the flow now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnergyLogicTest {

    // ── EnergyStyle: color + direction per leg ────────────────────────────────

    @Test fun directionWordsMatchTheWebScreen() {
        assertEquals("Bezug", EnergyStyle.directionFor("grid", 100.0))
        assertEquals("Einspeisung", EnergyStyle.directionFor("grid", -100.0))
        assertEquals("lädt", EnergyStyle.directionFor("battery", 50.0))
        assertEquals("entlädt", EnergyStyle.directionFor("battery", -50.0))
    }

    @Test fun wattFormattingSwitchesToKwAtThousand() {
        assertEquals("500 W", EnergyStyle.formatWatts(500.0))
        assertEquals("1,5 kW", EnergyStyle.formatWatts(1500.0))
        assertEquals("1,2 kW", EnergyStyle.formatWatts(-1200.0)) // magnitude only
    }

    // ── EnergyStyle: compact grid in/out balance (#25) ────────────────────────

    @Test fun theSmallTierAddsLinesOnlyWithHeight() {
        assertEquals(false, cloud.dopp.haus.widget.EnergyRender.showSub(60))
        assertEquals(true, cloud.dopp.haus.widget.EnergyRender.showSub(90))
        assertEquals(false, cloud.dopp.haus.widget.EnergyRender.showToday(80))
        assertEquals(true, cloud.dopp.haus.widget.EnergyRender.showToday(100))
    }

    @Test fun twoCellsHighDrawJetztAndHeuteAsCells() {
        assertEquals(false, cloud.dopp.haus.widget.EnergyRender.full(250, 40))
        assertEquals(true, cloud.dopp.haus.widget.EnergyRender.full(250, 120))
        assertEquals("a size the launcher did not tell: the placed default, 4×2", true, cloud.dopp.haus.widget.EnergyRender.full(0, 0))
    }

    // ── EnergyStyle: totals-slot mapping for the Gesamt grid ──────────────────

    @Test fun totalsMapOntoCanonicalSlotsByKeySynonyms() {
        val totals = listOf(
            EnergyTotal("Erzeugung", 12.3, "kWh", "production"),
            EnergyTotal("Einspeisung", 4.5, "kWh", "feed_in"),
            EnergyTotal("Bezug", 6.7, "kWh", "grid_import"),
            EnergyTotal("Akku", 2.1, "kWh", "battery_charged"),
        )
        val ordered = EnergyStyle.orderedTotals(totals)
        assertEquals(12.3, ordered[0]?.value!!, 0.001)  // pv
        assertEquals(4.5, ordered[1]?.value!!, 0.001)   // export
        assertEquals(6.7, ordered[2]?.value!!, 0.001)   // import
        assertEquals(2.1, ordered[3]?.value!!, 0.001)   // battery
    }

    @Test fun totalsMapFromEntityIdAndLabelWhenServerOmitsKey() {
        // The REAL /napi/portal/energy shape (#24): totals carry no key/sense —
        // only entity_id + a German label. ApiClient falls key back to entity_id;
        // classification then leans on entity_id/label text.
        val totals = listOf(
            EnergyTotal("PV-Erzeugung", 917.1, "kWh", "sensor.senec_solar_energy", "sensor.senec_solar_energy"),
            EnergyTotal("Einspeisung", 593.3, "kWh", "sensor.senec_grid_export_energy", "sensor.senec_grid_export_energy"),
            EnergyTotal("Netzbezug", 8.0, "kWh", "sensor.senec_grid_import_energy", "sensor.senec_grid_import_energy"),
            EnergyTotal("Batterie geladen", 115.1, "kWh", "sensor.senec_battery_charge_energy", "sensor.senec_battery_charge_energy"),
            // The discharge total must NOT hijack the battery (charge) slot.
            EnergyTotal("Batterie entladen", 105.1, "kWh", "sensor.senec_battery_discharge_energy", "sensor.senec_battery_discharge_energy"),
        )
        val ordered = EnergyStyle.orderedTotals(totals)
        assertEquals(917.1, ordered[0]?.value!!, 0.01) // pv
        assertEquals(593.3, ordered[1]?.value!!, 0.01) // export
        assertEquals(8.0, ordered[2]?.value!!, 0.01)   // import (not the export total)
        assertEquals(115.1, ordered[3]?.value!!, 0.01) // battery charged (not discharge)
        assertEquals("Batterie geladen", ordered[3]?.label)
    }

    @Test fun missingTotalsSlotsAreNull() {
        val ordered = EnergyStyle.orderedTotals(
            listOf(EnergyTotal("PV", 9.0, "kWh", "pv")),
        )
        assertEquals(9.0, ordered[0]?.value!!, 0.001)
        assertNull(ordered[1])
        assertNull(ordered[2])
        assertNull(ordered[3])
    }

    // ── EnergyFluss: the page's flow now (internal/pages/energie_fluss.go) ─────

    private fun fluss(pv: Double?, haus: Double?, netz: Double?, akku: Double?, charge: Double? = null, unit: String = "W") =
        cloud.dopp.haus.widget.EnergyFluss.of(
            cloud.dopp.haus.data.Energy(
                listOfNotNull(
                    EnergyFlow("PV", pv, unit, "supply"), EnergyFlow("Haus", haus, unit, "draw"),
                    EnergyFlow("Netz", netz, unit, "grid"), akku?.let { EnergyFlow("Akku", it, unit, "battery") },
                ),
                charge = charge,
            ),
        )

    /** The owner's evening (2026-11-03): no sun, the battery carries the house and a little goes into the grid. */
    @Test fun theBatteryCarriesTheHouseAtNight() {
        val f = fluss(0.0, 271.0, -4.0, -276.0, charge = 83.0)
        assertEquals("271 W" to "verbraucht", f.text("haus"))
        assertEquals("276 W" to "entlädt", f.text("akku"))
        assertEquals("owner: where the power goes", "4 W" to "ins Netz", f.text("netz"))
        assertEquals("16 W" to "aus dem Netz", fluss(0.0, 16.0, 16.0, null).text("netz"))
        assertEquals("0 W" to "ruht", fluss(0.0, 16.0, 0.0, -16.0).text("netz"))
        assertEquals("0 W" to "erzeugt", f.text("solar"))
        assertEquals("Akku 83\u00a0%", f.name("akku"))
        assertEquals("the one way that carries: Akku → Haus", listOf("akku" to "haus"), f.aktiv.map { it.von to it.nach })
        assertEquals(1.0, f.autarkie!!, 0.001)
    }

    /** At noon the sun fills the house first, then the battery, then the grid. */
    @Test fun theSunFillsTheHouseThenTheBatteryThenTheGrid() {
        val f = fluss(3.2, 0.8, -2.1, 0.3, unit = "kW")
        val ways = f.aktiv.associate { (it.von to it.nach) to it.w }
        assertEquals(800.0, ways.getValue("solar" to "haus"), 0.1)
        assertEquals(300.0, ways.getValue("solar" to "akku"), 0.1)
        assertEquals(2100.0, ways.getValue("solar" to "netz"), 0.1)
        assertEquals(3, ways.size)
        assertEquals("3,2 kW" to "erzeugt", f.text("solar"))
        assertEquals("Akku", f.name("akku"))
    }

    /** A node that says no number: no ways, no Autarkie — the page draws none either. */
    @Test fun anUnknownNodeDrawsNoWays() {
        val f = fluss(null, 800.0, 800.0, null)
        assertEquals(emptyList<Any>(), f.aktiv)
        assertNull(f.autarkie)
        assertEquals("–" to "unbekannt", f.text("solar"))
    }

    /** No battery leg: no charge shown even when Haus sent one. */
    @Test fun noBatteryNoCharge() {
        assertNull(fluss(0.0, 500.0, 500.0, null, charge = 50.0).charge)
        assertEquals("netz", cloud.dopp.haus.widget.EnergyPicture.singleKey(fluss(0.0, 500.0, 500.0, null)))
        assertEquals("akku", cloud.dopp.haus.widget.EnergyPicture.singleKey(fluss(0.0, 500.0, 500.0, -10.0, charge = 50.0)))
    }

    /**
     * The ovals keep apart and inside the field, spread over its width (owner,
     * 2026-11-03), the columns apart so a way runs between them.
     */
    @Test fun theOvalsDoNotTouchAndFillTheWidth() {
        for ((w, h) in listOf(187f to 147f, 323f to 147f, 136f to 147f)) {
            val l = cloud.dopp.haus.widget.EnergyPicture.lage(w, h)
            val boxes = cloud.dopp.haus.widget.EnergyFluss.KEYS.associateWith { l.box(it) }
            assertTrue("${w}x$h: Netz clear of the column", boxes.getValue("solar").left - boxes.getValue("netz").right >= 6f)
            assertTrue("${w}x$h: Akku clear of the column", boxes.getValue("akku").left - boxes.getValue("solar").right >= 6f)
            boxes.values.forEach { assertTrue("${w}x$h: inside", it.left >= 0 && it.right <= w && it.top >= 0 && it.bottom <= h) }
            assertTrue("${w}x$h: Netz at the left edge", boxes.getValue("netz").left <= 8.5f)
            assertTrue("${w}x$h: Akku at the right edge", boxes.getValue("akku").right >= w - 8.5f)
            assertTrue("${w}x$h: ovals, not circles", l.pw >= l.ph * 1.15f)
        }
    }
}
