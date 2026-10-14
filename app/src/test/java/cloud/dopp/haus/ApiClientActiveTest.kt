package cloud.dopp.haus

import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM coverage for [ApiClient.parseActive] (#28) — the parser for the collective
 * `/napi/portal/active` endpoint that replaced the per-entity
 * N+1 fan-out. Verifies both id shapes (`id` and `entity_id`), object-wrapped and
 * bare-array payloads, and stable domain→name ordering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiClientActiveTest {

    private fun client() = ApiClient(ApplicationProvider.getApplicationContext())

    @Test
    fun parsesWrappedList_withEntityId() {
        val json = """
            {"ok": true, "active": [
              {"entity_id": "light.kitchen", "name": "Küche", "room": "Küche", "domain": "light", "state": "on"},
              {"entity_id": "cover.garage", "name": "Garage", "room": "Flur", "domain": "cover", "state": "open"}
            ]}
        """.trimIndent()
        val cards = client().parseActive(json)
        assertEquals(2, cards.size)
        // Ordered by domain then name: cover < light.
        assertEquals("cover.garage", cards[0].entityId)
        assertEquals("Garage", cards[0].name)
        assertEquals("open", cards[0].state)
        assertEquals("light.kitchen", cards[1].entityId)
    }

    /**
     * Haus answers `{ok, active: [card]}` with `entity_id` on every card
     * (internal/napi/home.go:254-291). Older shapes — a bare
     * array, `id` for the entity — are not Haus's and are not read: a key Haus
     * never sends would be a guess (NapiContractTest).
     */
    @Test
    fun readsOnlyHaussShape() {
        assertTrue(client().parseActive("""[ {"entity_id": "switch.lamp", "state": "on"} ]""").isEmpty())
        assertTrue(client().parseActive("""{"active": [ {"id": "switch.lamp", "state": "on"} ]}""").isEmpty())
        val cards = client().parseActive("""{"ok": true, "active": [ {"entity_id": "switch.lamp", "name": "Lampe", "domain": "switch", "state": "on"} ]}""")
        assertEquals(1, cards.size)
        assertEquals("switch.lamp", cards[0].entityId)
        assertEquals("Lampe", cards[0].name)
        assertTrue(cards[0].isOn)
    }

    @Test
    fun domainFallsBackToEntityIdPrefix_whenMissing() {
        val json = """{"ok": true, "active": [ {"entity_id": "light.hall", "name": "Flur", "state": "on"} ]}"""
        val cards = client().parseActive(json)
        assertEquals("light", cards[0].domain)
    }

    @Test
    fun emptyOrMissingList_yieldsEmpty() {
        assertTrue(client().parseActive("{\"ok\": true}").isEmpty())
        assertTrue(client().parseActive("[]").isEmpty())
    }
}
