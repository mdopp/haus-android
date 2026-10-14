package cloud.dopp.haus

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.PairLinks
import cloud.dopp.haus.data.Persona
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.ui.HomeActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

/**
 * Pairing with Haus (#176, laib ADR 0022 §1): the person enters Haus's
 * address, the app opens /pair-device, the person confirms, Haus redirects to
 * cloud.dopp.haus://pair#token=haus_device_…, done. One set of names, and no
 * other: an old token or an old link pairs nothing.
 *
 * Nothing replaces the server or the token behind the person's back: a pair
 * link counts only while a pairing the app started is pending, a link or a
 * setup QR is stored only after a dialog that names the host, a token is
 * stored only after Haus answers `GET /napi/whoami` for it, and an address
 * over plain http is refused with a message. A token that cannot be saved is
 * said so, never toasted as a success.
 *
 * [Reach] finds the pairing's seams by name (red first): `Pairing.whoami`
 * answers in place of Haus, `TokenStore.backend` in place of the keystore,
 * which Robolectric does not have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()
    private val oldPrefix = "sol" + "_device_"
    private val oldScheme = "cloud.dopp." + "sol" + "aris"

    private val pairing = "cloud.dopp.haus.data.Pairing"
    private val tokens = "cloud.dopp.haus.data.TokenStore"
    private val link = Uri.parse("cloud.dopp.haus://pair#token=haus_device_TESTTOKEN123&id=abc")

    /** Who asked Haus about which token, and what Haus answers. */
    private val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile
    private var hausSays = 200

    @Before
    fun clean() {
        runCatching { Reach.set(tokens, "backend", ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)) }
        runCatching {
            Reach.set(pairing, "whoami", { server: String, token: String -> asked += "$server $token"; hausSays })
        }
        runCatching { Reach.call(pairing, "cancel", ctx()) }
        TokenStore.clear(ctx())
        ServerStore.clear(ctx())
    }

    private fun string(name: String): String {
        val id = ctx().resources.getIdentifier(name, "string", ctx().packageName)
        assertTrue("no string resource $name", id != 0)
        return ctx().getString(id)
    }

    /** Run the main looper until [done], for work the activity hands to a thread. */
    private fun settle(done: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
    }

    private fun home() = Robolectric.buildActivity(HomeActivity::class.java).setup()

    private fun connect(a: HomeActivity, address: String) {
        a.findViewById<android.widget.EditText>(R.id.server_url).setText(address)
        a.findViewById<android.view.View>(R.id.connect_btn).performClick()
    }

    private fun scan(a: HomeActivity, text: String) {
        HomeActivity::class.java.getDeclaredMethod("handleScanned", String::class.java)
            .apply { isAccessible = true }.invoke(a, text)
    }

    private fun dialog(): AlertDialog? = ShadowAlertDialog.getLatestAlertDialog()

    /** Say yes; the dialog delivers the click through the main looper. */
    private fun confirm() {
        val d = dialog()
        assertNotNull("no confirmation dialog", d)
        d!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
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

    /** The whole way: address, /pair-device, the redirect, the dialog, whoami, stored. */
    @Test
    fun theRedirectStoresTheTokenOnceConfirmedAndKnownToHaus() {
        val c = home()
        connect(c.get(), "haus.example")
        c.newIntent(Intent(Intent.ACTION_VIEW, link))
        confirm()
        settle { TokenStore.get(ctx()) != null }
        assertEquals(listOf("https://haus.example haus_device_TESTTOKEN123"), asked)
        assertEquals("haus_device_TESTTOKEN123", TokenStore.get(ctx()))
        assertEquals("https://haus.example", ServerStore.baseUrl(ctx()))
        assertEquals(string("pairing_success"), ShadowToast.getTextOfLatestToast())
    }

    /**
     * Once paired, the app asks — once, and saying why — to be exempt from
     * battery optimization: only then may Android's restart of the live
     * service after a killed process go foreground (Android 12+).
     */
    @Test
    fun afterPairingItAsksOnceForTheExemptionAndSaysWhy() {
        val why = "Damit die Widgets nach einem Neustart der App von selbst wieder live werden"
        val c = home()
        connect(c.get(), "haus.example")
        c.newIntent(Intent(Intent.ACTION_VIEW, link))
        confirm()
        settle { TokenStore.get(ctx()) != null && dialog()?.let { shadowOf(it).message.toString().contains(why) } == true }
        val d = dialog()
        assertNotNull(d)
        assertTrue("no explanation: ${shadowOf(d).message}", shadowOf(d).message.toString().contains(why))
        val activity = c.get()
        while (shadowOf(activity).nextStartedActivity != null) { /* the Custom Tab of the pairing */ }
        d!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val started = generateSequence { shadowOf(activity).nextStartedActivity }.toList()
        val asked = started.firstOrNull { it.action == android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS }
        assertNotNull("not asked: ${started.map { it.action }}", asked)
        assertEquals(Uri.parse("package:" + ctx().packageName), asked?.data)

        // pairing again does not ask again
        TokenStore.clear(ctx())
        val again = home()
        connect(again.get(), "haus.example")
        again.newIntent(Intent(Intent.ACTION_VIEW, link))
        confirm()
        settle { TokenStore.get(ctx()) != null }
        settle { false }
        assertTrue("asked twice", dialog()?.let { shadowOf(it).message.toString().contains(why) } != true || !dialog()!!.isShowing)
    }

    /** Any page or app can fire the pair link; without a pairing the app started, it pairs nothing. */
    @Test
    fun aPairLinkNobodyAskedForStoresNothing() {
        ServerStore.setBaseUrl(ctx(), "https://haus.example")
        Robolectric.buildActivity(HomeActivity::class.java, Intent(Intent.ACTION_VIEW, link)).setup()
        assertNull(TokenStore.get(ctx()))
        assertNull("no dialog for a link nobody asked for", dialog())
        assertTrue("Haus was asked", asked.isEmpty())
    }

    @Test
    fun aPairLinkAsksFirstAndNamesTheHost() {
        val c = home()
        connect(c.get(), "haus.example")
        c.newIntent(Intent(Intent.ACTION_VIEW, link))
        assertNull("stored before the person said yes", TokenStore.get(ctx()))
        val d = dialog()
        assertNotNull("no confirmation dialog", d)
        assertTrue(shadowOf(d).message.toString().contains("haus.example"))
    }

    @Test
    fun aDeclinedPairLinkStoresNothingAndEndsThePairing() {
        val c = home()
        connect(c.get(), "haus.example")
        c.newIntent(Intent(Intent.ACTION_VIEW, link))
        val d = dialog()
        assertNotNull("no confirmation dialog", d)
        d!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        settle { false }
        assertNull(TokenStore.get(ctx()))
        c.newIntent(Intent(Intent.ACTION_VIEW, link))
        assertTrue("a second link after no", dialog() == null || !dialog()!!.isShowing)
    }

    @Test
    fun aTokenHausDoesNotKnowIsNotStored() {
        hausSays = 401
        val c = home()
        connect(c.get(), "haus.example")
        c.newIntent(Intent(Intent.ACTION_VIEW, link))
        confirm()
        settle { ShadowToast.getLatestToast() != null }
        assertEquals(listOf("https://haus.example haus_device_TESTTOKEN123"), asked)
        assertNull(TokenStore.get(ctx()))
        assertEquals(string("pairing_refused"), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aTokenThatCannotBeSavedSaysSo() {
        Reach.set(tokens, "backend", Unwritable())
        val c = home()
        connect(c.get(), "haus.example")
        c.newIntent(Intent(Intent.ACTION_VIEW, link))
        confirm()
        settle { ShadowToast.getLatestToast() != null }
        assertNull(TokenStore.get(ctx()))
        assertEquals(string("pairing_not_saved"), ShadowToast.getTextOfLatestToast())
        assertFalse(ShadowToast.showedToast(string("pairing_success")))
    }

    @Test
    fun theSetupQrAsksAndNamesTheHostBeforeItSwitches() {
        ServerStore.setBaseUrl(ctx(), "https://haus.example")
        val a = home().get()
        scan(a, "cloud.dopp.haus://setup?server=https%3A%2F%2Fother.example&token=haus_device_T1")
        assertEquals("switched before the person said yes", "https://haus.example", ServerStore.baseUrl(ctx()))
        assertNull(TokenStore.get(ctx()))
        val d = dialog()
        assertNotNull("no confirmation dialog", d)
        assertTrue(shadowOf(d).message.toString().contains("other.example"))
        confirm()
        settle { TokenStore.get(ctx()) != null }
        assertEquals(listOf("https://other.example haus_device_T1"), asked)
        assertEquals("https://other.example", ServerStore.baseUrl(ctx()))
        assertEquals("haus_device_T1", TokenStore.get(ctx()))
    }

    @Test
    fun aScannedAddressAsksBeforeItOpensPairing() {
        val a = home().get()
        scan(a, "https://other.example")
        assertNull(ServerStore.baseUrl(ctx()))
        assertNull("opened before the person said yes", shadowOf(a).peekNextStartedActivity())
        val d = dialog()
        assertNotNull("no confirmation dialog", d)
        assertTrue(shadowOf(d).message.toString().contains("other.example"))
        confirm()
        assertEquals("https://other.example/pair-device", shadowOf(a).nextStartedActivity?.data?.toString())
    }

    @Test
    fun plainHttpIsRefusedWithAMessage() {
        ServerStore.setBaseUrl(ctx(), "http://haus.example")
        assertNull("ServerStore kept an http address", ServerStore.baseUrl(ctx()))

        val a = home().get()
        connect(a, "http://haus.example")
        assertNull(ServerStore.baseUrl(ctx()))
        assertNull(shadowOf(a).peekNextStartedActivity())
        assertEquals(string("server_http_refused"), ShadowToast.getTextOfLatestToast())

        ShadowToast.reset()
        scan(a, "cloud.dopp.haus://setup?server=http%3A%2F%2Fhaus.example&token=haus_device_T1")
        assertNull(ServerStore.baseUrl(ctx()))
        assertNull(TokenStore.get(ctx()))
        assertNull("a dialog for an http address", dialog())
        assertEquals(string("server_http_refused"), ShadowToast.getTextOfLatestToast())

        ShadowToast.reset()
        scan(a, "http://haus.example")
        assertNull(ServerStore.baseUrl(ctx()))
        assertEquals(string("server_http_refused"), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun anOldRedirectStoresNothing() {
        val uri = Uri.parse("cloud.dopp.haus://pair#token=${oldPrefix}TESTTOKEN123&id=abc")
        Robolectric.buildActivity(HomeActivity::class.java, Intent(Intent.ACTION_VIEW, uri)).setup()
        assertNull(TokenStore.get(ctx()))
        // nor while a pairing is pending
        val c = home()
        connect(c.get(), "haus.example")
        c.newIntent(Intent(Intent.ACTION_VIEW, uri))
        assertNull(TokenStore.get(ctx()))
        assertNull(dialog())
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
                """{"name":"joshua","display":"Joshua","greeting":"Hallo.","accent":"#3ddc84","ground":"#0c120e","icon":"/napi/persona/icon.png"}""",
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

    /** A store the keystore refused: every write fails, nothing is read back. */
    private class Unwritable : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = false
        override fun edit(): SharedPreferences.Editor = Refusing()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Refusing : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = this
            override fun clear() = this
            override fun commit() = false
            override fun apply() {}
        }
    }
}
