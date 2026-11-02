package cloud.dopp.haus

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.Persona
import cloud.dopp.haus.data.PersonaStore
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.ui.HomeActivity
import cloud.dopp.haus.ui.StartDevices
import cloud.dopp.haus.widget.WidgetActionActivity
import cloud.dopp.haus.widget.WidgetActionReceiver
import cloud.dopp.haus.widget.WidgetTypes
import org.junit.Assert.assertEquals
import org.json.JSONObject
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
 * The app's one screen (#176): unpaired it asks for Haus's address; paired it
 * shows the persona, the person's favorite devices first (owner, 2026-11-02),
 * and offers every widget — the house's first, the Box's apart, for whoever
 * looks after it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        ServerStore.clear(ctx())
        TokenStore.clear(ctx())
        PersonaStore.clear(ctx())
        StartDevices.clear(ctx())
    }

    private fun paired() {
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1")
        // Robolectric has no keystore: the token goes to plain prefs here.
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "haus_device_TESTTOKEN123")
    }

    private fun open(): HomeActivity = Robolectric.buildActivity(HomeActivity::class.java).setup().get()

    @Test
    fun unpairedItAsksForTheAddress() {
        val a = open()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.connect_section).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.connected_section).visibility)
        assertEquals("Haus", a.findViewById<TextView>(R.id.persona_name).text.toString())
    }

    @Test
    fun pairedItOffersEveryWidgetAndTheBoxApart() {
        paired()
        val a = open()
        assertEquals(View.GONE, a.findViewById<View>(R.id.connect_section).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.connected_section).visibility)
        assertEquals(
            WidgetTypes.ALL.count { !it.admin },
            a.findViewById<ViewGroup>(R.id.widgets_group).childCount,
        )
        assertEquals(2, a.findViewById<ViewGroup>(R.id.box_group).childCount)
    }

    @Test
    fun theScreenWearsThePersona() {
        paired()
        PersonaStore.save(ctx(), Persona("Joshua", "#3ddc84", "#0c120e"))
        val a = open()
        assertEquals("Joshua", a.findViewById<TextView>(R.id.persona_name).text.toString())
        // the app's line, never the persona's greeting (#184: "Wollen wir ein Spiel spielen?" is the chat's)
        assertEquals(ctx().getString(R.string.home_claim), a.findViewById<TextView>(R.id.home_claim).text.toString())
        // no icon announced → none drawn
        assertEquals(View.GONE, a.findViewById<View>(R.id.persona_icon).visibility)
    }

    /**
     * The acceptance of #184: Haus sends the greeting, the app reads it nowhere,
     * so it is on no text of the screen and no source names its key (the store
     * only drops what older versions kept).
     */
    @Test
    fun theGreetingIsShownNowhere() {
        paired()
        val greeting = "Wollen wir ein Spiel spielen?"
        val sent = Persona.parse(
            JSONObject().put("display", "Joshua").put("greeting", "Hallo, hier ist Joshua. $greeting").put("accent", "#3ddc84"),
        )
        PersonaStore.save(ctx(), requireNotNull(sent))
        val a = open()
        val texts = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) texts.add(v.text.toString())
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(a.window.decorView)
        assertTrue("the greeting is on the screen: $texts", texts.none { it.contains(greeting) || it.contains("Hallo, hier ist") })

        val readers = RepoFiles.mainKotlin().filter { f ->
            f.readLines().any { it.contains("\"greeting\"") && !it.contains(".remove(\"greeting\")") }
        }
        assertTrue("sources that read the greeting: $readers", readers.isEmpty())
    }

    @Test
    fun unpairingForgetsHausAndThePersona() {
        paired()
        PersonaStore.save(ctx(), Persona("Joshua", "#3ddc84"))
        val a = open()
        a.findViewById<View>(R.id.logout_btn).performClick()
        assertNull(TokenStore.get(ctx()))
        assertNull(ServerStore.baseUrl(ctx()))
        assertNull(PersonaStore.get(ctx()))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.connect_section).visibility)
    }

    // --- the devices: favorites first (owner, 2026-11-02) ------------------------

    /** Haus's answer to `/napi/portal/favorites`, the person's ☆ things in their order. */
    private val favorites = """{"ok":true,"favorites":[
        {"entity_id":"light.kueche","name":"Küche","domain":"light","state":"on","brightness":153,"page":"/raeume/light.kueche"},
        {"entity_id":"lock.haustuer","name":"Haustür","domain":"lock","state":"locked","supported_features":1},
        {"entity_id":"sensor.bad","name":"Bad","domain":"sensor","state":"21.5","unit":"°C","page":"/raeume/sensor.bad"}]}"""

    private fun texts(v: View): List<String> =
        if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else listOfNotNull((v as? TextView)?.takeIf { it.visibility == View.VISIBLE }?.text?.toString())

    private fun deviceRows(a: HomeActivity): List<View> =
        a.findViewById<ViewGroup>(R.id.devices_group).let { g -> (0 until g.childCount).map { g.getChildAt(it) } }

    @Test
    fun theFavoritesComeFirstThenAlleGeraete() {
        paired()
        StartDevices.save(ctx(), favorites)
        val a = open()
        val section = a.findViewById<ViewGroup>(R.id.connected_section)
        val group = a.findViewById<View>(R.id.devices_group)
        val order = (0 until section.childCount).map { section.getChildAt(it) }
        assertTrue("the devices come before the widgets", order.indexOf(group) < order.indexOf(a.findViewById(R.id.widgets_group)))
        assertTrue("the devices come before the live switch", order.indexOf(group) < order.indexOf(a.findViewById(R.id.live_switch)))
        val rows = deviceRows(a).map { texts(it) }
        assertEquals(
            listOf(listOf("Küche", "an · 60 %"), listOf("Haustür", "abgeschlossen"), listOf("Bad", "21,5 °C"), listOf("Alle Geräte")),
            rows,
        )
    }

    /** A lamp's tap switches it, the way the device widget's did: through the device receiver. */
    @Test
    fun aTapSwitchesTheFavorite() {
        paired()
        StartDevices.save(ctx(), favorites)
        val a = open()
        shadowOf(a.application).clearBroadcastIntents()
        deviceRows(a)[0].performClick()
        val tap = shadowOf(a.application).broadcastIntents.single { it.action == WidgetActionReceiver.ACTION_TAP }
        assertEquals("light.kueche", tap.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
        assertEquals(WidgetActionReceiver.OP_TOGGLE, tap.getStringExtra(WidgetActionReceiver.EXTRA_OP))
        assertEquals("the tap answers at once", ctx().getString(R.string.home_device_switching), texts(deviceRows(a)[0])[1])
    }

    /** A lock never switches on a tap: it opens its chooser, with the latch only when the card says it has one (#92). */
    @Test
    fun aLockOpensItsChooser() {
        paired()
        StartDevices.save(ctx(), favorites)
        val a = open()
        shadowOf(a.application).clearBroadcastIntents()
        deviceRows(a)[1].performClick()
        val tap = shadowOf(a.application).broadcastIntents.single { it.action == WidgetActionReceiver.ACTION_TAP }
        assertEquals(WidgetActionReceiver.OP_LOCK_CHOOSE, tap.getStringExtra(WidgetActionReceiver.EXTRA_OP))
        assertEquals(1, tap.getIntExtra(WidgetActionActivity.EXTRA_FEATURES, -1))
    }

    /** A sensor cannot be switched: its tap opens its page on Haus; „Alle Geräte“ opens the devices page. */
    @Test
    fun theRestOpensHaus() {
        paired()
        StartDevices.save(ctx(), favorites)
        val a = open()
        deviceRows(a)[2].performClick()
        assertEquals("https://127.0.0.1:1/raeume/sensor.bad", shadowOf(a).nextStartedActivity?.dataString)
        deviceRows(a).last().performClick()
        assertEquals("https://127.0.0.1:1/raeume", shadowOf(a).nextStartedActivity?.dataString)
    }

    /** No favorites yet: one sentence how to get some, and „Alle Geräte“ all the same. */
    @Test
    fun noFavoritesSaysHow() {
        paired()
        StartDevices.save(ctx(), """{"ok":true,"favorites":[]}""")
        val rows = deviceRows(open()).map { texts(it) }
        assertEquals(listOf(listOf(ctx().getString(R.string.home_devices_none)), listOf("Alle Geräte")), rows)
    }

    /** Unpairing forgets the favorites with the rest. */
    @Test
    fun unpairingForgetsTheFavorites() {
        paired()
        StartDevices.save(ctx(), favorites)
        open().findViewById<View>(R.id.logout_btn).performClick()
        assertNull(StartDevices.load(ctx()))
    }
}
