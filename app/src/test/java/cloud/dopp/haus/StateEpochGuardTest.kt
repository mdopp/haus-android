package cloud.dopp.haus

import cloud.dopp.haus.data.Card
import cloud.dopp.haus.data.StateEpochGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test

/**
 * The per-entity staleness guard behind the multi-device toggle-race fix: only
 * the newest source-stamped state (HA `last_updated`, epoch-ms) is applied; a
 * stale update yields the newer already-applied card instead of overwriting it.
 * Pure JVM — no Android / org.json.
 */
class StateEpochGuardTest {

    @Before fun reset() = StateEpochGuard.clear()

    private fun card(entity: String, state: String, ms: Long?): Card =
        Card(entityId = entity, name = entity, domain = "light", deviceClass = null,
            state = state, unit = null, updatedAtMs = ms)

    @Test fun newerStampWins_andBecomesTheRemembered() {
        val on = card("light.k", "on", 1000)
        assertSame(on, StateEpochGuard.resolve(on))
        val off = card("light.k", "off", 2000)
        assertSame(off, StateEpochGuard.resolve(off)) // newer → applied
    }

    @Test fun strictlyOlderIsRejected_returningTheNewerApplied() {
        val on = card("light.k", "on", 2000)
        StateEpochGuard.resolve(on)
        val staleOff = card("light.k", "off", 1000) // arrives late / reordered
        val winner = StateEpochGuard.resolve(staleOff)
        assertNotSame(staleOff, winner)
        assertSame(on, winner)            // the newer state, not the stale one
        assertEquals("on", winner.state)
    }

    @Test fun equalStampIsAdmitted_idempotentReapply() {
        val a = card("light.k", "on", 1500)
        StateEpochGuard.resolve(a)
        val b = card("light.k", "on", 1500) // same stamp (e.g. reconnect replay)
        assertSame(b, StateEpochGuard.resolve(b))
    }

    @Test fun nullStampPassesThrough_guardInertOnLegacyServer() {
        val a = card("light.k", "on", null)
        assertSame(a, StateEpochGuard.resolve(a))
        val b = card("light.k", "off", null)
        assertSame(b, StateEpochGuard.resolve(b)) // never blocked without a stamp
    }

    @Test fun perEntityIsolation() {
        StateEpochGuard.resolve(card("light.k", "on", 5000))
        val other = card("light.b", "on", 100) // different entity, low stamp
        assertSame(other, StateEpochGuard.resolve(other)) // not gated by light.k
    }

    @Test fun theSpringScenario_lateOffDoesNotReflipOn() {
        // Two devices: A turned off (@1000), B turned on (@2000). Both broadcasts
        // land; the newer "on" applies. Then A's post-toggle re-fetch returns the
        // stale "off@1000" — it must NOT re-flip the widget to off.
        StateEpochGuard.resolve(card("light.k", "off", 1000))
        StateEpochGuard.resolve(card("light.k", "on", 2000))
        val staleRefetch = card("light.k", "off", 1000)
        assertEquals("on", StateEpochGuard.resolve(staleRefetch).state)
    }
}
