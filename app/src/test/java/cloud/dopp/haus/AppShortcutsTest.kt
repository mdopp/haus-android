package cloud.dopp.haus

import android.appwidget.AppWidgetManager
import android.content.Intent
import cloud.dopp.haus.widget.AppShortcuts
import cloud.dopp.haus.widget.DeviceShortcuts
import cloud.dopp.haus.widget.ShortcutAction
import cloud.dopp.haus.widget.ShortcutDevice
import cloud.dopp.haus.widget.WidgetActionActivity
import cloud.dopp.haus.widget.WidgetActionReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The app-icon long-press menu (#97).
 *
 * A long-press menu on the *widget* is the launcher's, not ours (#96), so the
 * device entries live on the app icon. Three things must hold and all are pure
 * JVM logic: **how many** entries appear (the cap is the launcher's answer, not
 * a guessed four), **which ones and in what order** (the household catalogue by
 * last use, #100 — no longer only the placed widgets), and **what a lock's entry
 * does** (open the #92 chooser, never switch, never reach `lock.open`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppShortcutsTest {

    private fun dev(id: Int, entity: String, name: String, domain: String, cls: String? = null) =
        ShortcutDevice(id, entity, name, domain, cls)

    /** A catalogue device: in the household, but on no home screen (#100). */
    private fun cat(entity: String, name: String, domain: String, cls: String? = null) =
        ShortcutDevice(null, entity, name, domain, cls)

    private fun lamps(n: Int) = (1..n).map { dev(it, "light.l$it", "Lampe $it", "light") }

    // --- the count: empty, at the cap, over it ---------------------------------

    @Test fun noDevicesMeansNoEntries() {
        assertTrue(DeviceShortcuts.entries(emptyList(), 4).isEmpty())
    }

    @Test fun everyDeviceFitsBelowTheCap() {
        assertEquals(3, DeviceShortcuts.entries(lamps(3), 4).size)
    }

    @Test fun exactlyAtTheCapNothingIsDropped() {
        val e = DeviceShortcuts.entries(lamps(4), 4)
        assertEquals(4, e.size)
        assertEquals(setOf("light.l1", "light.l2", "light.l3", "light.l4"), e.map { it.device.entityId }.toSet())
    }

    /** Over the cap: the newest widgets win, and the rule is an order, not a truncation. */
    @Test fun overTheCapTheMostRecentlyAddedWin() {
        val e = DeviceShortcuts.entries(lamps(7), 3)
        assertEquals(3, e.size)
        assertEquals(listOf("light.l7", "light.l6", "light.l5"), e.map { it.device.entityId })
    }

    /** The order does not depend on the order the store happened to hand them over. */
    @Test fun theRuleIsDeterministicWhateverTheInputOrder() {
        val a = DeviceShortcuts.entries(lamps(7), 3)
        val b = DeviceShortcuts.entries(lamps(7).shuffled(), 3)
        assertEquals(a.map { it.device.entityId }, b.map { it.device.entityId })
    }

    /** A launcher that allows nothing (or an API < 25 phone, where the compat cap is 0). */
    @Test fun aZeroCapPublishesNothing() {
        for (cap in listOf(0, -1)) assertTrue(DeviceShortcuts.entries(lamps(5), cap).isEmpty())
    }

    @Test fun theSameDeviceOnTwoWidgetsIsOneEntry() {
        val e = DeviceShortcuts.entries(
            listOf(dev(1, "light.flur", "Flur", "light"), dev(9, "light.flur", "Flur", "light")),
            4,
        )
        assertEquals(1, e.size)
        assertEquals(9, e[0].device.appWidgetId) // the newer widget carries it
    }

    @Test fun anUnconfiguredWidgetIsNotADevice() {
        assertTrue(DeviceShortcuts.entries(listOf(dev(1, "", "—", "")), 4).isEmpty())
    }

    @Test fun anUnnamedDeviceStillGetsAReadableEntry() {
        val e = DeviceShortcuts.entries(listOf(dev(1, "switch.x", "", "switch")), 4)
        assertEquals("switch.x", e[0].label)
    }

    // --- catalogue + history + cap → entries (#100) -----------------------------

    /** Fresh install: nothing has been switched, so the placed widgets are the order. */
    @Test fun withoutHistoryTheWidgetOrderStands() {
        val devices = listOf(
            cat("light.keller", "Keller", "light"),
            dev(2, "light.flur", "Flur", "light"),
            cat("switch.pumpe", "Pumpe", "switch"),
            dev(5, "light.kueche", "Küche", "light"),
        )
        val e = DeviceShortcuts.entries(devices, 4, emptyList())
        // Widgets first, newest instance first; then the catalogue's own order.
        assertEquals(
            listOf("light.kueche", "light.flur", "light.keller", "switch.pumpe"),
            e.map { it.device.entityId },
        )
    }

    /** The rule proper: last switched wins, over any widget. */
    @Test fun theHistoryOutranksTheWidgets() {
        val devices = listOf(
            dev(9, "light.flur", "Flur", "light"),
            cat("switch.pumpe", "Pumpe", "switch"),
            cat("light.keller", "Keller", "light"),
        )
        val e = DeviceShortcuts.entries(devices, 3, listOf("light.keller", "switch.pumpe"))
        assertEquals(
            listOf("light.keller", "switch.pumpe", "light.flur"),
            e.map { it.device.entityId },
        )
    }

    /** More devices than slots: the cap cuts the tail, not the recently used. */
    @Test fun overTheCapTheRecentlyUsedSurvive() {
        val devices = (1..9).map { cat("light.l$it", "Lampe $it", "light") }
        val e = DeviceShortcuts.entries(devices, 3, listOf("light.l8", "light.l3"))
        assertEquals(listOf("light.l8", "light.l3", "light.l1"), e.map { it.device.entityId })
    }

    /** A device switched once and later removed from the household is simply gone. */
    @Test fun aHistoryEntryMissingFromTheCatalogueTakesNoSlot() {
        val devices = listOf(
            cat("light.flur", "Flur", "light"),
            cat("switch.pumpe", "Pumpe", "switch"),
        )
        val e = DeviceShortcuts.entries(devices, 4, listOf("light.abgebaut", "switch.pumpe"))
        assertEquals(listOf("switch.pumpe", "light.flur"), e.map { it.device.entityId })
    }

    /** A widget-less device is a full entry — that was the whole complaint. */
    @Test fun aDeviceWithoutAWidgetStillGetsAnEntry() {
        val e = DeviceShortcuts.entries(listOf(cat("light.keller", "Keller", "light")), 4)
        assertEquals(1, e.size)
        assertNull(e[0].device.appWidgetId)
        assertTrue(e[0].actsDirectly)
    }

    /** Listed twice — as a catalogue row and as a widget — it appears once, with the id. */
    @Test fun theWidgetCopyWinsWhenADeviceIsListedTwice() {
        val e = DeviceShortcuts.entries(
            listOf(cat("light.flur", "Flur", "light"), dev(4, "light.flur", "Flur", "light")),
            4,
        )
        assertEquals(1, e.size)
        assertEquals(4, e[0].device.appWidgetId)
    }

    /** An entity id names its own domain — the only handle a widget-less device has. */
    @Test fun theDomainComesOutOfTheEntityId() {
        assertEquals("lock", DeviceShortcuts.domainOf("lock.haustuer"))
        assertEquals("light", DeviceShortcuts.domainOf("light.flur"))
        // Nothing usable → a domain that acts on nothing.
        for (junk in listOf(null, "", "   ", "haustuer")) {
            assertFalse("junk $junk", DeviceShortcuts.actsDirectly(DeviceShortcuts.domainOf(junk)))
        }
    }

    // --- the security rules ----------------------------------------------------

    /** The core guard: a lock never gets an entry that switches anything. */
    @Test fun aLockNeverActsDirectly() {
        val e = DeviceShortcuts.entries(listOf(dev(1, "lock.haustuer", "Haustür", "lock")), 4)
        assertEquals(1, e.size)
        assertEquals(ShortcutAction.CHOOSE, e[0].action)
        assertFalse(e[0].actsDirectly)
        assertFalse(DeviceShortcuts.actsDirectly("lock"))
    }

    /** Neither does any other safety-gated device — a garage door opens its card. */
    @Test fun sensitiveDevicesDoNotActEither() {
        val devices = listOf(
            dev(1, "cover.garage", "Garage", "cover", "garage"),
            dev(2, "alarm_control_panel.haus", "Alarm", "alarm_control_panel"),
        )
        for (e in DeviceShortcuts.entries(devices, 4)) {
            assertFalse("${e.device.entityId} acts", e.actsDirectly)
            assertEquals(ShortcutAction.OPEN, e.action)
        }
    }

    /** Light and switch may act — a mistap there is harmless, which is the point. */
    @Test fun harmlessDevicesMayAct() {
        val devices = listOf(dev(1, "light.a", "Licht", "light"), dev(2, "switch.b", "Schalter", "switch"))
        for (e in DeviceShortcuts.entries(devices, 4)) assertTrue(e.actsDirectly)
    }

    /** Mixed household, cap exceeded: whatever survives, no lock is an actor. */
    @Test fun noLockActsWhateverTheMix() {
        val devices = listOf(
            dev(1, "light.a", "Licht", "light"),
            dev(2, "lock.haustuer", "Haustür", "lock"),
            dev(3, "lock.keller", "Keller", "lock"),
            dev(4, "switch.b", "Schalter", "switch"),
            dev(5, "cover.tor", "Tor", "cover", "gate"),
        )
        for (cap in 1..6) {
            for (e in DeviceShortcuts.entries(devices, cap)) {
                if (e.device.domain == "lock") assertFalse("cap $cap", e.actsDirectly)
            }
        }
    }

    // --- what the intents actually carry ---------------------------------------

    /** A lock's entry opens the chooser activity, carries no service, and says so. */
    @Test fun theLockEntryOpensTheChooserAndNothingElse() {
        val ctx = RuntimeEnvironment.getApplication()
        val entry = DeviceShortcuts.entries(listOf(dev(7, "lock.haustuer", "Haustür", "lock")), 4)[0]
        val i = AppShortcuts.intentFor(ctx, entry)

        assertEquals(WidgetActionActivity::class.java.name, i.component?.className)
        assertTrue(i.getBooleanExtra(WidgetActionActivity.EXTRA_LOCK_CHOOSE, false))
        assertFalse(i.getBooleanExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, false))
        // No service rides along — the pick in the chooser names it.
        assertNull(i.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
        assertEquals(7, i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    /** No entry, for any device, may name the latch. */
    @Test fun nothingPublishedCanReachLockOpen() {
        val ctx = RuntimeEnvironment.getApplication()
        val devices = listOf(
            dev(1, "lock.haustuer", "Haustür", "lock"),
            dev(2, "cover.garage", "Garage", "cover", "garage"),
            dev(3, "light.a", "Licht", "light"),
            // …and the same again from the catalogue, with no widget behind them.
            cat("lock.keller", "Keller", "lock"),
            cat("cover.tor", "Tor", "cover", "gate"),
        )
        for (e in DeviceShortcuts.entries(devices, 8)) {
            val i = AppShortcuts.intentFor(ctx, e)
            assertNull(i.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
            assertFalse(i.toUri(Intent.URI_INTENT_SCHEME).contains("lock.open"))
        }
    }

    /** A non-sensitive entry lands on the trampoline that asks for the widget's toggle. */
    @Test fun aLampEntryAsksForTheOrdinaryToggle() {
        val ctx = RuntimeEnvironment.getApplication()
        val entry = DeviceShortcuts.entries(listOf(dev(3, "light.flur", "Flur", "light")), 4)[0]
        val i = AppShortcuts.intentFor(ctx, entry)

        assertEquals(WidgetActionActivity::class.java.name, i.component?.className)
        assertTrue(i.getBooleanExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, false))
        assertFalse(i.getBooleanExtra(WidgetActionActivity.EXTRA_LOCK_CHOOSE, false))
    }

    /** A sensitive non-lock entry only opens a page: Haus's start page, in the browser. */
    @Test fun aGarageEntryOnlyOpensThePage() {
        val ctx = RuntimeEnvironment.getApplication()
        cloud.dopp.haus.data.ServerStore.setBaseUrl(ctx, "https://haus.example")
        val entry = DeviceShortcuts.entries(listOf(dev(4, "cover.garage", "Garage", "cover", "garage")), 4)[0]
        val i = AppShortcuts.intentFor(ctx, entry)

        assertEquals(android.content.Intent.ACTION_VIEW, i.action)
        assertEquals("https://haus.example/start", i.data?.toString())
        assertNull("no component of ours runs anything", i.component)
        assertFalse(i.getBooleanExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, false))
        cloud.dopp.haus.data.ServerStore.clear(ctx)
    }

    /**
     * A widget-less entry identifies its device by **entity id and nothing else**
     * (#100). No `appWidgetId` is invented for it: any number would name some
     * other instance's binding, and the receiver resolves the entity from it.
     */
    @Test fun aCatalogueEntryCarriesTheEntityAndNoWidgetId() {
        val ctx = RuntimeEnvironment.getApplication()
        val entry = DeviceShortcuts.entries(listOf(cat("light.keller", "Keller", "light")), 4)[0]
        val i = AppShortcuts.intentFor(ctx, entry)

        assertFalse(i.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID))
        assertEquals("light.keller", i.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
        assertTrue(i.getBooleanExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, false))
    }

    /** A widget-less lock is still only a chooser — the source changed, the rule did not. */
    @Test fun aCatalogueLockStillOnlyOpensTheChooser() {
        val ctx = RuntimeEnvironment.getApplication()
        val entry = DeviceShortcuts.entries(listOf(cat("lock.haustuer", "Haustür", "lock")), 4)[0]
        assertEquals(ShortcutAction.CHOOSE, entry.action)
        assertFalse(entry.actsDirectly)

        val i = AppShortcuts.intentFor(ctx, entry)
        assertEquals(WidgetActionActivity::class.java.name, i.component?.className)
        assertTrue(i.getBooleanExtra(WidgetActionActivity.EXTRA_LOCK_CHOOSE, false))
        assertFalse(i.getBooleanExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, false))
        assertNull(i.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
        assertFalse(i.toUri(Intent.URI_INTENT_SCHEME).contains("lock.open"))
        assertFalse(i.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID))
    }

    /** A widget's entry keeps its instance id — that is what refreshes the tile. */
    @Test fun aPlacedDeviceStillCarriesItsWidgetId() {
        val ctx = RuntimeEnvironment.getApplication()
        val entry = DeviceShortcuts.entries(listOf(dev(6, "light.flur", "Flur", "light")), 4)[0]
        assertEquals(6, AppShortcuts.intentFor(ctx, entry).getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    /** Shortcut ids are stable per entity, so a refresh updates instead of churning. */
    @Test fun idsAreStablePerEntity() {
        val once = DeviceShortcuts.entries(lamps(3), 4).map { it.id }
        val again = DeviceShortcuts.entries(lamps(3), 4).map { it.id }
        assertEquals(once, again)
        assertTrue(once.all { it.startsWith(DeviceShortcuts.ID_PREFIX) })
    }
}
