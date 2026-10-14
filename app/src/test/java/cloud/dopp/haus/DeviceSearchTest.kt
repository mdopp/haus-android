package cloud.dopp.haus

import cloud.dopp.haus.data.Device
import cloud.dopp.haus.data.DeviceSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM coverage for the device-picker search filter (#19) — extracted out of
 * `WidgetConfigActivity` so the name/room, case-insensitive, umlaut-aware matching
 * can be asserted without an emulator or Robolectric.
 */
class DeviceSearchTest {

    private fun dev(name: String, room: String?, domain: String = "light") =
        Device(entityId = "e_$name", name = name, domain = domain, deviceClass = null, room = room)

    private val lampe = dev("Lampe", room = "Küche")
    private val couch = dev("Stehlampe", room = "Wohnzimmer")
    private val heizung = dev("Thermostat", room = null, domain = "climate")
    private val all = listOf(lampe, couch, heizung)

    @Test
    fun blankQueryReturnsAll() {
        assertEquals(all, DeviceSearch.filter(all, ""))
        assertEquals(all, DeviceSearch.filter(all, "   "))
    }

    @Test
    fun matchesByName() {
        assertEquals(listOf(lampe, couch), DeviceSearch.filter(all, "lampe"))
    }

    @Test
    fun matchesByRoom() {
        assertEquals(listOf(couch), DeviceSearch.filter(all, "wohnzimmer"))
    }

    @Test
    fun caseInsensitive() {
        assertTrue(DeviceSearch.matches(lampe, "LAMPE"))
        assertTrue(DeviceSearch.matches(couch, "WOHN"))
    }

    @Test
    fun germanUmlautMatchesRoom() {
        assertTrue(DeviceSearch.matches(lampe, "küche"))
        assertEquals(listOf(lampe), DeviceSearch.filter(all, "küche"))
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertEquals(emptyList<Device>(), DeviceSearch.filter(all, "garage"))
        assertFalse(DeviceSearch.matches(heizung, "garage"))
    }

    @Test
    fun roomlessDeviceFallsBackToDomain() {
        // heizung has no room → its domain "climate" is searched.
        assertTrue(DeviceSearch.matches(heizung, "climate"))
    }
}
