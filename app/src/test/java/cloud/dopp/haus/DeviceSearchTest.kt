package cloud.dopp.haus

import cloud.dopp.haus.data.Device
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which devices of the household the app-icon menu offers (#100): only what
 * Haus draws as a card.
 */
class DeviceSearchTest {

    private fun dev(name: String, room: String?, domain: String = "light") =
        Device(entityId = "e_$name", name = name, domain = domain, deviceClass = null, room = room)

    private val all = listOf(dev("Lampe", "Küche"), dev("Stehlampe", "Wohnzimmer"), dev("Thermostat", null, "climate"))

    /**
     * Haus offers cameras in the addable list (camera.go:25) but draws no card
     * for one (home.go:41, 170): the menu offers only what Haus draws as a card.
     */
    @Test
    fun aCameraIsNotOffered() {
        val cam = dev("Einfahrt", room = "Hof", domain = "camera")
        @Suppress("UNCHECKED_CAST")
        val offered = Reach.call("cloud.dopp.haus.data.DeviceSearch", "pickable", all + cam) as List<Device>
        assertEquals(all, offered)
    }
}
