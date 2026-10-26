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
import org.robolectric.annotation.Config

/**
 * The app's one screen (#176): unpaired it asks for Haus's address; paired it
 * shows the persona and offers every widget — the house's first, the Box's
 * apart, for whoever looks after it.
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
}
