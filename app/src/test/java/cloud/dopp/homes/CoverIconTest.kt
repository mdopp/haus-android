package cloud.dopp.homes

import cloud.dopp.homes.widget.DeviceIcons
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

    @Test
    fun theDeviceClassTouchesNoOtherDomain() {
        assertEquals(DeviceIcons.forDomain("light"), DeviceIcons.forState("light", "on", "garage"))
        assertEquals(DeviceIcons.forLockState("locked"), DeviceIcons.forState("lock", "locked", "garage"))
    }
}
