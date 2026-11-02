package cloud.dopp.haus

import cloud.dopp.haus.data.EnergyFlow
import cloud.dopp.haus.data.EnergyTotal
import cloud.dopp.haus.widget.EnergyStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM coverage for the pure logic of the one energy widget — the flow
 * color/direction mapping, the size tiers and the totals-slot mapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnergyLogicTest {

    // ── EnergyStyle: color + direction per leg ────────────────────────────────

    @Test fun gridImportIsRedExportIsGreen() {
        assertEquals(EnergyStyle.DRAW, EnergyStyle.colorFor("grid", 500.0))
        assertEquals(EnergyStyle.SUPPLY, EnergyStyle.colorFor("grid", -500.0))
    }

    @Test fun pvIsAlwaysGreenHouseAlwaysRed() {
        assertEquals(EnergyStyle.SUPPLY, EnergyStyle.colorFor("supply", 1200.0))
        assertEquals(EnergyStyle.DRAW, EnergyStyle.colorFor("draw", 800.0))
    }

    @Test fun batteryChargeIsRedDischargeIsGreen() {
        assertEquals(EnergyStyle.DRAW, EnergyStyle.colorFor("battery", 300.0))
        assertEquals(EnergyStyle.SUPPLY, EnergyStyle.colorFor("battery", -300.0))
    }

    @Test fun missingWattsIsNeutral() {
        assertEquals(EnergyStyle.NEUTRAL, EnergyStyle.colorFor("grid", null))
    }

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

    @Test fun theSmallTierShowsHeuteWithALittleRoom() {
        // the bare 2×1: jetzt only; a touch more room: a line of heute
        assertEquals(false, cloud.dopp.haus.widget.EnergyRender.showToday(110, 40))
        assertEquals(true, cloud.dopp.haus.widget.EnergyRender.showToday(110, 80))
        assertEquals(true, cloud.dopp.haus.widget.EnergyRender.showToday(150, 40))
    }

    @Test fun twoCellsHighDrawJetztAndHeuteAsCells() {
        assertEquals(false, cloud.dopp.haus.widget.EnergyRender.full(250, 40))
        assertEquals(true, cloud.dopp.haus.widget.EnergyRender.full(250, 110))
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

    @Test fun orderedLegsPreserveTheDisplayOrder() {
        val pv = EnergyFlow("PV", 1.0, "W", "supply")
        val grid = EnergyFlow("Netz", 2.0, "W", "grid")
        val ordered = EnergyStyle.orderedLegs(pv, null, grid, null)
        assertSame(pv, ordered[0])
        assertNull(ordered[1])
        assertSame(grid, ordered[2])
        assertNull(ordered[3])
    }
}
