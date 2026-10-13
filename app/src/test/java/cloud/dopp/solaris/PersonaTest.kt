package cloud.dopp.solaris

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.solaris.data.Persona
import cloud.dopp.solaris.data.PersonaStore
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * mdopp/laib#130: the house has a persona (laib ADR 0020), and the app shows
 * it. Haus answers `GET /napi/persona` with the name, greeting and colours; the
 * app keeps the last answer and puts the name where the wordmark is. A server
 * without a persona (Solaris today) answers 404, and the app stays as it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonaTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        ServerStore.clear(ctx())
        TokenStore.clear(ctx())
        PersonaStore.clear(ctx())
    }

    @Test
    fun hausAnswerIsAPersona() {
        val p = Persona.parse(JSONObject("""{"name":"joshua","display":"Joshua","greeting":"Hallo, hier ist Joshua.","accent":"#3ddc84","ground":"#0c120e","icon":"/napi/persona/icon.svg"}"""))
        assertEquals("Joshua", p?.display)
        assertEquals("Hallo, hier ist Joshua.", p?.greeting)
        assertEquals("#3ddc84", p?.accent)
        assertNull("no display, no persona", Persona.parse(JSONObject("""{"display":""}""")))
    }

    @Test
    fun theStoreKeepsTheLastAnswerUntilLogout() {
        PersonaStore.save(ctx(), Persona("Joshua", "Hallo.", "#3ddc84"))
        assertEquals("Joshua", PersonaStore.get(ctx())?.display)
        PersonaStore.clear(ctx())
        assertNull(PersonaStore.get(ctx()))
    }

    @Test
    fun theHubShowsThePersonasName() {
        ServerStore.setBaseUrl(ctx(), "https://haus.example")
        TokenStore.save(ctx(), "sol_device_TESTTOKEN123")
        PersonaStore.save(ctx(), Persona("Joshua", "Hallo, hier ist Joshua.", "#3ddc84"))
        val a = Robolectric.buildActivity(OnboardingHomeActivity::class.java).setup().get()
        assertEquals("Joshua", a.findViewById<TextView>(R.id.brand_word_start).text.toString())
        assertEquals(View.GONE, a.findViewById<View>(R.id.brand_figure).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.brand_word_end).visibility)
    }

    @Test
    fun withoutAPersonaTheWordmarkStays() {
        val a = Robolectric.buildActivity(OnboardingHomeActivity::class.java).setup().get()
        assertEquals("Solar", a.findViewById<TextView>(R.id.brand_word_start).text.toString())
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.brand_figure).visibility)
    }
}
