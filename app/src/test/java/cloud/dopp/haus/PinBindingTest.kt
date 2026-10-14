package cloud.dopp.haus

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.widget.ConfigEntry
import cloud.dopp.haus.widget.ToolWidgetProvider
import cloud.dopp.haus.widget.WidgetPin
import cloud.dopp.haus.widget.WidgetPinReceiver
import cloud.dopp.haus.widget.WidgetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Redeeming a pinned widget (#105).
 *
 * A tool widget placed from the in-app offer landed unbound and showed
 * "Tool wählen": `requestPinAppWidget` was called with a `null` successCallback,
 * and the parked tool id it fell back on is only ever collected by the config
 * activity — which most hosts never start. The callback is the primary path now,
 * the parked id the fallback, and **both can reach the same fresh instance**.
 *
 * That is the risk this file pins down: whichever of the two runs first must bind,
 * and the other must keep its hands off — one binding, never two, in either order.
 * Pure store logic, so it is decided here instead of on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinBindingTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val id = 4711

    /** What [cloud.dopp.haus.widget.ToolWidgetConfigActivity] does on entry. */
    private fun configActivityWouldBind(): String? {
        val reconfigure = ConfigEntry.of(WidgetStore.toolId(ctx, id) != null).isReconfigure
        return if (reconfigure) null else WidgetStore.consumePendingToolId(ctx)
    }

    // --- order 1: the callback arrives first (the real device's launcher) -----

    @Test fun theCallbackBindsTheInstanceTheHostAssigned() {
        WidgetStore.setPendingToolId(ctx, "documents")

        val outcome = WidgetPin.onPinned(ctx, id, "documents", "Dokumente")

        assertEquals(WidgetPin.Outcome.BOUND, outcome)
        assertEquals("documents", WidgetStore.toolId(ctx, id))
        assertEquals("Dokumente", WidgetStore.toolLabel(ctx, id))
    }

    @Test fun theCallbackSpendsTheParkedIdSoTheFallbackCannotBindAgain() {
        WidgetStore.setPendingToolId(ctx, "documents")
        WidgetPin.onPinned(ctx, id, "documents", "Dokumente")

        // The config activity, started late by a host that does start it, finds
        // nothing to redeem — and reads the instance as a reconfigure, not a
        // half-placed widget it must bind.
        assertNull(configActivityWouldBind())
        assertNull(WidgetStore.consumePendingToolId(ctx))
        assertTrue(ConfigEntry.of(WidgetStore.toolId(ctx, id) != null).isReconfigure)
    }

    @Test fun aParkedIdForAnotherPinSurvivesThisCallback() {
        // Two offers tapped in a row: the callback of the first must not eat the
        // hand-off of the second.
        WidgetStore.setPendingToolId(ctx, "photos")
        WidgetPin.onPinned(ctx, id, "documents", "Dokumente")

        assertEquals("photos", WidgetStore.consumePendingToolId(ctx))
    }

    // --- order 2: the config activity got there first ------------------------

    @Test fun aCallbackAfterTheConfigActivityDoesNotBindASecondTime() {
        WidgetStore.setPendingToolId(ctx, "documents")
        // The config activity ran: it consumed the parked id and bound.
        assertEquals("documents", configActivityWouldBind())
        WidgetStore.bindTool(ctx, id, "documents", "Dokumente")
        // Rows fetched since. bindTool drops them, so their survival is the proof
        // that the second binding never happened.
        WidgetStore.setToolCellsJson(ctx, id, """[{"title":"Vertrag"}]""")

        val outcome = WidgetPin.onPinned(ctx, id, "documents", "Dokumente")

        assertEquals(WidgetPin.Outcome.ALREADY_BOUND, outcome)
        assertEquals("documents", WidgetStore.toolId(ctx, id))
        assertEquals("""[{"title":"Vertrag"}]""", WidgetStore.toolCellsJson(ctx, id))
    }

    @Test fun aCallbackNeverOverwritesADifferentBinding() {
        // The user re-picked in the config screen while the callback was in flight.
        WidgetStore.bindTool(ctx, id, "tasks", "Aufgaben")

        assertEquals(
            WidgetPin.Outcome.ALREADY_BOUND,
            WidgetPin.onPinned(ctx, id, "documents", "Dokumente"),
        )
        assertEquals("tasks", WidgetStore.toolId(ctx, id))
    }

    // --- the guard itself ----------------------------------------------------

    @Test fun everyKindOfBindingCountsAsBound() {
        assertFalse(WidgetStore.isBound(ctx, id))
        WidgetStore.bind(ctx, id, "light.flur", "Flur", "light", null)
        assertTrue(WidgetStore.isBound(ctx, id))
        WidgetStore.unbind(ctx, id)
        assertFalse(WidgetStore.isBound(ctx, id))
        WidgetStore.bindTool(ctx, id, "documents", "Dokumente")
        assertTrue(WidgetStore.isBound(ctx, id))
    }

    // --- pins with nothing to bind -------------------------------------------

    @Test fun aPinWithoutAToolIsLeftToItsOwnConfiguration() {
        // The device/energy/camera offers: no tool rides along, so the callback
        // only nudges the tile into drawing its "einrichten" state.
        assertEquals(
            WidgetPin.Outcome.NEEDS_CONFIG, WidgetPin.onPinned(ctx, id, null, null),
        )
        assertFalse(WidgetStore.isBound(ctx, id))
    }

    @Test fun aBlankToolIdBindsNothing() {
        assertEquals(WidgetPin.Outcome.NEEDS_CONFIG, WidgetPin.onPinned(ctx, id, "", ""))
        assertNull(WidgetStore.toolId(ctx, id))
    }

    // --- the receiver end: the id comes from the host, the tool from us -------

    @Test fun theReceiverBindsTheIdTheHostFilledIn() {
        WidgetStore.setPendingToolId(ctx, "documents")
        val intent = WidgetPin.pinIntent(
            ctx, ComponentName(ctx, ToolWidgetProvider::class.java), "documents", "Dokumente",
        ).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id) // ← what the launcher adds

        WidgetPinReceiver().onReceive(ctx, intent)

        assertEquals("documents", WidgetStore.toolId(ctx, id))
    }

    @Test fun anAnswerWithoutAnIdBindsNothingAndKeepsTheFallback() {
        WidgetStore.setPendingToolId(ctx, "documents")
        val intent = WidgetPin.pinIntent(
            ctx, ComponentName(ctx, ToolWidgetProvider::class.java), "documents", "Dokumente",
        )

        WidgetPinReceiver().onReceive(ctx, intent)

        assertFalse(WidgetStore.isBound(ctx, id))
        // The parked id is untouched, so the config activity can still redeem it.
        assertEquals("documents", WidgetStore.consumePendingToolId(ctx))
    }
}
