package cloud.dopp.haus

import cloud.dopp.haus.widget.ChartRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-logic checks for the #29 on/off timeline: the state→on/off mapping that
 * lets a binary device (a plain light, a switch, a cover) show a history bar
 * instead of an empty "kein Verlauf" block, and the timeline bucketing that
 * turns those booleans into filled/muted runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChartHistoryTest {

    @Test fun onStatesMapToOn() {
        assertTrue(ChartRenderer.stateIsOn("on"))
        assertTrue(ChartRenderer.stateIsOn("On"))
        assertTrue(ChartRenderer.stateIsOn("OPEN"))
        assertTrue(ChartRenderer.stateIsOn(" open "))
        assertTrue(ChartRenderer.stateIsOn("heat"))
    }

    @Test fun offStatesMapToOff() {
        assertFalse(ChartRenderer.stateIsOn("off"))
        assertFalse(ChartRenderer.stateIsOn("closed"))
        assertFalse(ChartRenderer.stateIsOn("unavailable"))
        assertFalse(ChartRenderer.stateIsOn("unknown"))
        assertFalse(ChartRenderer.stateIsOn(""))
        assertFalse(ChartRenderer.stateIsOn(null))
    }

    /** A brightness / cover-position number > 0 counts as on/open. */
    @Test fun positiveNumericStateIsOn() {
        assertTrue(ChartRenderer.stateIsOn("42"))
        assertTrue(ChartRenderer.stateIsOn("1"))
        assertFalse(ChartRenderer.stateIsOn("0"))
        assertFalse(ChartRenderer.stateIsOn("-5"))
    }

    /** The timeline bitmap clamps to MAX_EDGE and is produced for any non-empty run. */
    @Test fun timelineClampsAndRenders() {
        val states = List(96) { it % 2 == 0 } // 48h @ 30min
        val bmp = ChartRenderer.onOffTimeline(2000, 300, states, 0xFFFFC107.toInt(), 0x33FFFFFF)
        assertEquals(ChartRenderer.MAX_EDGE, bmp.width)
        assertEquals(300, bmp.height)
    }

    @Test fun emptyTimelineStillProducesABitmap() {
        val bmp = ChartRenderer.onOffTimeline(480, 120, emptyList(), 0xFFFFC107.toInt(), 0x33FFFFFF)
        assertEquals(480, bmp.width)
        assertEquals(120, bmp.height)
    }
}
