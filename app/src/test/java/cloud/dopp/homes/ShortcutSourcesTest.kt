package cloud.dopp.homes

import android.content.Intent
import cloud.dopp.homes.widget.DeviceShortcuts
import cloud.dopp.homes.widget.ShortcutCatalog
import cloud.dopp.homes.widget.ShortcutDevice
import cloud.dopp.homes.widget.WidgetActionActivity
import cloud.dopp.homes.widget.WidgetActionReceiver
import cloud.dopp.homes.widget.WidgetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The cached **device catalogue** the garage shortcut is looked for in (#100,
 * owner 2026-11-04) — plus the path that makes a widget-less entry actually work.
 *
 * That path is the substance of the change. `EXTRA_APPWIDGET_ID` used to be the
 * only handle a shortcut had, and a device that is not on the home screen has no
 * such id; the entity id has to carry the tap on its own, **without** loosening
 * the rule that a lock never switches from here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShortcutSourcesTest {

    // --- the cached catalogue ---------------------------------------------------

    @Test fun theCatalogueSurvivesARoundTrip() {
        val devices = listOf(
            ShortcutDevice("light.flur", "Flur", "light"),
            ShortcutDevice("cover.garage", "Garage", "cover", "garage"),
        )
        assertEquals(devices, ShortcutCatalog.decode(ShortcutCatalog.encode(devices)))
    }

    @Test fun anUnreadableCatalogueIsNoCatalogue() {
        for (junk in listOf(null, "", "nonsense", """[{"n":"kein entity_id"}]""")) {
            assertTrue("junk $junk", ShortcutCatalog.decode(junk).isEmpty())
        }
    }

    @Test fun aFreshInstallHasNoFreshCatalogue() {
        val ctx = RuntimeEnvironment.getApplication()
        assertFalse(WidgetStore.shortcutCatalogFresh(ctx))
        WidgetStore.setShortcutCatalog(ctx, listOf(ShortcutDevice("light.flur", "Flur", "light")))
        assertTrue(WidgetStore.shortcutCatalogFresh(ctx))
        assertEquals("light.flur", WidgetStore.shortcutCatalog(ctx).single().entityId)
    }

    // --- the action path -------------------------------------------------------

    private fun shortcutTap(entityId: String) =
        Intent(RuntimeEnvironment.getApplication(), WidgetActionActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(WidgetActionReceiver.EXTRA_ENTITY, entityId)
            .putExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, true)

    /** Everything the trampoline handed on, since the last time we looked. */
    private fun tapsFrom(intent: Intent): List<Intent> {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).clearBroadcastIntents()
        Robolectric.buildActivity(WidgetActionActivity::class.java, intent).create()
        return shadowOf(app).broadcastIntents
            .filter { it.action == WidgetActionReceiver.ACTION_TAP }
    }

    /** A device is switched from its entity id alone — the whole handle. */
    @Test fun aLampIsSwitchedFromItsEntityIdAlone() {
        val taps = tapsFrom(shortcutTap("light.keller"))
        assertEquals(1, taps.size)
        assertEquals("light.keller", taps[0].getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
        assertEquals(WidgetActionReceiver.OP_TOGGLE, taps[0].getStringExtra(WidgetActionReceiver.EXTRA_OP))
    }

    /** The garage's shortcut is the garage tile's tap: the receiver asks Homes, whose 403 brings the confirm. */
    @Test fun theGarageIsTappedAsOnTheStartScreen() {
        val taps = tapsFrom(shortcutTap("cover.garage"))
        assertEquals(1, taps.size)
        assertEquals(WidgetActionReceiver.OP_TOGGLE, taps[0].getStringExtra(WidgetActionReceiver.EXTRA_OP))
        assertEquals(null, taps[0].getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
    }

    /** The guard that must not have loosened: a `lock` entity reaches the receiver from nowhere. */
    @Test fun aLockIsNeverSwitched() {
        assertTrue(tapsFrom(shortcutTap("lock.haustuer")).isEmpty())
        assertTrue(tapsFrom(shortcutTap("lock.keller")).isEmpty())
        // Nor is any other safety-gated device.
        assertTrue(tapsFrom(shortcutTap("alarm_control_panel.homes")).isEmpty())
    }

    /** Nor may a made-up entity id: no domain, no action. */
    @Test fun anEntityWithoutADomainDoesNothing() {
        for (junk in listOf("", "haustuer", ".")) {
            assertTrue("junk '$junk'", tapsFrom(shortcutTap(junk)).isEmpty())
        }
    }

    /** And the rule tables agree with all of the above. */
    @Test fun theDomainRulesAreUnchanged() {
        assertFalse(DeviceShortcuts.actsDirectly(DeviceShortcuts.domainOf("lock.haustuer")))
        assertTrue(DeviceShortcuts.actsDirectly(DeviceShortcuts.domainOf("light.keller")))
        assertTrue(DeviceShortcuts.actsDirectly(DeviceShortcuts.domainOf("switch.pumpe")))
        assertTrue(DeviceShortcuts.actsDirectly(DeviceShortcuts.domainOf("cover.garage")))
    }
}
