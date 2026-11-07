package cloud.dopp.homes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.einrichtung.Einrichtung
import cloud.dopp.homes.einrichtung.EinrichtungClient
import cloud.dopp.homes.einrichtung.EinrichtungLeser
import cloud.dopp.homes.einrichtung.EinrichtungStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The person's modules and setup tasks (`/napi/einrichtung`, owner
 * 2026-11-04): the answer read as Homes writes it (docs/REFERENCE.md), an
 * older Homes' 404 that hides it all, which modules „Öffnen“ lists and
 * which tasks show, and what the phone tells Homes it has installed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EinrichtungTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private val server = MockWebServer()
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private fun client() = EinrichtungClient(server.url("/").toString().trimEnd('/'), "homes_device_T", http)

    @Before fun auf() = server.start()

    @After fun zu() {
        server.shutdown()
        EinrichtungStore.clear(ctx())
    }

    // --- the answer ------------------------------------------------------------

    @Test fun theAnswerIsReadAsHomesWritesIt() {
        val e = Einrichtung.lesen(ANTWORT)!!
        assertEquals(1, e.offen)
        assertEquals(listOf("fotos", "filme", "einkauf", "dokumente", "standort", "sprechen"), e.module.map { it.name })
        val fotos = e.module[0]
        assertEquals("Immich", fotos.app?.name)
        assertEquals("app.alextran.immich", fotos.app?.paket)
        assertEquals("https://play.google.com/store/apps/details?id=app.alextran.immich", fotos.app?.store)
        assertEquals("Fotos auf dem Handy einrichten", fotos.aufgabe?.titel)
        assertFalse(fotos.aufgabe!!.erledigt)
        assertEquals("https://homes.example/einrichten/fotos", fotos.aufgabe?.einrichten)
        assertTrue("a finished task, am and wie beside", e.module[1].aufgabe!!.erledigt)
        assertNull(e.module[2].app)
        assertEquals("https://homes.example/einkauf", e.module[2].web)
        assertEquals(
            mapOf("einkauf" to "https://homes.example/einkauf", "zeitleiste" to "https://reitti.example", "scannen" to "https://homes.example/napi/upload"),
            e.ziele,
        )
    }

    @Test fun whatIsNoAnswerIsNone() {
        for (junk in listOf(null, "", "nonsense", "{}", """{"version":0,"module":[]}""", """{"ok":false,"reason":"not_found"}""")) {
            assertNull("junk $junk", Einrichtung.lesen(junk))
        }
    }

    @Test fun nothingIsTakenOnAGuess() {
        val e = Einrichtung.lesen(
            """{"version":1,"offen":-3,"module":[{"titel":"ohne Namen"},
               {"name":"dokumente","web":"http://klartext.example","nur_web":true},
               {"name":"standort","web":"javascript:alert(1)","nur_web":true},
               {"name":"musik","icon":"z","web":"https://z.example","nur_web":true,"neu":"ein Feld von morgen"}],
               "ziele":{"einkauf":"http://klartext.example","unbekannt":"https://u.example","zeitleiste":"https://r.example"}}""",
        )!!
        assertEquals(0, e.offen)
        assertEquals("a module without its name is none", listOf("dokumente", "standort", "musik"), e.module.map { it.name })
        assertEquals("only https addresses", listOf("musik"), e.oeffnen.map { it.name })
        assertEquals("only https targets the app knows", mapOf("zeitleiste" to "https://r.example"), e.ziele)
    }

    // --- an older Homes --------------------------------------------------------

    @Test fun anOlderHomesAnswers404AndTheKeptAnswerGoes() {
        EinrichtungStore.neu(ctx(), ANTWORT)
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"reason":"not_found"}"""))
        assertEquals(EinrichtungClient.Antwort.Fehlt, client().lesen())
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"error":"no_such_path"}"""))
        assertTrue("forgetting is a change", EinrichtungLeser.einmal(ctx(), client()) { false })
        assertNull(EinrichtungStore.einrichtung(ctx()))
        assertEquals("one GET each, no report", 2, server.requestCount)
    }

    @Test fun noAnswerKeepsWhatWasRead() {
        EinrichtungStore.neu(ctx(), ANTWORT)
        server.enqueue(MockResponse().setResponseCode(502))
        assertFalse(EinrichtungLeser.einmal(ctx(), client()) { true })
        assertEquals(1, EinrichtungStore.einrichtung(ctx())?.offen)
    }

    // --- what is shown -----------------------------------------------------------

    /** „Öffnen“ is three modules only (owner, 2026-11-04; Gesundheit gone 2026-11-07), in their order, each when the box sends it. */
    @Test fun oeffnenListsTheThreeTheBoxSends() {
        val e = Einrichtung.lesen(ANTWORT)!!
        assertEquals("musik is not on this box", listOf("dokumente", "standort"), e.oeffnen.map { it.name })
        assertTrue("not Einkauf, not a module with its own app", e.oeffnen.none { it.name in setOf("einkauf", "fotos", "filme", "sprechen") })
        val alle = Einrichtung.lesen(
            """{"version":1,"module":[
               {"name":"musik","titel":"Musik verwalten","web":"https://homes.example/musik","nur_web":true},
               {"name":"notizen","titel":"Notizen","web":"https://homes.example/notizen","nur_web":true},
               {"name":"standort","titel":"Zeitleiste","web":"https://reitti.example","nur_web":true},
               {"name":"kalender","titel":"Kalender","web":"https://homes.example/kalender","nur_web":true},
               {"name":"dokumente","titel":"Dokumente","nur_web":true}]}""",
        )!!
        assertEquals("in the owner's order; dokumente without an address is none", listOf("standort", "musik"), alle.oeffnen.map { it.name })
        assertEquals(listOf("dokumente", "standort", "musik"), Einrichtung.OEFFNEN)
    }

    @Test fun aTaskShowsOnlyWhileItIsOpen() {
        val e = Einrichtung.lesen(ANTWORT)!!
        assertEquals("filme is done, sprechen has no task", listOf("fotos"), e.aufgaben.map { it.name })
        val alleFertig = Einrichtung.lesen(ANTWORT.replace("\"erledigt\":false", "\"erledigt\":true"))!!
        assertTrue(alleFertig.aufgaben.isEmpty())
    }

    // --- what the phone tells Homes ----------------------------------------------

    @Test fun theInstalledSubsetIsTheModulesAppsThePhoneHas() {
        val e = Einrichtung.lesen(ANTWORT)!!
        assertEquals(setOf("app.alextran.immich", "org.jellyfin.mobile", "org.thoughtcrime.securesms"), e.pakete)
        val phone = setOf("app.alextran.immich", "org.jellyfin.mobile", "com.whatsapp")
        assertEquals(listOf("app.alextran.immich", "org.jellyfin.mobile"), e.installiert { it in phone })
        assertTrue("no open task's app: nothing to tell", e.installiert { it == "org.jellyfin.mobile" }.isEmpty())
        assertTrue(e.installiert { false }.isEmpty())
    }

    @Test fun anInstalledAppIsReportedAndHomesAnswerIsKept() {
        val fertig = ANTWORT.replace("\"offen\":1", "\"offen\":0").replace("\"erledigt\":false", "\"erledigt\":true,\"am\":\"2026-11-04\",\"wie\":\"app\"")
        server.enqueue(MockResponse().setBody(ANTWORT))
        server.enqueue(MockResponse().setBody(fertig))
        assertTrue(EinrichtungLeser.einmal(ctx(), client()) { it == "app.alextran.immich" })
        assertEquals("GET", server.takeRequest().method)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/napi/einrichtung/apps", post.path)
        assertEquals("Bearer homes_device_T", post.getHeader("Authorization"))
        assertEquals("""["app.alextran.immich"]""", JSONObject(post.body.readUtf8()).getJSONArray("pakete").toString())
        assertTrue(EinrichtungStore.einrichtung(ctx())!!.aufgaben.isEmpty())
    }

    @Test fun nothingInstalledNoReport() {
        server.enqueue(MockResponse().setBody(ANTWORT))
        assertTrue(EinrichtungLeser.einmal(ctx(), client()) { false })
        assertEquals(1, server.requestCount)
    }

    @Test fun aReadIsDueEveryFiveMinutesAtMost() {
        assertTrue(EinrichtungStore.faellig(ctx(), 1_000_000L, immer = false))
        assertFalse(EinrichtungStore.faellig(ctx(), 1_000_000L + EinrichtungStore.ABSTAND_MS - 1, immer = false))
        assertTrue(EinrichtungStore.faellig(ctx(), 1_000_000L + EinrichtungStore.ABSTAND_MS + 1, immer = false))
        assertTrue(EinrichtungStore.faellig(ctx(), 1_000_000L + EinrichtungStore.ABSTAND_MS + 2, immer = true))
    }

    companion object {
        /** Homes' answer as docs/REFERENCE.md shows it, with the modules a household has. */
        const val ANTWORT = """{"version":1,"offen":1,"module":[
            {"name":"fotos","titel":"Fotos","icon":"fotos","web":"https://photos.example",
             "app":{"name":"Immich","paket":"app.alextran.immich","store":"https://play.google.com/store/apps/details?id=app.alextran.immich"},
             "nur_web":false,
             "aufgabe":{"titel":"Fotos auf dem Handy einrichten","erledigt":false,"einrichten":"https://homes.example/einrichten/fotos"}},
            {"name":"filme","titel":"Filme und Musik","icon":"filme","web":"https://media.example",
             "app":{"name":"Jellyfin","paket":"org.jellyfin.mobile","store":"https://play.google.com/store/apps/details?id=org.jellyfin.mobile"},
             "nur_web":false,
             "aufgabe":{"titel":"Filme und Musik auf dem Handy einrichten","erledigt":true,"am":"2026-11-04","wie":"app","einrichten":"https://homes.example/einrichten/filme"}},
            {"name":"einkauf","titel":"Einkauf","icon":"einkauf","web":"https://homes.example/einkauf","nur_web":true},
            {"name":"dokumente","titel":"Dokumente","icon":"dokumente","web":"https://homes.example/dokumente","nur_web":true},
            {"name":"standort","titel":"Zeitleiste","icon":"standort","web":"https://reitti.example","nur_web":true},
            {"name":"sprechen","titel":"Sprechen","icon":"sprechen",
             "app":{"name":"Signal","paket":"org.thoughtcrime.securesms","store":"https://play.google.com/store/apps/details?id=org.thoughtcrime.securesms"},
             "nur_web":false}],
           "ziele":{"einkauf":"https://homes.example/einkauf","zeitleiste":"https://reitti.example","scannen":"https://homes.example/napi/upload"}}"""
    }
}
