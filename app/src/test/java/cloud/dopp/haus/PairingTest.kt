package cloud.dopp.haus

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.PairLinks
import cloud.dopp.haus.data.Persona
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.ui.HomeActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pairing with Haus (#176, laib ADR 0022 §1): the person enters Haus's
 * address, the app opens /pair-device, the person confirms, Haus redirects to
 * cloud.dopp.haus://pair#token=haus_device_…, done. One set of names, and no
 * other: an old token or an old link pairs nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()
    private val oldPrefix = "sol" + "_device_"
    private val oldScheme = "cloud.dopp." + "sol" + "aris"

    @Before
    fun clean() {
        TokenStore.clear(ctx())
        ServerStore.clear(ctx())
    }

    @Test
    fun theNamesAreHauss() {
        assertEquals("cloud.dopp.haus", HausConfig.SCHEME)
        assertEquals("haus_device_", HausConfig.TOKEN_PREFIX)
        assertEquals("/pair-device", HausConfig.PAIR_PATH)
        assertEquals("cloud.dopp.haus", ctx().packageName)
    }

    @Test
    fun onlyAHausTokenIsADeviceToken() {
        assertTrue(PairLinks.isDeviceToken("haus_device_TESTTOKEN123"))
        assertFalse(PairLinks.isDeviceToken(oldPrefix + "TESTTOKEN123"))
        assertFalse(PairLinks.isDeviceToken("haus_device_"))
        assertFalse(PairLinks.isDeviceToken("x_haus_device_TESTTOKEN123"))
        assertFalse(PairLinks.isDeviceToken(null))
    }

    @Test
    fun hausPairLinkCarriesTheToken() {
        val uri = Uri.parse("cloud.dopp.haus://pair#token=haus_device_TESTTOKEN123&id=abc")
        assertTrue(PairLinks.isPairLink(uri))
        assertEquals("haus_device_TESTTOKEN123", PairLinks.pairToken(uri))
        assertEquals(
            "haus_device_TEST+TOKEN",
            PairLinks.pairToken(Uri.parse("cloud.dopp.haus://pair?id=abc&token=haus_device_TEST%2BTOKEN")),
        )
    }

    @Test
    fun anOldLinkOrAnOldTokenPairsNothing() {
        assertFalse(PairLinks.isPairLink(Uri.parse("$oldScheme://pair#token=haus_device_X")))
        assertNull(PairLinks.pairToken(Uri.parse("$oldScheme://pair#token=haus_device_X")))
        assertNull(PairLinks.pairToken(Uri.parse("cloud.dopp.haus://pair#token=${oldPrefix}X")))
        assertNull(PairLinks.parseSetup("$oldScheme://setup?server=https%3A%2F%2Fhaus.example"))
        assertNull(PairLinks.pairToken(Uri.parse("haus://pair#token=haus_device_X")))
    }

    @Test
    fun theSetupQrNamesHausAndMayCarryAToken() {
        val s = PairLinks.parseSetup("cloud.dopp.haus://setup?server=https%3A%2F%2Fhaus.example&token=haus_device_T1")
        assertEquals("https://haus.example", s?.server)
        assertEquals("haus_device_T1", s?.token)
        assertNull(PairLinks.parseSetup("cloud.dopp.haus://setup?server=https%3A%2F%2Fhaus.example&token=${oldPrefix}T")?.token)
        assertNull(PairLinks.parseSetup("cloud.dopp.haus://setup?token=haus_device_X"))
    }

    @Test
    fun enteringTheAddressOpensPairDevice() {
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        a.findViewById<android.widget.EditText>(R.id.server_url).setText("haus.example")
        a.findViewById<android.view.View>(R.id.connect_btn).performClick()
        assertEquals("https://haus.example", ServerStore.baseUrl(ctx()))
        val opened = shadowOf(a).nextStartedActivity
        assertEquals("https://haus.example/pair-device", opened?.data?.toString())
    }

    @Test
    fun theRedirectStoresTheToken() {
        ServerStore.setBaseUrl(ctx(), "https://haus.example")
        val uri = Uri.parse("cloud.dopp.haus://pair#token=haus_device_TESTTOKEN123&id=abc")
        Robolectric.buildActivity(HomeActivity::class.java, Intent(Intent.ACTION_VIEW, uri)).setup()
        assertEquals("haus_device_TESTTOKEN123", TokenStore.get(ctx()))
    }

    @Test
    fun anOldRedirectStoresNothing() {
        val uri = Uri.parse("cloud.dopp.haus://pair#token=${oldPrefix}TESTTOKEN123&id=abc")
        Robolectric.buildActivity(HomeActivity::class.java, Intent(Intent.ACTION_VIEW, uri)).setup()
        assertNull(TokenStore.get(ctx()))
    }

    @Test
    fun thePairLinkResolvesToTheHomeScreen() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("cloud.dopp.haus://pair#token=x"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(ctx().packageName)
        val hits = ctx().packageManager.queryIntentActivities(intent, 0)
        assertEquals(listOf(HomeActivity::class.java.name), hits.map { it.activityInfo.name })
    }

    /** The persona comes from /napi/persona: name, colours, icon. */
    @Test
    fun thePersonaIsNameColoursAndIcon() {
        val p = Persona.parse(
            JSONObject(
                """{"name":"joshua","display":"Joshua","greeting":"Hallo.","accent":"#3ddc84","ground":"#0c120e","icon":"/napi/persona/icon.svg"}""",
            ),
        )
        assertEquals("Joshua", p?.display)
        assertEquals("#3ddc84", p?.accent)
        assertEquals("#0c120e", p?.ground)
        assertTrue(p?.hasIcon == true)
        val plain = Persona.parse(JSONObject("""{"display":"Haus","accent":"#fff","ground":"#000","icon":""}"""))
        assertFalse(plain?.hasIcon == true)
        assertNull(Persona.parse(JSONObject("""{"display":""}""")))
    }
}
