package cloud.dopp.haus

import cloud.dopp.haus.data.Card
import cloud.dopp.haus.widget.ActiveDevicesRender
import cloud.dopp.haus.widget.ActiveDevicesRender.Tier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-logic checks for the active-devices overview widget (#28): tier
 * selection and the German per-domain state label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActiveDevicesRenderTest {

    @Test fun listShowsAtTwoCellsTall() {
        // Boundary lowered (#28): a ~2-cell-tall / full-width widget now shows the list.
        assertEquals(Tier.LARGE, ActiveDevicesRender.sizeTier(160, 110))
        assertEquals(Tier.LARGE, ActiveDevicesRender.sizeTier(300, 110))
        assertEquals(Tier.LARGE, ActiveDevicesRender.sizeTier(180, 180))
        assertEquals(Tier.LARGE, ActiveDevicesRender.sizeTier(300, 300))
    }

    @Test fun flatOrNarrowIsCompact() {
        assertEquals(Tier.SMALL, ActiveDevicesRender.sizeTier(300, 60))   // 1 row tall
        assertEquals(Tier.SMALL, ActiveDevicesRender.sizeTier(110, 40))
        assertEquals(Tier.SMALL, ActiveDevicesRender.sizeTier(120, 300))  // too narrow
    }

    private fun card(domain: String, state: String, brightness: Int? = null, position: Int? = null) =
        Card(
            entityId = "$domain.x", name = "X", domain = domain, deviceClass = null,
            state = state, unit = null, brightness = brightness, position = position,
        )

    @Test fun stateLabels() {
        assertEquals("an", ActiveDevicesRender.stateLabel(card("light", "on")))
        assertEquals("an · 50 %", ActiveDevicesRender.stateLabel(card("light", "on", brightness = 128)))
        assertEquals("an", ActiveDevicesRender.stateLabel(card("switch", "on")))
        assertEquals("offen", ActiveDevicesRender.stateLabel(card("cover", "open", position = 100)))
        assertEquals("40 % offen", ActiveDevicesRender.stateLabel(card("cover", "open", position = 40)))
    }
}
