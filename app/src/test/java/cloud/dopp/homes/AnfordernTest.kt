package cloud.dopp.homes

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.KannClient
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.kann.Anfordern
import cloud.dopp.homes.kann.Anfrage
import cloud.dopp.homes.kann.KannStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * „Hier anfordern“ (homes ADR 0001 §10; homes-android#231): a press answers
 * at once and ends visibly — the module's new state, or why not, also when
 * Homes does not answer in time or cannot be reached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnfordernTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private val server = MockWebServer()

    @Before
    fun kept() {
        KannStore.clear(ctx())
        Anfordern.clear()
        KannStore.neu(ctx(), KannTest.FULLSITE, "\"a1\"")
    }

    @After
    fun stop() = server.shutdown()

    private fun client(read: Long = 5) = KannClient(
        server.url("/").toString().trimEnd('/'), "homes_device_T",
        OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(read, TimeUnit.SECONDS).build(),
    )

    private fun standort() = KannStore.kann(ctx())!!.module.single { it.name == "standort" }

    @Test
    fun idleBeforeAnyPress() {
        assertEquals(Anfrage.Bereit, Anfordern.stand("standort"))
        // Homes' full site has no Laib that takes wishes: its traces offer no request
        assertEquals(false, standort().anfordern)
    }

    @Test
    fun doneTheTraceShowsTheNewState() {
        val eintrag = JSONObject(KannTest.FULLSITE).getJSONArray("module").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.single { it.getString("name") == "standort" }
        }.put("spur_id", "angefordert").put("spur", "angefordert").put("anfordern", false)
            .put("wunsch", JSONObject().put("stand", "angefordert").put("seit", "2026-11-04"))
        server.enqueue(MockResponse().setBody(eintrag.toString()))
        assertEquals(Anfrage.Fertig, Anfordern.einmal(ctx(), client(), "standort"))
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/napi/kann/anfordern", sent.path)
        assertEquals("standort", JSONObject(sent.body.readUtf8()).getString("modul"))
        assertEquals("angefordert", standort().spurId)
        assertEquals(false, standort().anfordern)
    }

    @Test
    fun failedHomesSentenceIsShown() {
        val satz = "Diese Box nimmt noch keine Wünsche an."
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"ok":false,"error":"$satz"}"""))
        assertEquals(Anfrage.Abgelehnt(503, satz), Anfordern.einmal(ctx(), client(), "standort"))
        assertEquals("nothing changed on a refusal", "fehlt", standort().spurId)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        assertEquals("401 is no sentence: the app says it", Anfrage.Abgelehnt(401, null), Anfordern.einmal(ctx(), client(), "standort"))
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>"))
        assertEquals(Anfrage.Abgelehnt(502, null), Anfordern.einmal(ctx(), client(), "standort"))
    }

    @Test
    fun timedOutSaysSo() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(Anfrage.Zeit, Anfordern.einmal(ctx(), client(read = 1), "standort"))
    }

    @Test
    fun offlineSaysSo() {
        server.start()
        val c = client()
        server.shutdown()
        assertEquals(Anfrage.Offline, Anfordern.einmal(ctx(), c, "standort"))
    }

    /** The press: running at once, a second press asks nothing more, then the end. */
    @Test
    fun runningAtOnceThenTheEnd() {
        server.enqueue(MockResponse().setResponseCode(504).setBody("""{"ok":false,"error":"Zu spät."}""").setBodyDelay(300, TimeUnit.MILLISECONDS))
        // the plain test server as the paired Homes (ServerStore keeps https only)
        ctx().getSharedPreferences("homes_server", Context.MODE_PRIVATE).edit()
            .putString("base_url", server.url("/").toString().trimEnd('/')).commit()
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_T")
        var ended = 0
        Anfordern.los(ctx(), "standort") { ended++ }
        assertEquals(Anfrage.Laeuft, Anfordern.stand("standort"))
        Anfordern.los(ctx(), "standort") { ended++ }
        val until = System.currentTimeMillis() + 15_000
        while (Anfordern.stand("standort") == Anfrage.Laeuft && System.currentTimeMillis() < until) Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("one request, one end", 1, ended)
        assertEquals(Anfrage.Abgelehnt(504, "Zu spät."), Anfordern.stand("standort"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unpairedThePressSaysPairAgain() {
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.clear(ctx())
        Anfordern.los(ctx(), "standort") {}
        val until = System.currentTimeMillis() + 5_000
        while (Anfordern.stand("standort") == Anfrage.Laeuft && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(Anfrage.Abgelehnt(Anfordern.UNAUTHORIZED, null), Anfordern.stand("standort"))
    }
}
