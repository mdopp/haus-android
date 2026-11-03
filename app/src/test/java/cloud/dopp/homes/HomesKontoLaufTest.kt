package cloud.dopp.homes

import android.Manifest
import android.accounts.AccountManager
import android.app.Application
import android.content.ContentResolver
import android.content.SyncResult
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.GemeinsamClient
import cloud.dopp.homes.data.GemeinsamClient.Antwort
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.konto.HomesKonto
import cloud.dopp.homes.konto.HomesKonto.Teil
import cloud.dopp.homes.konto.KontoLauf
import cloud.dopp.homes.konto.KontoStore
import cloud.dopp.homes.sicherung.Lauf
import cloud.dopp.homes.ui.HomesKontoActivity
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
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
import org.robolectric.shadows.ShadowContentResolver

/**
 * Das Homes-Konto (#206): one sync's outcomes as the screen reads them, the
 * client against a fake Homes (the ETag and Homes' 304), and the screen's
 * switches — the account made, synced now and hourly, gone with both off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomesKontoLaufTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: KontoStore

    @Before
    fun setUp() {
        store = KontoStore(app)
        store.vergiss(Teil.KONTAKTE)
        TokenStore.backend = app.getSharedPreferences("test_tokens", android.content.Context.MODE_PRIVATE)
    }

    @After
    fun tearDown() {
        HomesKonto.entfernen(app)
    }

    /** A part whose phone side is a counter: [geschrieben] the texts it wrote, [aufDemHandy] its entries. */
    private inner class Probe(antworten: List<Antwort>, var aufDemHandy: Int = 0, val fehler: Exception? = null) {
        val gefragt = ArrayList<String?>()
        val geschrieben = ArrayList<String>()
        private val rest = ArrayDeque(antworten)
        val lauf = object : KontoLauf(Teil.KONTAKTE, store, { e -> gefragt += e; rest.removeFirst() }) {
            override fun schreiben(text: String): Int {
                fehler?.let { throw it }
                geschrieben += text
                aufDemHandy = text.length
                return aufDemHandy
            }
            override fun anzahl() = aufDemHandy
            override fun abgelehnt(code: Int) = "abgelehnt $code"
            override val keineErlaubnis = "keine Erlaubnis"
        }
    }

    @Test
    fun aNewStateIsWrittenAndItsEtagKept() {
        val p = Probe(listOf(Antwort.Neu("abc", "\"e1\"")))
        val r = SyncResult()
        p.lauf.laufen(r)
        assertEquals(listOf("abc"), p.geschrieben)
        assertEquals("\"e1\"", store.etag(Teil.KONTAKTE))
        assertEquals(3, store.stand(Teil.KONTAKTE)!!.anzahl)
        assertNull(store.meldung(Teil.KONTAKTE))
        assertFalse(r.hasError())
        assertEquals(listOf<String?>(null), p.gefragt)
    }

    @Test
    fun homesSaysUnchangedAndThePhoneStillHoldsIt() {
        store.geschafft(Teil.KONTAKTE, "\"e1\"", 3)
        val p = Probe(listOf(Antwort.Gleich), aufDemHandy = 3)
        p.lauf.laufen(SyncResult())
        assertTrue(p.geschrieben.isEmpty())
        assertEquals(listOf<String?>("\"e1\""), p.gefragt)
    }

    @Test
    fun homesSaysUnchangedButThePhoneLostEntriesSoAllOfItComesAgain() {
        store.geschafft(Teil.KONTAKTE, "\"e1\"", 3)
        val p = Probe(listOf(Antwort.Gleich, Antwort.Neu("abc", "\"e1\"")), aufDemHandy = 1)
        p.lauf.laufen(SyncResult())
        assertEquals(listOf("\"e1\"", null), p.gefragt)
        assertEquals(listOf("abc"), p.geschrieben)
    }

    @Test
    fun eachFailureLeavesASentenceAndTellsAndroidWhetherToBackOff() {
        fun lauf(a: Antwort, fehler: Exception? = null): SyncResult {
            val r = SyncResult()
            Probe(listOf(a), fehler = fehler).lauf.laufen(r)
            return r
        }
        var r = lauf(Antwort.Unerreichbar)
        assertEquals(KontoLauf.UNERREICHBAR, store.meldung(Teil.KONTAKTE))
        assertEquals(1L, r.stats.numIoExceptions)
        r = lauf(Antwort.Abgelehnt(401, null))
        assertEquals(Lauf.NICHT_GEKOPPELT, store.meldung(Teil.KONTAKTE))
        assertEquals(1L, r.stats.numAuthExceptions)
        lauf(Antwort.Abgelehnt(503, "Kalender und Kontakte (Radicale) sind auf dieser Box nicht eingerichtet."))
        assertEquals("Kalender und Kontakte (Radicale) sind auf dieser Box nicht eingerichtet.", store.meldung(Teil.KONTAKTE))
        lauf(Antwort.Abgelehnt(403, null))
        assertEquals("abgelehnt 403", store.meldung(Teil.KONTAKTE))
        r = lauf(Antwort.Neu("x", null), SecurityException("WRITE_CONTACTS"))
        assertEquals("keine Erlaubnis", store.meldung(Teil.KONTAKTE))
        assertEquals(1L, r.stats.numAuthExceptions)
        lauf(Antwort.Neu("x", null), android.os.RemoteException())
        assertEquals(KontoLauf.NICHT_GESCHRIEBEN, store.meldung(Teil.KONTAKTE))
        // the next good run clears the word
        lauf(Antwort.Neu("x", "\"e2\""))
        assertNull(store.meldung(Teil.KONTAKTE))
    }

    @Test
    fun theClientSendsTheEtagAndReadsHomesAnswers() {
        val homes = MockWebServer()
        homes.enqueue(MockResponse().setBody("BEGIN:VCARD\r\nEND:VCARD\r\n").setHeader("ETag", "\"e1\""))
        homes.enqueue(MockResponse().setResponseCode(304))
        homes.enqueue(MockResponse().setResponseCode(503).setBody("""{"fehler":"Nicht eingerichtet."}"""))
        homes.start()
        try {
            val c = GemeinsamClient(homes.url("/").toString(), "homes_device_T")
            assertEquals(Antwort.Neu("BEGIN:VCARD\r\nEND:VCARD\r\n", "\"e1\""), c.kontakte(null))
            assertEquals(Antwort.Gleich, c.kalender("\"e1\""))
            assertEquals(Antwort.Abgelehnt(503, "Nicht eingerichtet."), c.kontakte("\"e1\""))
            val erste = homes.takeRequest()
            assertEquals(Napi.GEMEINSAM_KONTAKTE.path, erste.path)
            assertEquals("Bearer homes_device_T", erste.getHeader("Authorization"))
            assertEquals("", erste.getHeader("If-None-Match"))
            val zweite = homes.takeRequest()
            assertEquals(Napi.GEMEINSAM_KALENDER.path, zweite.path)
            assertEquals("\"e1\"", zweite.getHeader("If-None-Match"))
        } finally {
            homes.shutdown()
        }
        assertEquals(Antwort.Unerreichbar, GemeinsamClient("http://127.0.0.1:1/", "t").kontakte(null))
    }

    // --- the screen -----------------------------------------------------------------

    private fun schalter(a: HomesKontoActivity, id: Int) = a.findViewById<SwitchCompat>(id)

    @Test
    fun unpairedTheSwitchesAreOffAndSaySo() {
        TokenStore.clear(app)
        val a = Robolectric.buildActivity(HomesKontoActivity::class.java).setup().get()
        assertFalse(schalter(a, R.id.konto_kontakte_an).isEnabled)
        assertFalse(a.findViewById<Button>(R.id.konto_jetzt).isEnabled)
        assertEquals(Lauf.NICHT_GEKOPPELT, a.findViewById<TextView>(R.id.konto_zustand).text.toString())
        assertEquals("Auf dem Handy nur zum Lesen: ändern geht in Homes.", app.getString(R.string.konto_nur_lesen))
    }

    @Test
    fun theSwitchMakesTheAccountSyncsNowAndHourlyAndBothOffTakeItAway() {
        ServerStore.setBaseUrl(app, "https://homes.example.org")
        TokenStore.save(app, "homes_device_T")
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        val a = Robolectric.buildActivity(HomesKontoActivity::class.java).setup().get()
        val kontakte = schalter(a, R.id.konto_kontakte_an)
        assertTrue(kontakte.isEnabled)
        assertFalse(kontakte.isChecked)

        kontakte.performClick()
        assertTrue(HomesKonto.da(app))
        assertTrue(HomesKonto.an(app, Teil.KONTAKTE))
        assertFalse("the calendar stays off", HomesKonto.an(app, Teil.KALENDER))
        assertEquals(1, ContentResolver.getIsSyncable(HomesKonto.konto, ContactsContract.AUTHORITY))
        assertEquals(0, ContentResolver.getIsSyncable(HomesKonto.konto, CalendarContract.AUTHORITY))
        val perioden = ContentResolver.getPeriodicSyncs(HomesKonto.konto, ContactsContract.AUTHORITY)
        assertEquals(listOf(HomesKonto.STUNDE), perioden.map { it.period })
        assertTrue("synced at once", (ShadowContentResolver.getStatus(HomesKonto.konto, ContactsContract.AUTHORITY)?.syncRequests ?: 0) > 0)
        assertEquals(app.getString(R.string.konto_gleicht_ab), a.findViewById<TextView>(R.id.konto_zustand).text.toString())
        assertEquals(View.VISIBLE, a.findViewById<TextView>(R.id.konto_kontakte_stand).visibility)

        kontakte.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(200)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse("both off: the account is gone", AccountManager.get(app).getAccountsByType(HomesKonto.TYP).isNotEmpty())
    }

    @Test
    fun withoutThePermissionNothingIsMadeYet() {
        ServerStore.setBaseUrl(app, "https://homes.example.org")
        TokenStore.save(app, "homes_device_T")
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val a = Robolectric.buildActivity(HomesKontoActivity::class.java).setup().get()
        schalter(a, R.id.konto_kalender_an).performClick()
        assertFalse("asked first, made after", HomesKonto.da(app))
        assertEquals(
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            shadowOf(a).lastRequestedPermission?.requestedPermissions?.toList(),
        )
    }
}
