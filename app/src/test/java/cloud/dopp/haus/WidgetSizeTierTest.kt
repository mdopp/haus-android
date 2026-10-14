package cloud.dopp.haus

import cloud.dopp.haus.widget.WidgetRender
import cloud.dopp.haus.widget.WidgetRender.Tier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-logic check for the widget layout tier selection (#20): a wide-but-flat
 * widget (e.g. 4×1) must land on WIDE — which carries inline controls — instead
 * of falling through to SMALL, which has none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetSizeTierTest {

    @Test fun tallEnoughIsMedium() {
        assertEquals(Tier.MEDIUM, WidgetRender.sizeTier(180, 110))
        assertEquals(Tier.MEDIUM, WidgetRender.sizeTier(300, 200))
    }

    @Test fun wideButFlatIsWide() {
        // 4×1 on a typical launcher grid: ~270dp wide, ~40dp tall.
        assertEquals(Tier.WIDE, WidgetRender.sizeTier(270, 40))
        assertEquals(Tier.WIDE, WidgetRender.sizeTier(250, 100))
        assertEquals(Tier.WIDE, WidgetRender.sizeTier(180, 40)) // 3 cells wide flat → controls
        assertEquals(Tier.WIDE, WidgetRender.sizeTier(170, 40)) // low edge of 3 cells
    }

    @Test fun smallStaysSmall() {
        assertEquals(Tier.SMALL, WidgetRender.sizeTier(110, 40))
        assertEquals(Tier.SMALL, WidgetRender.sizeTier(150, 40)) // ~2 cells, too narrow for controls
    }

    @Test fun singleCellIsTiny() {
        // 1×1 on a typical launcher grid: ~60–100dp square → TINY (#31).
        assertEquals(Tier.TINY, WidgetRender.sizeTier(70, 70))
        assertEquals(Tier.TINY, WidgetRender.sizeTier(109, 109))
        // Just past either edge climbs back into SMALL.
        assertEquals(Tier.SMALL, WidgetRender.sizeTier(110, 90)) // wide enough
        assertEquals(Tier.SMALL, WidgetRender.sizeTier(90, 110)) // tall enough
    }

    /** #29 reverted: a big/tall box now resolves to MEDIUM (no history chart). */
    @Test fun bigBoxIsMedium() {
        assertEquals(Tier.MEDIUM, WidgetRender.sizeTier(180, 220))
        assertEquals(Tier.MEDIUM, WidgetRender.sizeTier(300, 300))
        assertEquals(Tier.MEDIUM, WidgetRender.sizeTier(300, 200))
    }

    /** #33: lights/switches toggle on a body tap; covers/sensors open Haus's page. */
    @Test fun onlyLightAndSwitchToggleOnBodyTap() {
        assertEquals(true, WidgetRender.togglesOnBodyTap("light"))
        assertEquals(true, WidgetRender.togglesOnBodyTap("switch"))
        assertEquals(false, WidgetRender.togglesOnBodyTap("cover"))
        assertEquals(false, WidgetRender.togglesOnBodyTap("sensor"))
        assertEquals(false, WidgetRender.togglesOnBodyTap("climate"))
        assertEquals(false, WidgetRender.togglesOnBodyTap(""))
    }

    /** #55: at a cover's stop the impossible direction is disabled (grey + inert). */
    @Test fun coverDirectionDisabledAtTheStop() {
        // Fully open (>=99): up is dead, down is live.
        assertEquals(false, WidgetRender.coverUpEnabled(100))
        assertEquals(false, WidgetRender.coverUpEnabled(99))
        assertEquals(true, WidgetRender.coverDownEnabled(100))
        // Fully closed (<=1): down is dead, up is live.
        assertEquals(false, WidgetRender.coverDownEnabled(0))
        assertEquals(false, WidgetRender.coverDownEnabled(1))
        assertEquals(true, WidgetRender.coverUpEnabled(0))
        // Mid travel: both directions live.
        assertEquals(true, WidgetRender.coverUpEnabled(50))
        assertEquals(true, WidgetRender.coverDownEnabled(50))
        assertEquals(true, WidgetRender.coverUpEnabled(98))
        assertEquals(true, WidgetRender.coverDownEnabled(2))
        // Unknown position (null): both stay enabled (don't dead-lock on missing data).
        assertEquals(true, WidgetRender.coverUpEnabled(null))
        assertEquals(true, WidgetRender.coverDownEnabled(null))
    }

    /** #38: only garage/door/gate covers are lock-gated (need a confirm). */
    @Test fun onlySensitiveCoverClassesNeedTheLockBadge() {
        assertEquals(true, cloud.dopp.haus.data.isSensitiveDevice("cover", "garage"))
        assertEquals(true, cloud.dopp.haus.data.isSensitiveDevice("cover", "door"))
        assertEquals(true, cloud.dopp.haus.data.isSensitiveDevice("cover", "gate"))
        // Ordinary covers + other domains are not gated.
        assertEquals(false, cloud.dopp.haus.data.isSensitiveDevice("cover", "shade"))
        assertEquals(false, cloud.dopp.haus.data.isSensitiveDevice("cover", null))
        assertEquals(false, cloud.dopp.haus.data.isSensitiveDevice("light", "garage"))
        assertEquals(false, cloud.dopp.haus.data.isSensitiveDevice(null, "garage"))
    }
}
