package cloud.dopp.homes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.KannClient
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.kann.Kann
import cloud.dopp.homes.kann.KannLeser
import cloud.dopp.homes.kann.KannStore
import cloud.dopp.homes.kann.Liste
import cloud.dopp.homes.kann.hat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the box can do (homes ADR 0001 §2, §3; homes-android#231): the app
 * reads `/napi/kann`, ignores what it does not know, takes a known key that
 * is missing as „the box lacks it“, and with no answer — a 404 from an older
 * Homes, or none yet — offers everything as before. The golden answer is
 * Homes' own (`cmd/homes/testdata/kann_fullsite.json`, mdopp/homes#256).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KannTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private val server = MockWebServer()
    private val http = OkHttpClient()

    @Before
    fun clean() = KannStore.clear(ctx())

    @After
    fun stop() = server.shutdown()

    private fun client() = KannClient(server.url("/").toString().trimEnd('/'), "homes_device_T", http)

    @Test
    fun homesGoldenAnswerReads() {
        val k = Kann.lesen(FULLSITE)
        assertNotNull(k)
        k!!
        assertEquals(18, k.module.size)
        assertTrue(k.hat(Liste.WIDGETS, Napi.KannKey.GERAET))
        assertTrue(k.hat(Liste.WIDGETS, "tool:doc"))
        assertFalse("Standortverlauf is off on the full site", k.hat(Liste.MEHR, Napi.KannKey.STANDORTVERLAUF))
        assertFalse(k.hat(Liste.MEHR, Napi.KannKey.DATEN_SICHERN))
        assertEquals("standort", k.spurVon(Liste.MEHR, Napi.KannKey.STANDORTVERLAUF)?.name)
        assertEquals("Auf dieser Box läuft kein Reitti.", k.spurVon(Liste.TEILEN, "standort-import")?.grund)
        val spuren = k.spuren.map { it.name }
        assertTrue("an off module leaves a trace", "standort" in spuren)
        assertFalse("the admins' trace is on Homes' Box page", "werkstatt" in spuren || "nutzung" in spuren)
        assertFalse("a module that is on leaves none", "geraete" in spuren)
        val standort = k.module.single { it.name == "standort" }
        assertEquals("fehlt", standort.spurId)
        assertEquals("auf dieser Box nicht eingerichtet", standort.spur)
        assertEquals("Gesehen & Gehört", k.module.single { it.name == "gesehen" }.titel)
        assertEquals(listOf("whatsapp", "signal", "sms"), k.fehlend(Liste.SICHERUNG))
    }

    @Test
    fun anUnknownKeyIsIgnored() {
        val o = JSONObject(FULLSITE)
        o.put("zukunft", JSONObject().put("x", 1))
        o.getJSONObject("app").put("zukunft", org.json.JSONArray().put("y"))
        o.getJSONObject("app").getJSONArray("widgets").put("tool:neu")
        o.getJSONArray("module").getJSONObject(0).put("farbe", "gruen")
        val k = Kann.lesen(o.toString())!!
        assertEquals(18, k.module.size)
        assertTrue(k.hat(Liste.WIDGETS, Napi.KannKey.ENERGIE))
        assertFalse(k.hat(Liste.MEHR, Napi.KannKey.STANDORTVERLAUF))
    }

    @Test
    fun aKnownKeyThatIsMissingIsWhatTheBoxLacks() {
        val k = Kann.lesen("""{"version":1,"module":[],"app":{"widgets":["geraet"],"mehr":[]}}""")!!
        assertTrue(k.hat(Liste.WIDGETS, Napi.KannKey.GERAET))
        assertFalse("energie is missing from widgets", k.hat(Liste.WIDGETS, Napi.KannKey.ENERGIE))
        assertFalse(k.hat(Liste.MEHR, Napi.KannKey.DATEN_SICHERN))
        // a list Homes did not send says nothing: its features stay
        assertTrue(k.hat(Liste.TEILEN, "merken"))
        assertTrue(k.hat(Liste.KONTO, "kalender"))
    }

    @Test
    fun noAnswerMeansEverythingAsToday() {
        val unknown: Kann? = null
        Liste.values().forEach { assertTrue(unknown.hat(it, "irgendwas")) }
        assertNull("not JSON", Kann.lesen("<html>"))
        assertNull("no version", Kann.lesen("""{"module":[],"app":{"widgets":[]}}"""))
        assertNull(Kann.lesen(null))
        assertNull("nothing read yet", KannStore.kann(ctx()))
    }

    @Test
    fun aModuleWithoutItsNameOrStateIsSkipped() {
        val k = Kann.lesen(
            """{"version":1,"module":[{"titel":"Ohne Namen","an":false},{"name":"x","titel":"Ohne an"},
               {"name":"standort","an":false,"warum":"fehlt"}],"app":{}}""",
        )!!
        assertEquals(listOf("standort"), k.module.map { it.name })
        assertEquals("the name stands in for a missing title", "standort", k.module.single().titel)
        assertTrue("no bietet: no trace placed by it", k.module.single().bietet.isEmpty())
    }

    // --- the cache, the ETag, the 404 --------------------------------------------

    @Test
    fun aReadIsKeptAndAskedAgainWithItsEtag() {
        server.enqueue(MockResponse().setBody(FULLSITE).setHeader("ETag", "\"a1\""))
        server.enqueue(MockResponse().setResponseCode(304))
        assertTrue("a first answer changes what the app shows", KannLeser.einmal(ctx(), client()))
        assertEquals("\"a1\"", KannStore.etag(ctx()))
        assertFalse(KannStore.kann(ctx()).hat(Liste.MEHR, Napi.KannKey.STANDORTVERLAUF))
        assertFalse("304: nothing changed", KannLeser.einmal(ctx(), client()))
        val first = server.takeRequest()
        assertEquals("/napi/kann", first.path)
        assertEquals("Bearer homes_device_T", first.getHeader("Authorization"))
        assertEquals("\"a1\"", server.takeRequest().getHeader("If-None-Match"))
        assertNotNull("the kept answer stays after a 304", KannStore.kann(ctx()))
    }

    @Test
    fun theSameAnswerAgainChangesNothing() {
        repeat(2) { server.enqueue(MockResponse().setBody(FULLSITE).setHeader("ETag", "\"a1\"")) }
        assertTrue(KannLeser.einmal(ctx(), client()))
        assertFalse(KannLeser.einmal(ctx(), client()))
    }

    @Test
    fun anOlderHomesAnswers404AndTheAppForgets() {
        server.enqueue(MockResponse().setBody(FULLSITE).setHeader("ETag", "\"a1\""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"reason":"not_found"}"""))
        KannLeser.einmal(ctx(), client())
        assertTrue("forgetting changes what the app shows", KannLeser.einmal(ctx(), client()))
        assertNull(KannStore.kann(ctx()))
        assertNull(KannStore.etag(ctx()))
        assertTrue(KannStore.kann(ctx()).hat(Liste.MEHR, Napi.KannKey.STANDORTVERLAUF))
    }

    @Test
    fun offlineOrAnErrorKeepsTheKeptAnswer() {
        server.enqueue(MockResponse().setBody(FULLSITE).setHeader("ETag", "\"a1\""))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setBody("kein json"))
        KannLeser.einmal(ctx(), client())
        assertFalse(KannLeser.einmal(ctx(), client()))
        assertFalse("a body that is no answer is not kept", KannLeser.einmal(ctx(), client()))
        server.shutdown()
        assertFalse(KannLeser.einmal(ctx(), client()))
        assertNotNull(KannStore.kann(ctx()))
    }

    @Test
    fun atMostOnceAMinuteUnlessTheStreamCameBack() {
        val t = 1_000_000L
        assertTrue(KannStore.faellig(ctx(), t, immer = false))
        assertFalse(KannStore.faellig(ctx(), t + 59_000, immer = false))
        assertTrue("the live stream connected again", KannStore.faellig(ctx(), t + 59_500, immer = true))
        assertTrue(KannStore.faellig(ctx(), t + 59_500 + KannStore.MINUTE, immer = false))
        assertTrue("a clock set back does not stop the reads", KannStore.faellig(ctx(), t, immer = false))
    }

    @Test
    fun aWishsAnswerReplacesItsModuleInTheKeptAnswer() {
        KannStore.neu(ctx(), FULLSITE, "\"a1\"")
        KannStore.ersetzen(
            ctx(),
            """{"name":"standort","titel":"Standortverlauf","an":false,"warum":"fehlt","bietet":{"widgets":[],"teilen":["standort-import"],
               "konto":[],"sicherung":[],"mehr":["standortverlauf"]},"spur_id":"angefordert","spur":"angefordert","wunsch":{"stand":"angefordert"}}""",
        )
        val m = KannStore.kann(ctx())!!.module.single { it.name == "standort" }
        assertEquals("angefordert", m.spurId)
        assertFalse(m.anfordern)
        assertEquals(18, KannStore.kann(ctx())!!.module.size)
        assertEquals("the ETag stays: the next read brings Homes' whole answer", "\"a1\"", KannStore.etag(ctx()))
    }

    /** A placed widget whose type the box lost shows the module's reason, not an error (homes ADR 0001 §3). */
    @Test
    fun aPlacedWidgetWhoseTypeWentShowsTheReason() {
        assertFalse("unknown: drawn as before", cloud.dopp.homes.widget.WidgetFallback.weg(ctx(), 7, Napi.KannKey.ENERGIE))
        KannStore.neu(ctx(), FULLSITE, null)
        assertFalse("offered: drawn as before", cloud.dopp.homes.widget.WidgetFallback.weg(ctx(), 7, Napi.KannKey.ENERGIE))
        KannStore.neu(ctx(), """{"version":1,"module":[{"name":"energie","titel":"Energie","an":false,"grund":"Kein HA.",
            "bietet":{"widgets":["energie"]}}],"app":{"widgets":["geraet"]}}""", null)
        assertTrue("lost: its reason instead", cloud.dopp.homes.widget.WidgetFallback.weg(ctx(), 7, Napi.KannKey.ENERGIE))
        assertFalse(cloud.dopp.homes.widget.WidgetFallback.weg(ctx(), 7, Napi.KannKey.GERAET))
    }

    companion object {
        val FULLSITE: String by lazy {
            KannTest::class.java.classLoader!!.getResourceAsStream("kann/fullsite.json")!!.bufferedReader().use { it.readText() }
        }
    }
}
