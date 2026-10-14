package cloud.dopp.haus

import android.appwidget.AppWidgetManager
import android.content.Intent
import cloud.dopp.haus.widget.DeviceShortcuts
import cloud.dopp.haus.widget.ShortcutCatalog
import cloud.dopp.haus.widget.ShortcutDevice
import cloud.dopp.haus.widget.ShortcutUsage
import cloud.dopp.haus.widget.WidgetActionActivity
import cloud.dopp.haus.widget.WidgetActionReceiver
import cloud.dopp.haus.widget.WidgetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The two sources behind the app-icon menu (#100): the capped **usage history**
 * that orders it, and the cached **device catalogue** it is drawn from — plus the
 * path that makes a widget-less entry actually work.
 *
 * That path is the substance of the change. `EXTRA_APPWIDGET_ID` used to be the
 * only handle a shortcut had, and a device that is not on the home screen has no
 * such id; the entity id has to carry the tap on its own, **without** loosening
 * the rule that a lock never switches from here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShortcutSourcesTest {

    // --- the usage history ------------------------------------------------------

    @Test fun theMostRecentSwitchComesFirst() {
        var h = ShortcutUsage.record(emptyList(), "light.flur")
        h = ShortcutUsage.record(h, "switch.pumpe")
        assertEquals(listOf("switch.pumpe", "light.flur"), h)
    }

    /** Switching the same device again moves it up; it never appears twice. */
    @Test fun repeatingADeviceDoesNotDuplicateIt() {
        val h = ShortcutUsage.record(listOf("a.1", "b.2", "c.3"), "c.3")
        assertEquals(listOf("c.3", "a.1", "b.2"), h)
    }

    /** A capped list, not a log — the oldest falls off the end. */
    @Test fun theHistoryIsCapped() {
        var h = emptyList<String>()
        for (i in 1..40) h = ShortcutUsage.record(h, "light.l$i", cap = 5)
        assertEquals(5, h.size)
        assertEquals(listOf("light.l40", "light.l39", "light.l38", "light.l37", "light.l36"), h)
    }

    @Test fun nothingUsableIsNotRecorded() {
        assertEquals(listOf("a.1"), ShortcutUsage.record(listOf("a.1"), ""))
    }

    @Test fun theHistorySurvivesARoundTrip() {
        val h = listOf("light.flur", "lock.haustuer")
        assertEquals(h, ShortcutUsage.decode(ShortcutUsage.encode(h)))
    }

    /** A corrupt or absent history reads as "nothing switched yet", never a crash. */
    @Test fun anUnreadableHistoryIsNoHistory() {
        for (junk in listOf(null, "", "   ", "{", "not json")) {
            assertTrue("junk $junk", ShortcutUsage.decode(junk).isEmpty())
        }
    }

    /** The store is what the receiver writes into and the publisher reads back. */
    @Test fun theStoreRemembersWhatWasSwitched() {
        val ctx = RuntimeEnvironment.getApplication()
        assertTrue(WidgetStore.recentEntities(ctx).isEmpty())
        WidgetStore.noteEntityUsed(ctx, "light.flur")
        WidgetStore.noteEntityUsed(ctx, "switch.pumpe")
        WidgetStore.noteEntityUsed(ctx, "light.flur")
        assertEquals(listOf("light.flur", "switch.pumpe"), WidgetStore.recentEntities(ctx))
    }

    // --- the cached catalogue ---------------------------------------------------

    @Test fun theCatalogueSurvivesARoundTrip() {
        val devices = listOf(
            ShortcutDevice(null, "light.flur", "Flur", "light"),
            ShortcutDevice(null, "cover.garage", "Garage", "cover", "garage"),
        )
        assertEquals(devices, ShortcutCatalog.decode(ShortcutCatalog.encode(devices)))
    }

    /** The cache is the household, not the home screen — no widget ids come back. */
    @Test fun aCachedDeviceCarriesNoWidgetId() {
        val json = ShortcutCatalog.encode(listOf(ShortcutDevice(7, "light.flur", "Flur", "light")))
        assertNull(ShortcutCatalog.decode(json).single().appWidgetId)
    }

    @Test fun anUnreadableCatalogueIsNoCatalogue() {
        for (junk in listOf(null, "", "nonsense", """[{"n":"kein entity_id"}]""")) {
            assertTrue("junk $junk", ShortcutCatalog.decode(junk).isEmpty())
        }
    }

    @Test fun aFreshInstallHasNoFreshCatalogue() {
        val ctx = RuntimeEnvironment.getApplication()
        assertFalse(WidgetStore.shortcutCatalogFresh(ctx))
        WidgetStore.setShortcutCatalog(ctx, listOf(ShortcutDevice(null, "light.flur", "Flur", "light")))
        assertTrue(WidgetStore.shortcutCatalogFresh(ctx))
        assertEquals("light.flur", WidgetStore.shortcutCatalog(ctx).single().entityId)
    }

    // --- the widget-less action path -------------------------------------------

    private fun shortcutTap(entityId: String, appWidgetId: Int? = null) =
        Intent(RuntimeEnvironment.getApplication(), WidgetActionActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(WidgetActionReceiver.EXTRA_ENTITY, entityId)
            .putExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, true)
            .apply { appWidgetId?.let { putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, it) } }

    /** Everything the trampoline handed on, since the last time we looked. */
    private fun tapsFrom(intent: Intent): List<Intent> {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).clearBroadcastIntents()
        Robolectric.buildActivity(WidgetActionActivity::class.java, intent).create()
        return shadowOf(app).broadcastIntents
            .filter { it.action == WidgetActionReceiver.ACTION_TAP }
    }

    /** A device with no widget can be switched — the entity id is the whole handle. */
    @Test fun aWidgetLessLampIsSwitchedFromItsEntityIdAlone() {
        val taps = tapsFrom(shortcutTap("light.keller"))
        assertEquals(1, taps.size)
        assertEquals("light.keller", taps[0].getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
        assertEquals(WidgetActionReceiver.OP_TOGGLE, taps[0].getStringExtra(WidgetActionReceiver.EXTRA_OP))
        // No placeholder id was invented — that would name another instance's binding.
        assertEquals(
            AppWidgetManager.INVALID_APPWIDGET_ID,
            taps[0].getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
        )
    }

    /**
     * The guard that must not have loosened: a `lock` entity reaches the receiver
     * from nowhere, with or without a widget behind it.
     */
    @Test fun aWidgetLessLockIsNeverSwitched() {
        assertTrue(tapsFrom(shortcutTap("lock.haustuer")).isEmpty())
        assertTrue(tapsFrom(shortcutTap("lock.keller")).isEmpty())
        // Nor is any other safety-gated device.
        assertTrue(tapsFrom(shortcutTap("alarm_control_panel.haus")).isEmpty())
    }

    /** Nor may a made-up entity id: no domain, no action. */
    @Test fun anEntityWithoutADomainDoesNothing() {
        for (junk in listOf("", "haustuer", ".")) {
            assertTrue("junk '$junk'", tapsFrom(shortcutTap(junk)).isEmpty())
        }
    }

    /**
     * With a widget behind it the identity still comes from the **store**: a
     * forged shortcut naming a lamp but pointing at a lock's instance must not
     * switch anything.
     */
    @Test fun aWidgetBackedTapStillTrustsTheStoreOverTheIntent() {
        val ctx = RuntimeEnvironment.getApplication()
        WidgetStore.bind(ctx, 11, "lock.haustuer", "Haustür", "lock", null)
        assertTrue(tapsFrom(shortcutTap("light.keller", appWidgetId = 11)).isEmpty())

        WidgetStore.bind(ctx, 12, "light.flur", "Flur", "light", null)
        val taps = tapsFrom(shortcutTap("light.flur", appWidgetId = 12))
        assertEquals(1, taps.size)
        assertEquals(12, taps[0].getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        // The store answers the identity, so no entity rides along.
        assertNull(taps[0].getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
    }

    /** And the rule tables agree with all of the above. */
    @Test fun theDomainRulesAreUnchanged() {
        assertFalse(DeviceShortcuts.actsDirectly(DeviceShortcuts.domainOf("lock.haustuer")))
        assertTrue(DeviceShortcuts.actsDirectly(DeviceShortcuts.domainOf("light.keller")))
        assertTrue(DeviceShortcuts.actsDirectly(DeviceShortcuts.domainOf("switch.pumpe")))
    }
}
