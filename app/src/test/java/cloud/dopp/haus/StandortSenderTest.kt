package cloud.dopp.haus

import cloud.dopp.haus.data.Napi
import cloud.dopp.haus.data.StandortClient
import cloud.dopp.haus.data.StandortClient.Ergebnis
import cloud.dopp.haus.standort.Kanal
import cloud.dopp.haus.standort.Modus
import cloud.dopp.haus.standort.Plan.MINUTE
import cloud.dopp.haus.standort.Puffer
import cloud.dopp.haus.standort.Punkt
import cloud.dopp.haus.standort.Sendegedaechtnis
import cloud.dopp.haus.standort.Sender
import cloud.dopp.haus.standort.Senden
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * The points on the phone go to Haus in batches, and leave the phone only
 * once Haus says Reitti has them: a missing token, Reitti down, Haus away —
 * every other answer keeps them, counts a failure and pauses.
 */
@RunWith(RobolectricTestRunner::class) // org.json is Android's
@Config(sdk = [34])
class StandortSenderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Gedaechtnis : Sendegedaechtnis {
        override var fehlschlaege = 0
        override var letzterFehlschlag = 0L
        var satz: String? = null
        var gesendet = 0
        override fun fehlgeschlagen(zeit: Long, satz: String?) {
            fehlschlaege++
            letzterFehlschlag = zeit
            this.satz = satz
        }
        override fun gesendet(zeit: Long, anzahl: Int, verworfen: Int) {
            fehlschlaege = 0
            satz = null
            gesendet += anzahl
        }
    }

    private val jetzt = 1_800_000_000_000L

    private fun punkte(n: Int, ab: Long = jetzt - 60 * MINUTE) =
        (0 until n).map { Punkt(ab + it * 1000L, 52.5 + it * 1e-5, 13.4, 12f, 34.0, 1.5f) }

    private fun puffer(name: String = "p.txt", hoechstens: Int = Puffer.HOECHSTENS) = Puffer(File(tmp.root, name), hoechstens)

    @Test
    fun takenBatchesLeaveThePhoneInOrderAtMost500AtATime() {
        val p = puffer()
        val alle = punkte(1200)
        p.dazu(alle)
        val gesehen = mutableListOf<List<Punkt>>()
        val g = Gedaechtnis()
        val lauf = Sender(p, g, { b -> gesehen += b; Ergebnis.Angenommen(b.size, 0) }, { jetzt }).senden(Modus.SPARSAM)
        assertEquals(Sender.Lauf.ALLES_GESENDET, lauf)
        assertEquals(listOf(500, 500, 200), gesehen.map { it.size })
        assertEquals(alle, gesehen.flatten())
        assertEquals(0, p.anzahl())
        assertEquals(1200, g.gesendet)
    }

    @Test
    fun aMissingTokenAReittiErrorAndNoHausKeepTheBatch() {
        val antworten = listOf<() -> Ergebnis>(
            { Ergebnis.Behalten(409, Napi.GRUND_KEIN_SCHLUESSEL, null) },
            { Ergebnis.Behalten(409, Napi.GRUND_SCHLUESSEL_ABGELEHNT, "Reitti kennt deinen Schlüssel nicht mehr.") },
            { Ergebnis.Behalten(503, Napi.GRUND_REITTI_WEG, "Reitti nimmt gerade nichts an.") },
            { Ergebnis.Behalten(502, null, null) },
            { Ergebnis.NichtGekoppelt },
            { throw IOException("no route") },
        )
        antworten.forEachIndexed { i, antwort ->
            val p = puffer("p$i.txt")
            p.dazu(punkte(300))
            val g = Gedaechtnis()
            val lauf = Sender(p, g, Kanal { antwort() }, { jetzt }).senden(Modus.SPARSAM)
            assertEquals("answer $i", Sender.Lauf.BEHALTEN, lauf)
            assertEquals("answer $i: nothing left the phone", 300, p.anzahl())
            assertEquals(1, g.fehlschlaege)
            assertEquals(jetzt, g.letzterFehlschlag)
            assertTrue("answer $i says why: ${g.satz}", !g.satz.isNullOrBlank())
        }
    }

    @Test
    fun noTokenSaysWhatToDoInOneSentence() {
        val p = puffer()
        p.dazu(punkte(1))
        val g = Gedaechtnis()
        Sender(p, g, { Ergebnis.Behalten(409, Napi.GRUND_KEIN_SCHLUESSEL, null) }, { jetzt }).senden(Modus.SPARSAM)
        assertEquals(Sender.KEIN_SCHLUESSEL, g.satz)
        assertTrue(g.satz!!.contains("Mehr → Standortverlauf"))
    }

    @Test
    fun aFailureMidwayKeepsTheRestAndThePauseHolds() {
        val p = puffer()
        p.dazu(punkte(700))
        val g = Gedaechtnis()
        var n = 0
        val kanal = Kanal { b -> if (n++ == 0) Ergebnis.Angenommen(b.size, 0) else throw IOException("gone") }
        assertEquals(Sender.Lauf.BEHALTEN, Sender(p, g, kanal, { jetzt }).senden(Modus.SPARSAM))
        assertEquals(200, p.anzahl())
        assertEquals(punkte(700)[500].zeit, p.aeltester())
        // a minute later, the pause holds: nothing is tried
        var versucht = false
        val danach = Sender(p, g, { versucht = true; Ergebnis.Angenommen(it.size, 0) }, { jetzt + MINUTE }).senden(Modus.SPARSAM)
        assertEquals(Sender.Lauf.NICHTS_FAELLIG, danach)
        assertTrue(!versucht)
        // the person taps „Jetzt senden“: it goes
        assertEquals(
            Sender.Lauf.ALLES_GESENDET,
            Sender(p, g, { Ergebnis.Angenommen(it.size, 0) }, { jetzt + MINUTE }).senden(Modus.SPARSAM, gedraengt = true),
        )
        assertEquals(0, g.fehlschlaege)
    }

    @Test
    fun freshPointsWaitForTheirBatch() {
        val p = puffer()
        p.dazu(punkte(5, ab = jetzt - MINUTE))
        var versucht = false
        assertEquals(
            Sender.Lauf.NICHTS_FAELLIG,
            Sender(p, Gedaechtnis(), { versucht = true; Ergebnis.Angenommen(0, 0) }, { jetzt }).senden(Modus.DETAILLIERT),
        )
        assertTrue(!versucht)
    }

    @Test
    fun theBufferSurvivesKeepsOrderSkipsABrokenLineAndSaysWhatAFullOneDropped() {
        val f = File(tmp.root, "b.txt")
        Puffer(f).dazu(punkte(3))
        f.appendText("1800000000000;52.5;13") // a line cut by a killed process
        val wieder = Puffer(f, hoechstens = 5)
        assertEquals(4, wieder.anzahl())
        val stapel = wieder.stapel(10)
        assertEquals(punkte(3), stapel.punkte)
        assertEquals(4, stapel.zeilen)
        assertEquals("the oldest two go for room, and that is said", 2, wieder.dazu(punkte(3, ab = jetzt)))
        assertEquals(5, wieder.anzahl())
        wieder.weg(5)
        assertEquals(0, wieder.anzahl())
        assertNull(wieder.aeltester())
        val ohne = Punkt(1L, 1.0, 2.0, 3f)
        assertEquals(ohne, Punkt.aus(ohne.zeile()))
    }

    /** The client: the body Haus reads, and only a 2xx with `angenommen` lets the batch go. */
    @Test
    fun theClientSendsWhatHausReadsAndReadsItsAnswers() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"angenommen":2,"verworfen":0}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"fehler":"Verbinde zuerst Reitti.","grund":"kein_schluessel"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"fehler":"Reitti nimmt gerade nichts an.","grund":"reitti_weg"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.start()
        try {
            val c = StandortClient(server.url("/").toString(), "haus_device_test")
            val zwei = listOf(Punkt(1_762_070_100_000L, 52.52, 13.405, 12.5f, 34.0, 1.5f), Punkt(1_762_070_160_000L, 52.5201, 13.4051, 30f))
            assertEquals(Ergebnis.Angenommen(2, 0), c.senden(zwei))
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/napi/standort", req.path)
            assertEquals("Bearer haus_device_test", req.getHeader("Authorization"))
            val body = JSONObject(req.body.readUtf8())
            val p = body.getJSONArray("punkte")
            assertEquals(2, p.length())
            assertEquals("2025-11-02T07:55:00.000Z", p.getJSONObject(0).getString("zeit"))
            assertEquals(52.52, p.getJSONObject(0).getDouble("breite"), 0.0)
            assertEquals(13.405, p.getJSONObject(0).getDouble("laenge"), 0.0)
            assertEquals(12.5, p.getJSONObject(0).getDouble("genauigkeit"), 0.0)
            assertTrue(p.getJSONObject(0).has("hoehe") && p.getJSONObject(0).has("tempo"))
            assertTrue(!p.getJSONObject(1).has("hoehe") && !p.getJSONObject(1).has("tempo"))
            assertEquals(Ergebnis.Behalten(409, "kein_schluessel", "Verbinde zuerst Reitti."), c.senden(zwei))
            assertEquals(Ergebnis.Behalten(503, "reitti_weg", "Reitti nimmt gerade nichts an."), c.senden(zwei))
            assertTrue("a 2xx without angenommen keeps", c.senden(zwei) is Ergebnis.Behalten)
        } finally {
            server.shutdown()
        }
        assertEquals(Ergebnis.NichtGekoppelt, StandortClient.ergebnisAus(401, """{"error":"unauthorized"}"""))
        assertEquals(StandortClient.Verbindung.Verbunden("2026-11-02T09:00:00Z"), StandortClient.verbindungAus(200, """{"verbunden":true,"seit":"2026-11-02T09:00:00Z"}"""))
        assertEquals(StandortClient.Verbindung.NichtVerbunden, StandortClient.verbindungAus(200, """{"verbunden":false}"""))
        assertEquals(
            StandortClient.Verbindung.Fehler("Der Standortverlauf (Reitti) ist auf dieser Box nicht eingerichtet."),
            StandortClient.verbindungAus(503, """{"fehler":"Der Standortverlauf (Reitti) ist auf dieser Box nicht eingerichtet.","grund":"nicht_eingerichtet"}"""),
        )
    }

    /** The point's keys are the ones Haus's `punkt` reads: internal/napi/standort.go:49-56 (haus 145ec05). */
    @Test
    fun thePointsKeysAreHauss() {
        val haus = setOf("zeit", "breite", "laenge", "genauigkeit", "hoehe", "tempo")
        val keys = Napi.PunktJson::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }.toSet()
        assertEquals(haus, keys)
        assertEquals(haus, StandortClient.json(Punkt(1L, 1.0, 2.0, 3f, 4.0, 5f)).keys().asSequence().toSet())
        assertTrue(Senden.JE_ANFRAGE <= 1000) // Haus takes 1000 a batch
    }
}
