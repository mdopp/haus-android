package cloud.dopp.haus

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.Persona
import cloud.dopp.haus.data.PersonaStore
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.widget.ShareTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The persona in the share sheet (#188): one sharing shortcut with the
 * persona's name, only while paired and Haus named one, and a share target
 * that hands it to the share activity. The app's name and the sheet's own
 * entry stay as they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareTargetTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        ServerStore.clear(ctx())
        TokenStore.clear(ctx())
        PersonaStore.clear(ctx())
    }

    private fun paired() {
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1")
        // Robolectric has no keystore: the token goes to plain prefs here.
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "haus_device_TESTTOKEN123")
    }

    @Test
    fun noPairingNoTarget() {
        PersonaStore.save(ctx(), Persona("Joshua", ""))
        assertNull(ShareTarget.info(ctx()))
    }

    @Test
    fun noPersonaNoTarget() {
        paired()
        assertNull(ShareTarget.info(ctx()))
    }

    @Test
    fun theTargetIsThePersona() {
        paired()
        PersonaStore.save(ctx(), Persona("Joshua", ""))
        val info = requireNotNull(ShareTarget.info(ctx()))
        assertEquals(ShareTarget.ID, info.id)
        assertEquals("Joshua", info.shortLabel.toString())
        assertEquals(setOf(ShareTarget.CATEGORY), info.categories)
        assertTrue("the share sheet only", info.isExcludedFromSurfaces(ShortcutInfoCompat.SURFACE_LAUNCHER))
    }

    @Test
    fun theNameFollowsThePersona() {
        paired()
        PersonaStore.save(ctx(), Persona("Joshua", ""))
        PersonaStore.save(ctx(), Persona("Mira", ""))
        assertEquals("Mira", ShareTarget.info(ctx())?.shortLabel.toString())
    }

    @Test
    fun theShareTargetHandsTextToTheShareActivity() {
        val xml = File(RepoFiles.main, "res/xml/shortcuts.xml").readText()
        assertTrue(xml, xml.contains("""android:targetClass="cloud.dopp.haus.ui.ShareActivity""""))
        assertTrue(xml, xml.contains("""android:mimeType="text/plain""""))
        assertTrue(xml, xml.contains("""<category android:name="${ShareTarget.CATEGORY}" />"""))
        assertTrue("no static shortcut", !xml.contains("<shortcut "))

        val manifest = File(RepoFiles.main, "AndroidManifest.xml").readText()
        val home = manifest.substringAfter("""android:name=".ui.HomeActivity"""").substringBefore("</activity>")
        assertTrue(home, home.contains("""android:name="android.app.shortcuts""""))
        assertTrue(home, home.contains("""android:resource="@xml/shortcuts""""))
    }

    @Test
    fun theAppAndTheSheetEntryKeepTheirNames() {
        assertEquals("Haus", ctx().getString(R.string.appName))
        assertEquals("In Haus merken", ctx().getString(R.string.share_label))
    }
}
