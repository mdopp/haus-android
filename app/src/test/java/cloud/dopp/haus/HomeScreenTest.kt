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
import org.junit.Assert.assertNull
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
        assertEquals(ctx().getString(R.string.home_claim), a.findViewById<TextView>(R.id.persona_greeting).text.toString())
        // no icon announced → none drawn
        assertEquals(View.GONE, a.findViewById<View>(R.id.persona_icon).visibility)
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
