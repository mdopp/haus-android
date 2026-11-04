package cloud.dopp.homes

import cloud.dopp.homes.data.Card
import cloud.dopp.homes.widget.DeviceIcons
import cloud.dopp.homes.widget.DeviceLook
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A cover in the favourites row (owner, 2026-11-03): „Rollo“ showed the
 * garage. A garage or a gate is drawn as a garage, every other cover —
 * shutter, blind, curtain, or no class named — as a shutter, up when open or
 * opening, down otherwise.
 */
class CoverIconTest {

    @Test
    fun aShutterIsAShutterUpOrDown() {
        for (dc in listOf("shutter", "blind", "curtain", null, "", " Shutter ")) {
            assertEquals("$dc open", R.drawable.ic_blind_open, DeviceIcons.forState("cover", "open", dc))
            assertEquals("$dc opening", R.drawable.ic_blind_open, DeviceIcons.forState("cover", "opening", dc))
            assertEquals("$dc closed", R.drawable.ic_blind_closed, DeviceIcons.forState("cover", "closed", dc))
            assertEquals("$dc unknown", R.drawable.ic_blind_closed, DeviceIcons.forState("cover", null, dc))
        }
        // „Rollo“ as Homes sends it: no class
        assertEquals(R.drawable.ic_blind_open, DeviceIcons.forState("cover", "open"))
    }

    @Test
    fun onlyAGarageOrAGateIsAGarage() {
        for (dc in listOf("garage", "gate", "Garage")) {
            assertEquals("$dc open", R.drawable.ic_garage_open, DeviceIcons.forState("cover", "open", dc))
            assertEquals("$dc closed", R.drawable.ic_garage_closed, DeviceIcons.forState("cover", "closed", dc))
        }
    }

    /** The widget draws a cover as the start screen does (owner, 2026-11-04): a door is a shutter on both. */
    @Test
    fun theWidgetDrawsACoverAsTheStartScreenDoes() {
        for (dc in listOf("garage", "gate", "Garage", "door", "shutter", null)) {
            for (state in listOf("open", "opening", "closed", null)) {
                assertEquals("$dc $state", DeviceIcons.forState("cover", state, dc), DeviceLook.icon("cover", dc, state))
            }
        }
        val door = Card(entityId = "cover.tuer", name = "Tür", domain = "cover", deviceClass = "door", state = "open", unit = null)
        assertEquals("an open door is coloured as an open shutter", DeviceLook.ON, DeviceLook.tint("cover", "door", door))
        assertEquals(DeviceLook.OPEN, DeviceLook.tint("cover", "Garage", door.copy(deviceClass = "Garage")))
    }

    @Test
    fun theDeviceClassTouchesNoOtherDomain() {
        assertEquals(DeviceIcons.forDomain("light"), DeviceIcons.forState("light", "on", "garage"))
        assertEquals(DeviceIcons.forLockState("locked"), DeviceIcons.forState("lock", "locked", "garage"))
    }
}
