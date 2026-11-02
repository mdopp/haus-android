package cloud.dopp.haus

import cloud.dopp.haus.data.Napi.Quelle
import cloud.dopp.haus.data.SicherungClient
import cloud.dopp.haus.sicherung.Datei
import cloud.dopp.haus.sicherung.Uploader
import cloud.dopp.haus.sicherung.Uploader.Ausgang
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The resumable upload (#194) against a fake Haus that keeps the contract:
 * a file goes in pieces, and after any interruption it goes on where Haus
 * stands — a connection cut mid-piece, a write Haus lost (409), an upload Haus
 * forgot (404) — and stops with Haus's sentence when the box is full.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SicherungUploadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var haus: FakeHaus
    private lateinit var ordner: DateiOrdner
    private val merker = MapGedaechtnis()

    private fun client() = SicherungClient(haus.base, FakeHaus.TOKEN, haus.http)

    private fun uploader(teil: Int = TEIL) = Uploader(client(), merker, teil)

    @Before
    fun auf() {
        haus = FakeHaus()
        ordner = DateiOrdner(tmp.root)
    }

    @After
    fun zu() = haus.schliessen()

    private fun datei(n: Int, pfad: String = "signal-2026-11-01-03-00-00.backup"): Pair<Datei, ByteArray> {
        val inhalt = FakeHaus.bytes(n)
        val f = ordner.lege(pfad, inhalt)
        return Datei(pfad, f.length(), f.lastModified()) to inhalt
    }

    @Test
    fun aFileGoesInPiecesAndArrivesWhole() {
        val (d, inhalt) = datei(5 * TEIL + 123)
        assertEquals(Ausgang.Fertig, uploader().hochladen(Quelle.SIGNAL, ordner, d))
        assertArrayEquals(inhalt, haus.fertig.getValue("signal|${d.pfad}"))
        val patches = haus.patches()
        assertEquals(6, patches.size)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L).map { (it * TEIL).toString() }, patches.map { it.offset })
        patches.forEach {
            assertEquals("application/offset+octet-stream", it.typ)
            assertEquals("Bearer haus_device_TEST", it.auth)
            assertTrue(it.laenge <= TEIL)
        }
        assertNull("a finished upload is forgotten", merker.upload(Quelle.SIGNAL, d.pfad))
    }

    /** The piece is 8 MiB on the phone; Haus takes up to 16. */
    @Test
    fun aPieceIsEightMebibytes() {
        assertEquals(8 * 1024 * 1024, Uploader.TEIL)
        val (d, inhalt) = datei(Uploader.TEIL * 2 + 5)
        assertEquals(Ausgang.Fertig, Uploader(client(), merker).hochladen(Quelle.SIGNAL, ordner, d))
        assertEquals(listOf(Uploader.TEIL.toLong(), Uploader.TEIL.toLong(), 5L), haus.patches().map { it.laenge })
        assertArrayEquals(inhalt, haus.fertig.getValue("signal|${d.pfad}"))
    }

    /** WLAN gone mid-piece: Haus kept half of it; the next run asks where Haus stands and goes on from there. */
    @Test
    fun aCutMidFileGoesOnWhereHausStands() {
        val (d, inhalt) = datei(6 * TEIL)
        haus.schneideNach = TEIL / 2
        var gesendet = 0
        val erster = uploader().hochladen(Quelle.SIGNAL, ordner, d, halt = { false }) { gesendet++ }
        // the first piece is cut: no answer, the run ends there
        assertEquals(Ausgang.Fehler(0, null), erster)
        val id = haus.uploads.keys.single()
        assertEquals((TEIL / 2).toLong(), haus.uploads.getValue(id).offset)
        assertTrue("the id outlives the run", merker.upload(Quelle.SIGNAL, d.pfad)!!.endsWith("|$id"))

        val vorher = haus.anfragen.size
        assertEquals(Ausgang.Fertig, uploader().hochladen(Quelle.SIGNAL, ordner, d))
        val danach = haus.anfragen.drop(vorher)
        assertEquals("the next run asks first", "HEAD", danach.first().method)
        assertEquals((TEIL / 2).toString(), danach[1].offset)
        assertTrue("no new upload", danach.none { it.method == "POST" })
        assertArrayEquals(inhalt, haus.fertig.getValue("signal|${d.pfad}"))
    }

    /** Haus lost the last write: the PATCH gets 409 and the upload goes on from Haus's offset, in the same run. */
    @Test
    fun aConflictGoesOnFromHaussOffset() {
        val (d, inhalt) = datei(4 * TEIL + 77)
        val u = uploader()
        var stueck = 0
        haus.verliere = null
        val a = u.hochladen(Quelle.SIGNAL, ordner, d, halt = {
            stueck++
            if (stueck == 3) haus.verliere = TEIL + TEIL / 3 // before the third piece
            false
        })
        assertEquals(Ausgang.Fertig, a)
        val p = haus.patches()
        assertTrue("a 409 came", p.zip(p.drop(1)).any { (x, y) -> y.offset!!.toLong() < x.offset!!.toLong() + TEIL })
        assertArrayEquals(inhalt, haus.fertig.getValue("signal|${d.pfad}"))
    }

    /** Haus forgot the partial upload: HEAD answers 404 and the upload starts anew. */
    @Test
    fun aForgottenUploadStartsAnew() {
        val (d, inhalt) = datei(3 * TEIL)
        assertEquals(Ausgang.Angehalten, uploader().hochladen(Quelle.SIGNAL, ordner, d, halt = { haus.patches().size >= 2 }))
        haus.vergiss()
        val vorher = haus.anfragen.size
        assertEquals(Ausgang.Fertig, uploader().hochladen(Quelle.SIGNAL, ordner, d))
        val danach = haus.anfragen.drop(vorher).map { it.method }
        assertEquals(listOf("HEAD", "POST", "PATCH", "PATCH", "PATCH"), danach)
        assertArrayEquals(inhalt, haus.fertig.getValue("signal|${d.pfad}"))
    }

    /** Forgotten in the middle of a run: the PATCH answers 404 and a new upload begins at once. */
    @Test
    fun aPatchToAForgottenUploadStartsAnew() {
        val (d, inhalt) = datei(3 * TEIL)
        var n = 0
        val a = uploader().hochladen(Quelle.SIGNAL, ordner, d, halt = {
            if (++n == 2) haus.vergiss()
            false
        })
        assertEquals(Ausgang.Fertig, a)
        assertEquals(2, haus.anfragen.count { it.method == "POST" })
        assertArrayEquals(inhalt, haus.fertig.getValue("signal|${d.pfad}"))
    }

    /** Stopped between two pieces (constraints lost): the next run goes on, nothing is sent twice. */
    @Test
    fun aStopEndsBetweenTwoPiecesAndTheNextRunGoesOn() {
        val (d, inhalt) = datei(4 * TEIL)
        assertEquals(Ausgang.Angehalten, uploader().hochladen(Quelle.SIGNAL, ordner, d, halt = { haus.patches().size >= 2 }))
        assertEquals(Ausgang.Fertig, uploader().hochladen(Quelle.SIGNAL, ordner, d))
        assertEquals(4, haus.patches().size)
        assertArrayEquals(inhalt, haus.fertig.getValue("signal|${d.pfad}"))
    }

    /** A remembered id for another version of the file (other size or time) is not resumed. */
    @Test
    fun aChangedFileIsNotResumedOnTheOldId() {
        val (d, _) = datei(3 * TEIL)
        assertEquals(Ausgang.Angehalten, uploader().hochladen(Quelle.SIGNAL, ordner, d, halt = { haus.patches().isNotEmpty() }))
        val (d2, inhalt2) = datei(2 * TEIL)
        val vorher = haus.anfragen.size
        assertEquals(Ausgang.Fertig, uploader().hochladen(Quelle.SIGNAL, ordner, d2))
        assertTrue("no HEAD on the old id", haus.anfragen.drop(vorher).none { it.method == "HEAD" })
        assertArrayEquals(inhalt2, haus.fertig.getValue("signal|${d2.pfad}"))
    }

    /**
     * The last piece arrived but its answer was lost: Haus keeps the finished
     * upload known, HEAD says offset = size, and that is done — nothing is sent again.
     */
    @Test
    fun aLostLastAnswerIsDoneAfterHead() {
        val (d, inhalt) = datei(3 * TEIL)
        var n = 0
        val erster = uploader().hochladen(Quelle.SIGNAL, ordner, d, halt = {
            if (++n == 3) haus.schneideNach = TEIL // the whole last piece lands, the answer does not
            false
        })
        assertEquals(Ausgang.Fehler(0, null), erster)
        assertArrayEquals("Haus has it whole", inhalt, haus.fertig.getValue("signal|${d.pfad}"))

        val vorher = haus.anfragen.size
        assertEquals(Ausgang.Fertig, uploader().hochladen(Quelle.SIGNAL, ordner, d))
        assertEquals("one HEAD, no piece again", listOf("HEAD"), haus.anfragen.drop(vorher).map { it.method })
        assertNull(merker.upload(Quelle.SIGNAL, d.pfad))
    }

    /** A busy box (429) is no answer about the file: the run ends and the next one goes on, nothing lost. */
    @Test
    fun aBusyBoxIsTriedAgainLater() {
        val (d, inhalt) = datei(3 * TEIL)
        haus.beschaeftigt = 1
        assertEquals(Ausgang.Fehler(429, "Die Box nimmt gerade schon andere Sicherungen an. Versuch es gleich noch einmal."), uploader().hochladen(Quelle.SIGNAL, ordner, d))
        assertEquals(Ausgang.Fertig, uploader().hochladen(Quelle.SIGNAL, ordner, d))
        assertArrayEquals(inhalt, haus.fertig.getValue("signal|${d.pfad}"))
    }

    @Test
    fun aFullBoxStopsWithHaussSentence() {
        val (d, _) = datei(2 * TEIL)
        haus.voll = true
        assertEquals(Ausgang.Voll("Auf der Box ist kein Platz mehr frei."), uploader().hochladen(Quelle.SIGNAL, ordner, d))
        assertEquals(1, haus.patches().size)
    }

    @Test
    fun theReplyShapesReadAsTheContractSaysThem() {
        assertEquals(SicherungClient.Teil.Weiter(8, true), SicherungClient.teilAus(200, """{"offset":8,"fertig":true}"""))
        assertEquals(SicherungClient.Teil.Versatz(3), SicherungClient.teilAus(409, """{"fehler":"x","offset":3}"""))
        // a cut while Haus wrote: 408 with how far it got
        assertEquals(SicherungClient.Teil.Versatz(5), SicherungClient.teilAus(408, """{"fehler":"x","offset":5}"""))
        assertEquals(SicherungClient.Teil.Unbekannt, SicherungClient.teilAus(404, """{"fehler":"x"}"""))
        assertEquals(SicherungClient.Teil.Voll("Voll."), SicherungClient.teilAus(507, """{"fehler":"Voll."}"""))
        val stand = SicherungClient.standAus(
            200,
            """{"quellen":{"whatsapp":{"zuletzt":"2026-11-01T03:12:00Z","dateien":2,"bytes":30,"wartend":1},"signal":{"zuletzt":null,"dateien":0,"bytes":0,"wartend":0}},"frei_bytes":99}""",
        ) as SicherungClient.Stand.Da
        assertEquals(SicherungClient.QuelleStand("2026-11-01T03:12:00Z", 2, 30, 1), stand.quellen[Quelle.WHATSAPP])
        assertEquals(SicherungClient.QuelleStand(null, 0, 0, 0), stand.quellen[Quelle.SIGNAL])
        assertEquals(99L, stand.freiBytes)
        assertEquals(
            SicherungClient.Stand.Fehler("Kein Archiv."),
            SicherungClient.standAus(503, """{"fehler":"Kein Archiv."}"""),
        )
        assertEquals(SicherungClient.Stand.NichtGekoppelt, SicherungClient.standAus(401, """{"error":"unauthorized"}"""))
    }

    /** One dot-name in one path fails Haus's whole question: the client refuses to ask it. */
    @Test
    fun noDotNameIsAskedAbout() {
        val c = client()
        listOf("Media/.Statuses/x.jpg", ".nomedia", "Media/WhatsApp Images/Sent/.x/IMG.jpg").forEach { p ->
            val e = runCatching { c.fehlend(Quelle.WHATSAPP, listOf("Media/a.jpg" to 1L, p to 1L)) }.exceptionOrNull()
            assertTrue("$p: $e", e is IllegalArgumentException)
        }
        assertTrue("nothing went out", haus.anfragen.isEmpty())
        assertEquals(setOf("Media/a.jpg"), c.fehlend(Quelle.WHATSAPP, listOf("Media/a.jpg" to 1L)))
    }

    @Test
    fun standAgainstTheFakeHaus() {
        val s = client().stand() as SicherungClient.Stand.Da
        assertEquals(setOf(Quelle.WHATSAPP, Quelle.SIGNAL, Quelle.DISCORD, Quelle.SMS), s.quellen.keys)
        haus.keinArchiv = true
        assertEquals(
            SicherungClient.Stand.Fehler("Auf dieser Box ist noch kein Nachrichten-Archiv eingerichtet."),
            client().stand(),
        )
    }

    private companion object {
        /** Small pieces in the tests; [aPieceIsEightMebibytes] holds the real size. */
        const val TEIL = 1024
    }
}
