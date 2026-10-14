package cloud.dopp.haus

import cloud.dopp.haus.data.Card
import cloud.dopp.haus.widget.ActiveCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip checks for the active-devices instant-cache JSON (#28): the
 * collection widget renders from this cache, so encode→decode must preserve the
 * fields the row needs (name/domain/state/brightness/position/temperature).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActiveCacheTest {

    private fun card(
        domain: String, state: String, name: String = "X",
        brightness: Int? = null, position: Int? = null, temperature: Double? = null,
        unit: String? = null, deviceClass: String? = null,
    ) = Card(
        entityId = "$domain.x", name = name, domain = domain, deviceClass = deviceClass,
        state = state, unit = unit, brightness = brightness, position = position,
        temperature = temperature,
    )

    @Test fun roundTripPreservesFields() {
        val cards = listOf(
            card("light", "on", name = "Wohnzimmer", brightness = 128),
            card("cover", "open", name = "Garage", position = 40, deviceClass = "garage"),
            card("climate", "heat", name = "Bad", temperature = 21.5),
            card("sensor", "42", name = "Zähler", unit = "W"),
        )
        val back = ActiveCache.decode(ActiveCache.encode(cards))

        assertEquals(4, back.size)
        assertEquals("Wohnzimmer", back[0].name)
        assertEquals(128, back[0].brightness)
        assertEquals("cover", back[1].domain)
        assertEquals(40, back[1].position)
        assertEquals("garage", back[1].deviceClass)
        assertEquals(21.5, back[2].temperature!!, 0.001)
        assertEquals("W", back[3].unit)
    }

    @Test fun emptyAndMalformedDecodeToEmpty() {
        assertTrue(ActiveCache.decode(null).isEmpty())
        assertTrue(ActiveCache.decode("").isEmpty())
        assertTrue(ActiveCache.decode("not json").isEmpty())
        assertEquals("[]", ActiveCache.encode(emptyList()))
        assertTrue(ActiveCache.decode("[]").isEmpty())
    }

    @Test fun nullsOmittedNotResurrected() {
        val back = ActiveCache.decode(ActiveCache.encode(listOf(card("switch", "on"))))
        assertEquals(1, back.size)
        assertEquals(null, back[0].brightness)
        assertEquals(null, back[0].position)
        assertEquals(null, back[0].temperature)
        assertEquals(null, back[0].unit)
    }
}
