package cloud.dopp.homes

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutManagerCompat
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.einrichtung.EinrichtungStore
import cloud.dopp.homes.einrichtung.Kurz
import cloud.dopp.homes.einrichtung.Verknuepfung
import cloud.dopp.homes.einrichtung.VerknuepfungActivity
import cloud.dopp.homes.einrichtung.Verknuepfungen
import cloud.dopp.homes.ui.StartDevices
import cloud.dopp.homes.widget.AppShortcuts
import cloud.dopp.homes.widget.ShortcutDevice
import cloud.dopp.homes.widget.WidgetActionActivity
import cloud.dopp.homes.widget.WidgetActionReceiver
import cloud.dopp.homes.widget.WidgetStore
import org.junit.After
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
 * The app-icon long-press menu (owner, 2026-11-04): exactly Garagentor,
 * Einkaufsliste, Zeitleiste and Dokument scannen, in this order, each only
 * where the box has it. The garage's entry taps the garage as its tile on the
 * start screen does — Homes' 403 puts the confirm dialog before an open — and
 * carries no service, so nothing published can reach a lock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppShortcutsTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private val alle = mapOf(
        "einkauf" to "https://homes.example/einkauf",
        "zeitleiste" to "https://reitti.example",
        "scannen" to "https://homes.example/napi/upload",
    )

    private val garage = ShortcutDevice("cover.garage", "Garagentor Garage", "cover", "garage")

    @After
    fun weg() {
        ServerStore.clear(ctx)
        TokenStore.clear(ctx)
        EinrichtungStore.clear(ctx)
        StartDevices.clear(ctx)
    }

    // --- which, and in what order ----------------------------------------------

    @Test fun theFourInTheirOrder() {
        val l = Verknuepfungen.liste(alle, garage)
        assertEquals(listOf(Kurz.GARAGE, Kurz.EINKAUF, Kurz.ZEITLEISTE, Kurz.SCANNEN), l.map { it.art })
        assertEquals("cover.garage", l[0].ziel)
        assertEquals("https://reitti.example", l[2].ziel)
    }

    @Test fun noGarageNoGarageShortcut() {
        val l = Verknuepfungen.liste(alle, null)
        assertEquals(listOf(Kurz.EINKAUF, Kurz.ZEITLEISTE, Kurz.SCANNEN), l.map { it.art })
    }

    @Test fun aMissingTargetHidesItsShortcut() {
        assertEquals(listOf(Kurz.GARAGE, Kurz.SCANNEN), Verknuepfungen.liste(mapOf("scannen" to "https://h.example/x"), garage).map { it.art })
        assertTrue("an older Homes and no garage: nothing", Verknuepfungen.liste(emptyMap(), null).isEmpty())
    }

    @Test fun theGarageIsACoverDrawnAsAGarage() {
        val devices = listOf(
            ShortcutDevice("light.garage", "Garagenlicht", "light"),
            ShortcutDevice("cover.wohnzimmer", "Rollo", "cover", "shutter"),
            ShortcutDevice("cover.haustuer", "Tür", "cover", "door"),
            ShortcutDevice("cover.tor", "Hoftor", "cover", "gate"),
            garage,
        )
        assertEquals("cover.tor", Verknuepfungen.garage(devices)?.entityId)
        assertNull(Verknuepfungen.garage(devices.take(3)))
        // the domain is the id's own: a lock named as a garage cover is none
        assertNull(Verknuepfungen.garage(listOf(ShortcutDevice("lock.tor", "Tor", "cover", "garage"))))
    }

    // --- what the intents carry ------------------------------------------------

    @Test fun theGarageEntryTapsTheGarageAndCarriesNoService() {
        val i = AppShortcuts.intentFor(ctx, Verknuepfung(Kurz.GARAGE, "cover.garage", "Garagentor Garage"))
        assertEquals(WidgetActionActivity::class.java.name, i.component?.className)
        assertTrue(i.getBooleanExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, false))
        assertFalse(i.getBooleanExtra(WidgetActionActivity.EXTRA_LOCK_CHOOSE, false))
        assertEquals("cover.garage", i.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
        assertEquals("Garagentor Garage", i.getStringExtra(WidgetActionActivity.EXTRA_NAME))
        assertNull(i.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
        assertFalse(i.toUri(Intent.URI_INTENT_SCHEME).contains("lock.open"))
    }

    @Test fun theListAndTheTimelineOpenTheirAddress() {
        for (k in listOf(Kurz.EINKAUF, Kurz.ZEITLEISTE)) {
            val i = AppShortcuts.intentFor(ctx, Verknuepfung(k, alle.getValue(k.id)))
            assertEquals(VerknuepfungActivity::class.java.name, i.component?.className)
            assertEquals(Intent.ACTION_VIEW, i.action)
            assertEquals(alle.getValue(k.id), i.dataString)
        }
    }

    @Test fun scanningStartsTheCamerasWay() {
        val i = AppShortcuts.intentFor(ctx, Verknuepfung(Kurz.SCANNEN, alle.getValue("scannen")))
        assertEquals(VerknuepfungActivity::class.java.name, i.component?.className)
        assertEquals(VerknuepfungActivity.SCANNEN, i.action)
        assertNull("the upload is the app's own call, not an address", i.data)
    }

    // --- what is published -----------------------------------------------------

    private fun koppeln(einrichtung: String?) {
        ServerStore.setBaseUrl(ctx, "https://homes.example")
        TokenStore.backend = ctx.getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx, "homes_device_TESTTOKEN123")
        einrichtung?.let { EinrichtungStore.neu(ctx, it) }
    }

    private fun published(): List<String> =
        ShortcutManagerCompat.getDynamicShortcuts(ctx).sortedBy { it.rank }.map { it.id }.filter { it.startsWith(Verknuepfungen.PREFIX) }

    @Test fun publishedFromHomesAnswerAndTheFavourites() {
        koppeln(EinrichtungTest.ANTWORT)
        StartDevices.save(ctx, """{"ok":true,"favorites":[{"entity_id":"cover.garage","name":"Garagentor Garage","domain":"cover","device_class":"garage","state":"closed"}]}""")
        AppShortcuts.refresh(ctx)
        assertEquals(listOf("kurz:garage", "kurz:einkauf", "kurz:zeitleiste", "kurz:scannen"), published())
    }

    @Test fun anOlderHomesWithoutAGarageLeavesTheMenuEmpty() {
        koppeln(null)
        WidgetStore.setShortcutCatalog(ctx, listOf(ShortcutDevice("light.flur", "Flur", "light")))
        AppShortcuts.refresh(ctx)
        assertTrue(published().isEmpty())
    }

    @Test fun unpairedNothingIsPublished() {
        WidgetStore.setShortcutCatalog(ctx, listOf(garage))
        AppShortcuts.refresh(ctx)
        assertTrue(published().isEmpty())
    }
}
