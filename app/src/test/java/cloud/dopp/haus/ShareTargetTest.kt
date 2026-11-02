package cloud.dopp.haus

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.Assistent
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.widget.ShareTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The assistant in the share sheet (#188): one sharing shortcut with its
 * name („Homes“ until Haus names one), only while paired, and a share target
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
        Assistent.clear(ctx())
    }

    private fun paired() {
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1")
        // Robolectric has no keystore: the token goes to plain prefs here.
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "haus_device_TESTTOKEN123")
    }

    @Test
    fun noPairingNoTarget() {
        Assistent.save(ctx(), "Mira")
        assertNull(ShareTarget.info(ctx()))
    }

    @Test
    fun pairedTheTargetIsHomesUntilHausNamesOne() {
        paired()
        val info = requireNotNull(ShareTarget.info(ctx()))
        assertEquals(ShareTarget.ID, info.id)
        assertEquals("share_assistent", info.id)
        assertEquals("Homes", info.shortLabel.toString())
        assertEquals(setOf(ShareTarget.CATEGORY), info.categories)
        // Android 13+ keeps no shortcut excluded from the launcher, so the share sheet never saw it
        assertFalse("kept out of the launcher", info.isExcludedFromSurfaces(ShortcutInfoCompat.SURFACE_LAUNCHER))
    }

    @Test
    fun theNameFollowsHaus() {
        paired()
        Assistent.save(ctx(), "Mira")
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
