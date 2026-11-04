package cloud.dopp.homes

import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.ScreenGate
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.verbindung.Verbindung
import cloud.dopp.homes.verbindung.Zustand
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.TimeZone
import javax.net.ssl.SSLHandshakeException

/**
 * The line to Homes as the app says it (owner, 2026-11-04: „Die App zeigt
 * ‚pausiert, Bildschirm inaktiv‘, wenn die Box nicht erreichbar ist“): what
 * a failed request means ([Zustand.ausFehler]), the one answer from the facts
 * ([Zustand.aus]), and the store every screen reads ([Verbindung]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerbindungTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun pairedWithTheScreenOn() {
        ServerStore.setBaseUrl(ctx, "https://homes.example")
        TokenStore.backend = ctx.getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx, "homes_device_T1")
        screen(true)
    }

    private fun screen(on: Boolean) = shadowOf(ctx.getSystemService(PowerManager::class.java)).setIsInteractive(on)

    // --- a failed request -> what it says --------------------------------------

    @Test
    fun noAnswerWithTheNetworkUpIsTheBoxNotReachable() {
        val noAnswer = listOf(
            ConnectException("Connection refused"),
            SocketTimeoutException("timeout"),
            UnknownHostException("homes.example"),
            SSLHandshakeException("reset"),
            IOException("unexpected end of stream"),
        )
        noAnswer.forEach { assertEquals(it.toString(), Zustand.BOX_WEG, Zustand.ausFehler(it, null, netz = true)) }
        noAnswer.forEach { assertEquals(it.toString(), Zustand.KEIN_NETZ, Zustand.ausFehler(it, null, netz = false)) }
    }

    @Test
    fun theProxysOwnAnswerIsTheBoxNotReachable() {
        assertEquals(Zustand.BOX_WEG, Zustand.ausFehler(null, 502, netz = true))
        assertEquals(Zustand.BOX_WEG, Zustand.ausFehler(null, 504, netz = true))
    }

    @Test
    fun aRefusedTokenAsksToPairAgain() {
        assertEquals(Zustand.NICHT_GEKOPPELT, Zustand.ausFehler(null, 401, netz = true))
        assertEquals(Zustand.NICHT_GEKOPPELT, Zustand.ausFehler(IOException("x"), 401, netz = false))
    }

    @Test
    fun homesAnsweringSaysNothingAgainstTheLine() {
        listOf(200, 204, 304, 403, 404, 500, 503).forEach { assertNull("$it", Zustand.ausFehler(null, it, netz = true)) }
        assertNull("a stream Homes ended", Zustand.ausFehler(null, null, netz = true))
        assertNull("the screen gate is no failure", Zustand.ausFehler(ScreenGate.ScreenOffException(), null, netz = true))
    }

    // --- the facts -> the one answer -------------------------------------------

    @Test
    fun pausedOnlyWhileTheScreenIsOffAndNothingFailed() {
        assertEquals(Zustand.PAUSE_BILDSCHIRM, Zustand.aus(true, bildschirm = false, gewollt = true, live = false, fehler = null))
        assertEquals(Zustand.BOX_WEG, Zustand.aus(true, bildschirm = false, gewollt = true, live = false, fehler = Zustand.BOX_WEG))
        assertEquals(Zustand.BOX_WEG, Zustand.aus(true, bildschirm = true, gewollt = true, live = false, fehler = Zustand.BOX_WEG))
        assertEquals(Zustand.KEIN_NETZ, Zustand.aus(true, bildschirm = true, gewollt = false, live = false, fehler = Zustand.KEIN_NETZ))
        assertEquals(Zustand.PAUSE_AUS, Zustand.aus(true, bildschirm = true, gewollt = false, live = false, fehler = null))
        assertEquals(Zustand.VERBINDET, Zustand.aus(true, bildschirm = true, gewollt = true, live = false, fehler = null))
        assertEquals(Zustand.LIVE, Zustand.aus(true, bildschirm = true, gewollt = true, live = true, fehler = null))
        assertEquals(Zustand.NICHT_GEKOPPELT, Zustand.aus(false, bildschirm = true, gewollt = false, live = false, fehler = null))
        assertEquals(Zustand.NICHT_GEKOPPELT, Zustand.aus(true, bildschirm = false, gewollt = true, live = true, fehler = Zustand.NICHT_GEKOPPELT))
    }

    @Test
    fun onlyTheFailuresMakeTheValuesOld() {
        assertEquals(setOf(Zustand.BOX_WEG, Zustand.KEIN_NETZ, Zustand.NICHT_GEKOPPELT), Zustand.values().filter { it.alt }.toSet())
    }

    @Test
    fun theTextsSayWhatHappened() {
        assertEquals("Box nicht erreichbar – versucht es wieder …", ctx.getString(Zustand.BOX_WEG.text))
        assertEquals("Kein Netz", ctx.getString(Zustand.KEIN_NETZ.text))
        assertEquals("Pausiert, Bildschirm aus", ctx.getString(Zustand.PAUSE_BILDSCHIRM.text))
        val paused = Zustand.values().filter { ctx.getString(it.text).contains("Bildschirm") }
        assertEquals("only the screen says the screen", listOf(Zustand.PAUSE_BILDSCHIRM), paused)
    }

    @Test
    fun sinceWhenIsTheTimeTodayElseTheDayToo() {
        val zone = TimeZone.getTimeZone("Europe/Berlin")
        val at0814 = 1_793_776_440_000L // 2026-11-04 08:14 in Berlin
        assertEquals("08:14", Verbindung.zeit(at0814, at0814 + 3_600_000L, zone))
        assertEquals("04.11. 08:14", Verbindung.zeit(at0814, at0814 + 24 * 3_600_000L, zone))
    }

    // --- the store -------------------------------------------------------------

    @Test
    fun aFailureIsKeptAndHomesAnsweringClearsIt() {
        Verbindung.ergebnis(ctx, null, 200)
        assertNull("current values say no Stand", Verbindung.stand(ctx))
        Verbindung.ergebnis(ctx, ConnectException("refused"), null)
        assertEquals(Zustand.BOX_WEG, Verbindung.zustand(ctx))
        assertTrue(Verbindung.stand(ctx)!!.matches(Regex("Stand \\d\\d:\\d\\d")))
        screen(false)
        assertEquals("the screen off does not make old values new", Zustand.BOX_WEG, Verbindung.zustand(ctx))
        Verbindung.ergebnis(ctx, ScreenGate.ScreenOffException(), null)
        assertEquals(Zustand.BOX_WEG, Verbindung.zustand(ctx))
        screen(true)
        Verbindung.ergebnis(ctx, null, 503)
        assertFalse(Verbindung.zustand(ctx).alt)
        assertNull(Verbindung.stand(ctx))
    }

    @Test
    fun withoutANetworkItSaysKeinNetz() {
        shadowOf(ctx.getSystemService(ConnectivityManager::class.java)).setDefaultNetworkActive(false)
        shadowOf(ctx.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)
        Verbindung.ergebnis(ctx, UnknownHostException("homes.example"), null)
        assertEquals(Zustand.KEIN_NETZ, Verbindung.zustand(ctx))
    }

    @Test
    fun aChangeIsSaidOnceToTheApp() {
        val app = shadowOf(ctx as android.app.Application)
        Verbindung.ergebnis(ctx, null, 200)
        app.clearBroadcastIntents()
        Verbindung.ergebnis(ctx, ConnectException("refused"), null)
        Verbindung.ergebnis(ctx, SocketTimeoutException("timeout"), null)
        val said = app.broadcastIntents.filter { it.action == Verbindung.GEAENDERT }
        assertEquals("one change, one broadcast", 1, said.size)
        assertEquals(ctx.packageName, said.single().`package`)
    }

    /** The widgets' and the start screen's reads report through their client: a refused port is the box not reachable. */
    @Test
    fun theReadsOfTheAppReportTheLine() {
        val closed = ServerSocket(0).use { it.localPort } // nothing listens there any more
        ServerStore.setBaseUrl(ctx, "https://127.0.0.1:$closed")
        assertNull(ApiClient(ctx).favoritesBody())
        assertEquals(Zustand.BOX_WEG, Verbindung.zustand(ctx))
    }

    /** Homes' answer, whatever it is, clears a failure; its 401 asks to pair again. */
    @Test
    fun theClientReportsEachAnswer() {
        Verbindung.ergebnis(ctx, ConnectException("refused"), null)
        val homes = MockWebServer()
        homes.enqueue(MockResponse().setResponseCode(404))
        homes.enqueue(MockResponse().setResponseCode(401))
        homes.start()
        try {
            val client = Verbindung.gemeldet(ctx, OkHttpClient())
            val req = Request.Builder().url(homes.url("/napi/portal/favorites")).build()
            client.newCall(req).execute().close()
            assertFalse("Homes answered", Verbindung.zustand(ctx).alt)
            client.newCall(req).execute().close()
            assertEquals(Zustand.NICHT_GEKOPPELT, Verbindung.zustand(ctx))
        } finally {
            homes.shutdown()
        }
    }
}
