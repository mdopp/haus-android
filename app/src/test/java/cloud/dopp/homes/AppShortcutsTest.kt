package cloud.dopp.homes

import android.content.Intent
import cloud.dopp.homes.widget.AppShortcuts
import cloud.dopp.homes.widget.DeviceShortcuts
import cloud.dopp.homes.widget.ShortcutAction
import cloud.dopp.homes.widget.ShortcutDevice
import cloud.dopp.homes.widget.WidgetActionActivity
import cloud.dopp.homes.widget.WidgetActionReceiver
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
 * last use, #100), and **what a lock's entry does** (open the #92 chooser,
 * never switch, never reach `lock.open`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppShortcutsTest {

    /** A device of the household catalogue (#100). */
    private fun dev(entity: String, name: String, domain: String, cls: String? = null) =
        ShortcutDevice(entity, name, domain, cls)

    private fun lamps(n: Int) = (1..n).map { dev("light.l$it", "Lampe $it", "light") }

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

    /** Over the cap without a history: the catalogue's own order, cut at the cap. */
    @Test fun overTheCapTheCatalogueOrderStands() {
        val e = DeviceShortcuts.entries(lamps(7), 3)
        assertEquals(listOf("light.l1", "light.l2", "light.l3"), e.map { it.device.entityId })
    }

    /** A launcher that allows nothing (or an API < 25 phone, where the compat cap is 0). */
    @Test fun aZeroCapPublishesNothing() {
        for (cap in listOf(0, -1)) assertTrue(DeviceShortcuts.entries(lamps(5), cap).isEmpty())
    }

    @Test fun theSameDeviceListedTwiceIsOneEntry() {
        val e = DeviceShortcuts.entries(
            listOf(dev("light.flur", "Flur", "light"), dev("light.flur", "Flur", "light")),
            4,
        )
        assertEquals(1, e.size)
    }

    @Test fun aRowWithoutAnEntityIsNotADevice() {
        assertTrue(DeviceShortcuts.entries(listOf(dev("", "—", "")), 4).isEmpty())
    }

    @Test fun anUnnamedDeviceStillGetsAReadableEntry() {
        val e = DeviceShortcuts.entries(listOf(dev("switch.x", "", "switch")), 4)
        assertEquals("switch.x", e[0].label)
    }

    // --- catalogue + history + cap → entries (#100) -----------------------------

    /** The rule proper: last switched wins, over the catalogue's order. */
    @Test fun theHistoryOutranksTheCatalogue() {
        val devices = listOf(
            dev("light.flur", "Flur", "light"),
            dev("switch.pumpe", "Pumpe", "switch"),
            dev("light.keller", "Keller", "light"),
        )
        val e = DeviceShortcuts.entries(devices, 3, listOf("light.keller", "switch.pumpe"))
        assertEquals(
            listOf("light.keller", "switch.pumpe", "light.flur"),
            e.map { it.device.entityId },
        )
    }

    /** More devices than slots: the cap cuts the tail, not the recently used. */
    @Test fun overTheCapTheRecentlyUsedSurvive() {
        val devices = (1..9).map { dev("light.l$it", "Lampe $it", "light") }
        val e = DeviceShortcuts.entries(devices, 3, listOf("light.l8", "light.l3"))
        assertEquals(listOf("light.l8", "light.l3", "light.l1"), e.map { it.device.entityId })
    }

    /** A device switched once and later removed from the household is simply gone. */
    @Test fun aHistoryEntryMissingFromTheCatalogueTakesNoSlot() {
        val devices = listOf(
            dev("light.flur", "Flur", "light"),
            dev("switch.pumpe", "Pumpe", "switch"),
        )
        val e = DeviceShortcuts.entries(devices, 4, listOf("light.abgebaut", "switch.pumpe"))
        assertEquals(listOf("switch.pumpe", "light.flur"), e.map { it.device.entityId })
    }

    /** An entity id names its own domain — the only handle a device has here. */
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
        val e = DeviceShortcuts.entries(listOf(dev("lock.haustuer", "Haustür", "lock")), 4)
        assertEquals(1, e.size)
        assertEquals(ShortcutAction.CHOOSE, e[0].action)
        assertFalse(e[0].actsDirectly)
        assertFalse(DeviceShortcuts.actsDirectly("lock"))
    }

    /** Neither does any other safety-gated device — a garage door opens its card. */
    @Test fun sensitiveDevicesDoNotActEither() {
        val devices = listOf(
            dev("cover.garage", "Garage", "cover", "garage"),
            dev("alarm_control_panel.homes", "Alarm", "alarm_control_panel"),
        )
        for (e in DeviceShortcuts.entries(devices, 4)) {
            assertFalse("${e.device.entityId} acts", e.actsDirectly)
            assertEquals(ShortcutAction.OPEN, e.action)
        }
    }

    /** Light and switch may act — a mistap there is harmless, which is the point. */
    @Test fun harmlessDevicesMayAct() {
        val devices = listOf(dev("light.a", "Licht", "light"), dev("switch.b", "Schalter", "switch"))
        for (e in DeviceShortcuts.entries(devices, 4)) assertTrue(e.actsDirectly)
    }

    /** Mixed household, cap exceeded: whatever survives, no lock is an actor. */
    @Test fun noLockActsWhateverTheMix() {
        val devices = listOf(
            dev("light.a", "Licht", "light"),
            dev("lock.haustuer", "Haustür", "lock"),
            dev("lock.keller", "Keller", "lock"),
            dev("switch.b", "Schalter", "switch"),
            dev("cover.tor", "Tor", "cover", "gate"),
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
        val entry = DeviceShortcuts.entries(listOf(dev("lock.haustuer", "Haustür", "lock")), 4)[0]
        val i = AppShortcuts.intentFor(ctx, entry)

        assertEquals(WidgetActionActivity::class.java.name, i.component?.className)
        assertTrue(i.getBooleanExtra(WidgetActionActivity.EXTRA_LOCK_CHOOSE, false))
        assertFalse(i.getBooleanExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, false))
        // No service rides along — the pick in the chooser names it.
        assertNull(i.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
        assertEquals("lock.haustuer", i.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
    }

    /** No entry, for any device, may name the latch. */
    @Test fun nothingPublishedCanReachLockOpen() {
        val ctx = RuntimeEnvironment.getApplication()
        val devices = listOf(
            dev("lock.haustuer", "Haustür", "lock"),
            dev("cover.garage", "Garage", "cover", "garage"),
            dev("light.a", "Licht", "light"),
            dev("lock.keller", "Keller", "lock"),
            dev("cover.tor", "Tor", "cover", "gate"),
        )
        for (e in DeviceShortcuts.entries(devices, 8)) {
            val i = AppShortcuts.intentFor(ctx, e)
            assertNull(i.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
            assertFalse(i.toUri(Intent.URI_INTENT_SCHEME).contains("lock.open"))
        }
    }

    /** A non-sensitive entry lands on the trampoline that asks for the device's toggle. */
    @Test fun aLampEntryAsksForTheOrdinaryToggle() {
        val ctx = RuntimeEnvironment.getApplication()
        val entry = DeviceShortcuts.entries(listOf(dev("light.flur", "Flur", "light")), 4)[0]
        val i = AppShortcuts.intentFor(ctx, entry)

        assertEquals(WidgetActionActivity::class.java.name, i.component?.className)
        assertTrue(i.getBooleanExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, false))
        assertFalse(i.getBooleanExtra(WidgetActionActivity.EXTRA_LOCK_CHOOSE, false))
    }

    /** A sensitive non-lock entry only opens a page: Homes' devices page, in the browser. */
    @Test fun aGarageEntryOnlyOpensThePage() {
        val ctx = RuntimeEnvironment.getApplication()
        cloud.dopp.homes.data.ServerStore.setBaseUrl(ctx, "https://homes.example")
        val entry = DeviceShortcuts.entries(listOf(dev("cover.garage", "Garage", "cover", "garage")), 4)[0]
        val i = AppShortcuts.intentFor(ctx, entry)

        assertEquals(android.content.Intent.ACTION_VIEW, i.action)
        assertEquals("https://homes.example/raeume", i.data?.toString())
        assertNull("no component of ours runs anything", i.component)
        assertFalse(i.getBooleanExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, false))
        cloud.dopp.homes.data.ServerStore.clear(ctx)
    }

    /** An entry identifies its device by **entity id and nothing else** (#100). */
    @Test fun anEntryCarriesTheEntity() {
        val ctx = RuntimeEnvironment.getApplication()
        val entry = DeviceShortcuts.entries(listOf(dev("light.keller", "Keller", "light")), 4)[0]
        val i = AppShortcuts.intentFor(ctx, entry)
        assertEquals("light.keller", i.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
        assertTrue(i.getBooleanExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, false))
    }

    /** Shortcut ids are stable per entity, so a refresh updates instead of churning. */
    @Test fun idsAreStablePerEntity() {
        val once = DeviceShortcuts.entries(lamps(3), 4).map { it.id }
        val again = DeviceShortcuts.entries(lamps(3), 4).map { it.id }
        assertEquals(once, again)
        assertTrue(once.all { it.startsWith(DeviceShortcuts.ID_PREFIX) })
    }
}
