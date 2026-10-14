package cloud.dopp.solaris

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.solaris.data.PairLinks
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #174 (laib ADR 0021): the app learns the neutral names next to the old ones.
 * Haus may hand out `haus_device_…` tokens and `cloud.dopp.haus://pair` / `cloud.dopp.haus://setup`
 * links; every phone that still talks to a server speaking `sol_device_…` and
 * `cloud.dopp.solaris://` must keep pairing exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairLinksTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        TokenStore.clear(ctx())
    }

    @Test
    fun bothTokenPrefixesAreDeviceTokens() {
        assertTrue(PairLinks.isDeviceToken("sol_device_TESTTOKEN123"))
        assertTrue(PairLinks.isDeviceToken("haus_device_TESTTOKEN123"))
    }

    @Test
    fun anythingElseIsNotADeviceToken() {
        assertFalse(PairLinks.isDeviceToken(null))
        assertFalse(PairLinks.isDeviceToken(""))
        assertFalse("a bare prefix carries no token", PairLinks.isDeviceToken("haus_device_"))
        assertFalse("a bare prefix carries no token", PairLinks.isDeviceToken("sol_device_"))
        assertFalse(PairLinks.isDeviceToken("device_TESTTOKEN123"))
        assertFalse(PairLinks.isDeviceToken("haus_TESTTOKEN123"))
        assertFalse(PairLinks.isDeviceToken("sb_TESTTOKEN123"))
        assertFalse("the prefix is a prefix", PairLinks.isDeviceToken("x_haus_device_TESTTOKEN123"))
    }

    @Test
    fun legacyPairLinkStillPairs() {
        val uri = Uri.parse("cloud.dopp.solaris://pair#token=sol_device_TESTTOKEN123&id=abc")
        assertTrue(PairLinks.isPairLink(uri))
        assertEquals("sol_device_TESTTOKEN123", PairLinks.pairToken(uri))
    }

    @Test
    fun neutralPairLinkPairs() {
        val uri = Uri.parse("cloud.dopp.haus://pair#token=haus_device_TESTTOKEN123&id=abc")
        assertTrue(PairLinks.isPairLink(uri))
        assertEquals("haus_device_TESTTOKEN123", PairLinks.pairToken(uri))
    }

    /** During the switch a server may mix the two: any scheme carries any prefix. */
    @Test
    fun schemeAndPrefixAreIndependent() {
        assertEquals(
            "sol_device_TESTTOKEN123",
            PairLinks.pairToken(Uri.parse("cloud.dopp.haus://pair#token=sol_device_TESTTOKEN123&id=abc")),
        )
        assertEquals(
            "haus_device_TESTTOKEN123",
            PairLinks.pairToken(Uri.parse("cloud.dopp.solaris://pair#token=haus_device_TESTTOKEN123&id=abc")),
        )
    }

    @Test
    fun pairTokenAlsoReadsTheQueryAndDecodes() {
        assertEquals(
            "haus_device_TEST+TOKEN",
            PairLinks.pairToken(Uri.parse("cloud.dopp.haus://pair?id=abc&token=haus_device_TEST%2BTOKEN")),
        )
    }

    @Test
    fun notAPairLink() {
        assertFalse(PairLinks.isPairLink(Uri.parse("cloud.dopp.haus://setup?server=https://haus.example")))
        assertFalse(PairLinks.isPairLink(Uri.parse("https://haus.example/pair#token=haus_device_X")))
        assertFalse(PairLinks.isPairLink(Uri.parse("solaris://pair#token=haus_device_X")))
        assertNull(PairLinks.pairToken(Uri.parse("https://haus.example/pair#token=haus_device_X")))
    }

    @Test
    fun pairLinkWithoutAValidTokenYieldsNoToken() {
        assertNull(PairLinks.pairToken(Uri.parse("cloud.dopp.haus://pair#id=abc")))
        assertNull(PairLinks.pairToken(Uri.parse("cloud.dopp.haus://pair#token=&id=abc")))
        assertNull(PairLinks.pairToken(Uri.parse("cloud.dopp.haus://pair#token=sb_TESTTOKEN&id=abc")))
    }

    @Test
    fun legacySetupLinkStillParses() {
        val s = PairLinks.parseSetup(
            "cloud.dopp.solaris://setup?server=https%3A%2F%2Fchat.example&token=sol_device_TESTTOKEN123",
        )
        assertEquals("https://chat.example", s?.server)
        assertEquals("sol_device_TESTTOKEN123", s?.token)
    }

    @Test
    fun neutralSetupLinkParses() {
        val s = PairLinks.parseSetup(
            "cloud.dopp.haus://setup?server=https%3A%2F%2Fhaus.example&token=haus_device_TESTTOKEN123",
        )
        assertEquals("https://haus.example", s?.server)
        assertEquals("haus_device_TESTTOKEN123", s?.token)
    }

    /** No token, or one the app doesn't recognise: connect, then log in to pair. */
    @Test
    fun setupLinkWithoutAValidTokenStillNamesTheServer() {
        val bare = PairLinks.parseSetup("cloud.dopp.haus://setup?server=https%3A%2F%2Fhaus.example")
        assertEquals("https://haus.example", bare?.server)
        assertNull(bare?.token)
        val foreign = PairLinks.parseSetup("cloud.dopp.haus://setup?server=https%3A%2F%2Fhaus.example&token=sb_X")
        assertEquals("https://haus.example", foreign?.server)
        assertNull(foreign?.token)
    }

    @Test
    fun notASetupLink() {
        assertNull(PairLinks.parseSetup("cloud.dopp.haus://setup?token=haus_device_X"))
        assertNull(PairLinks.parseSetup("cloud.dopp.haus://pair?server=https%3A%2F%2Fhaus.example"))
        assertNull(PairLinks.parseSetup("https://haus.example"))
        assertNull(PairLinks.parseSetup("hello"))
        assertNull(PairLinks.parseSetup(""))
    }

    /** The neutral pair link reaches the hub and stores its token, like the old one. */
    @Test
    fun neutralPairDeepLinkStoresTheToken() {
        val uri = Uri.parse("cloud.dopp.haus://pair#token=haus_device_TESTTOKEN123&id=abc")
        Robolectric.buildActivity(OnboardingHomeActivity::class.java, Intent(Intent.ACTION_VIEW, uri)).setup()
        assertEquals("haus_device_TESTTOKEN123", TokenStore.get(ctx()))
    }

    @Test
    fun legacyPairDeepLinkStillStoresTheToken() {
        val uri = Uri.parse("cloud.dopp.solaris://pair#token=sol_device_TESTTOKEN123&id=abc")
        Robolectric.buildActivity(OnboardingHomeActivity::class.java, Intent(Intent.ACTION_VIEW, uri)).setup()
        assertEquals("sol_device_TESTTOKEN123", TokenStore.get(ctx()))
    }

    /** The manifest routes both pair schemes to the hub; the browser hands them over. */
    @Test
    fun bothPairSchemesResolveToTheHub() {
        for (link in listOf("cloud.dopp.solaris://pair#token=x", "cloud.dopp.haus://pair#token=x")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(ctx().packageName)
            val hits = ctx().packageManager.queryIntentActivities(intent, 0)
            assertEquals(
                link,
                listOf(OnboardingHomeActivity::class.java.name),
                hits.map { it.activityInfo.name },
            )
        }
    }
}
