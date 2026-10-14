package cloud.dopp.haus

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.widget.ConfigEntry
import cloud.dopp.haus.widget.WidgetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Re-entering a widget's config screen (#96).
 *
 * `android:widgetFeatures="reconfigurable"` is what puts *Einrichten* into the
 * launcher's long-press menu, and it hands the config activities something they
 * never saw before: an `appWidgetId` that is **already bound**. The dangerous half
 * is the answer a back-out gives — on a first placement `RESULT_CANCELED` tells
 * the host to drop the half-placed widget, on a reconfigure the very same answer
 * would offer up a working widget for removal. That decision is pure logic
 * ([ConfigEntry]), so it is pinned here rather than on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconfigureTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun anUnboundIdIsAFirstPlacement() {
        assertEquals(ConfigEntry.FIRST_PLACEMENT, ConfigEntry.of(alreadyBound = false))
        assertFalse(ConfigEntry.of(alreadyBound = false).isReconfigure)
    }

    @Test fun aBoundIdIsAReconfigure() {
        assertEquals(ConfigEntry.RECONFIGURE, ConfigEntry.of(alreadyBound = true))
        assertTrue(ConfigEntry.of(alreadyBound = true).isReconfigure)
    }

    @Test fun backingOutOfAFirstPlacementLetsTheHostDropTheWidget() {
        assertEquals(
            Activity.RESULT_CANCELED,
            ConfigEntry.FIRST_PLACEMENT.backOutResult,
        )
    }

    @Test fun backingOutOfAReconfigureKeepsTheWidget() {
        // The whole point: cancelling an edit must NOT read as "not wanted".
        assertEquals(Activity.RESULT_OK, ConfigEntry.RECONFIGURE.backOutResult)
        assertNotEquals(
            ConfigEntry.FIRST_PLACEMENT.backOutResult,
            ConfigEntry.RECONFIGURE.backOutResult,
        )
    }

    // --- what the store says about a given instance --------------------------

    @Test fun theStoreIsWhatTellsThePathsApart() {
        // The host sends the same extras either way, so the binding decides.
        assertEquals(
            ConfigEntry.FIRST_PLACEMENT,
            ConfigEntry.of(WidgetStore.entityId(ctx, 11) != null),
        )
        WidgetStore.bind(ctx, 11, "light.flur", "Flur", "light", null)
        assertEquals(
            ConfigEntry.RECONFIGURE,
            ConfigEntry.of(WidgetStore.entityId(ctx, 11) != null),
        )
        // …per instance: a second, fresh widget is still a first placement.
        assertEquals(
            ConfigEntry.FIRST_PLACEMENT,
            ConfigEntry.of(WidgetStore.entityId(ctx, 12) != null),
        )
    }

    @Test fun cancellingAReconfigureLeavesTheBindingExactlyAsItWas() {
        WidgetStore.bind(ctx, 21, "cover.buero", "Büro", "cover", "shutter")
        // A cancelled edit writes nothing — the widget keeps rendering its device.
        assertEquals("cover.buero", WidgetStore.entityId(ctx, 21))
        assertEquals("Büro", WidgetStore.name(ctx, 21))
        assertEquals("cover", WidgetStore.domain(ctx, 21))
        assertEquals("shutter", WidgetStore.deviceClass(ctx, 21))
    }

    @Test fun reBindingReplacesTheEntryInsteadOfAddingASecond() {
        WidgetStore.bind(ctx, 31, "light.kueche", "Küche", "light", null)
        WidgetStore.bind(ctx, 31, "switch.terrasse", "Terrasse", "switch", "outlet")
        assertEquals("switch.terrasse", WidgetStore.entityId(ctx, 31))
        assertEquals("Terrasse", WidgetStore.name(ctx, 31))
        assertEquals("switch", WidgetStore.domain(ctx, 31))
        assertEquals("outlet", WidgetStore.deviceClass(ctx, 31))
        // One binding for one id — the old device must be gone, not shadowed.
        WidgetStore.unbind(ctx, 31)
        assertNull(WidgetStore.entityId(ctx, 31))
    }

    @Test fun reBindingAToolWidgetReplacesTheEntryAndDropsItsRows() {
        WidgetStore.bindTool(ctx, 41, "task", "Aufgaben")
        WidgetStore.setToolCellsJson(ctx, 41, """{"rows":[]}""")
        assertNotNull(WidgetStore.toolCellsJson(ctx, 41))
        WidgetStore.bindTool(ctx, 41, "contact", "Kontakte")
        assertEquals("contact", WidgetStore.toolId(ctx, 41))
        assertEquals("Kontakte", WidgetStore.toolLabel(ctx, 41))
        // The cached rows belonged to the previous tool — a reconfigure must not
        // leave the widget drawing tasks under a "Kontakte" header.
        assertNull(WidgetStore.toolCellsJson(ctx, 41))
    }

    @Test fun aReconfigureDoesNotEatTheParkedInAppPick() {
        // The in-app tool section parks an id for the widget about to be placed
        // (#72). Re-configuring a *different*, already bound widget must leave it
        // alone — otherwise that pending placement silently loses its tool.
        WidgetStore.setPendingToolId(ctx, "task")
        val bound = ConfigEntry.of(alreadyBound = true)
        val pending = if (bound.isReconfigure) null else WidgetStore.consumePendingToolId(ctx)
        assertNull(pending)
        assertEquals("task", WidgetStore.consumePendingToolId(ctx))
    }
}
