package cloud.dopp.haus

import cloud.dopp.haus.data.Napi.Quelle
import cloud.dopp.haus.data.SicherungClient
import cloud.dopp.haus.sicherung.Gedaechtnis
import cloud.dopp.haus.sicherung.Gesendet
import cloud.dopp.haus.sicherung.Lauf
import cloud.dopp.haus.sicherung.Meldung
import cloud.dopp.haus.sicherung.Ordner
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
import java.io.File

/** [Gedaechtnis] in memory: what [cloud.dopp.haus.sicherung.SicherungStore] keeps in prefs. */
class MapGedaechtnis : Gedaechtnis {
    val gesendet = HashMap<Quelle, Gesendet>()
    val meldungen = HashMap<Quelle, Meldung>()
    val uebertragen = HashMap<Quelle, Pair<Long, Int>>()
    private val uploads = HashMap<String, String>()

    override fun gesendet(quelle: Quelle) = gesendet[quelle]
    override fun merkeGesendet(quelle: Quelle, g: Gesendet) { gesendet[quelle] = g }
    override fun merkeUebertragen(quelle: Quelle, zeit: Long, dateien: Int) { uebertragen[quelle] = zeit to dateien }
    override fun melde(quelle: Quelle, meldung: Meldung) { meldungen[quelle] = meldung }
    override fun upload(quelle: Quelle, pfad: String) = uploads["${quelle.wire}|$pfad"]
    override fun merkeUpload(quelle: Quelle, pfad: String, zeile: String) { uploads["${quelle.wire}|$pfad"] = zeile }
    override fun vergissUpload(quelle: Quelle, pfad: String) { uploads.remove("${quelle.wire}|$pfad") }
}

/**
 * One run of Nachrichten sichern (#194) over two folders on disk against the
 * fake Haus: what goes, what stays, what the screen is told — and that a run
 * without a granted folder asks Haus nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SicherungLaufTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var haus: FakeHaus
    private lateinit var whatsapp: DateiOrdner
    private lateinit var signal: DateiOrdner
    private val g = MapGedaechtnis()

    @Before
    fun auf() {
        haus = FakeHaus()
        whatsapp = DateiOrdner(File(tmp.root, "WhatsApp").apply { mkdirs() })
        signal = DateiOrdner(File(tmp.root, "Signal").apply { mkdirs() })
    }

    @After
    fun zu() = haus.schliessen()

    private fun lauf(ordner: (Quelle) -> Ordner? = { q -> if (q == Quelle.SIGNAL) signal else whatsapp }, halt: () -> Boolean = { false }) =
        Lauf(SicherungClient(haus.base, FakeHaus.TOKEN, haus.http), g, ordner, halt = halt, teil = 1024)

    private fun legeWhatsapp(): Map<String, ByteArray> {
        val dateien = mapOf(
            "Databases/msgstore.db.crypt15" to FakeHaus.bytes(5000, 1),
            "Databases/msgstore-2026-10-31.1.db.crypt15" to FakeHaus.bytes(4900, 2),
            "Media/WhatsApp Images/IMG-20261101-WA0001.jpg" to FakeHaus.bytes(3000, 3),
            "Media/WhatsApp Images/Sent/IMG-20261101-WA0002.jpg" to FakeHaus.bytes(2000, 4),
            "Media/WhatsApp Video/VID-20261101-WA0003.mp4" to FakeHaus.bytes(2500, 5),
            "Media/.Statuses/status.jpg" to FakeHaus.bytes(100, 6),
            "Media/WhatsApp Images/.nomedia" to FakeHaus.bytes(1, 7),
        )
        dateien.forEach { (p, b) -> whatsapp.lege(p, b) }
        return dateien
    }

    @Test
    fun withoutAGrantedFolderNothingIsAsked() {
        assertEquals(Lauf.Ergebnis.NICHTS, lauf(ordner = { null }).laufen())
        assertTrue(haus.anfragen.isEmpty())
    }

    @Test
    fun whatsappSendsTheDatabaseAndTheMissingMediaOnly() {
        val dateien = legeWhatsapp()
        haus.vorhanden += "whatsapp|Media/WhatsApp Video/VID-20261101-WA0003.mp4"
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(ordner = { q -> if (q == Quelle.WHATSAPP) whatsapp else null }).laufen())
        assertEquals(
            setOf(
                "whatsapp|Databases/msgstore.db.crypt15",
                "whatsapp|Media/WhatsApp Images/IMG-20261101-WA0001.jpg",
                "whatsapp|Media/WhatsApp Images/Sent/IMG-20261101-WA0002.jpg",
            ),
            haus.fertig.keys,
        )
        assertArrayEquals(dateien["Databases/msgstore.db.crypt15"], haus.fertig["whatsapp|Databases/msgstore.db.crypt15"])
        assertEquals(Meldung.Art.OK, g.meldungen[Quelle.WHATSAPP]?.art)
        assertEquals(3, g.uebertragen[Quelle.WHATSAPP]?.second)

        // the next run: the same msgstore is not sent again, and Haus has the media
        val vorher = haus.anfragen.size
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(ordner = { q -> if (q == Quelle.WHATSAPP) whatsapp else null }).laufen())
        val danach = haus.anfragen.drop(vorher)
        assertEquals(listOf("/napi/sicherung/fehlend"), danach.map { it.path })
    }

    @Test
    fun signalSendsTheNewestBackupOnceAndThenOnlyANewerOne() {
        val stunde = 60 * 60 * 1000L
        signal.lege("signal-2026-10-31-03-00-00.backup", FakeHaus.bytes(3000, 1), alterMs = 26 * stunde)
        val heute = FakeHaus.bytes(3500, 2)
        signal.lege("signal-2026-11-01-03-00-00.backup", heute, alterMs = 2 * stunde)
        val nurSignal: (Quelle) -> Ordner? = { q -> if (q == Quelle.SIGNAL) signal else null }

        assertEquals(Lauf.Ergebnis.FERTIG, lauf(nurSignal).laufen())
        assertEquals(setOf("signal|signal-2026-11-01-03-00-00.backup"), haus.fertig.keys)
        assertArrayEquals(heute, haus.fertig.values.single())

        val vorher = haus.anfragen.size
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(nurSignal).laufen())
        assertEquals("nothing new, nothing asked", vorher, haus.anfragen.size)

        signal.lege("signal-2026-11-02-03-00-00.backup", FakeHaus.bytes(3600, 3), alterMs = stunde)
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(nurSignal).laufen())
        assertTrue("signal|signal-2026-11-02-03-00-00.backup" in haus.fertig.keys)
        assertEquals(2, haus.fertig.size)
    }

    @Test
    fun aWithdrawnFolderSaysSoInGerman() {
        legeWhatsapp()
        whatsapp.entzogen = true
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(ordner = { q -> if (q == Quelle.WHATSAPP) whatsapp else null }).laufen())
        assertEquals(
            Meldung.Art.ORDNER to "WhatsApp-Ordner nicht mehr freigegeben: tippe hier, um ihn neu zu wählen.",
            g.meldungen[Quelle.WHATSAPP]!!.let { it.art to it.satz },
        )
        assertTrue(haus.anfragen.isEmpty())
    }

    @Test
    fun anEmptyFolderPointsToTheGuide() {
        assertEquals(Lauf.Ergebnis.FERTIG, lauf().laufen())
        assertEquals(Meldung.Art.KEINE, g.meldungen[Quelle.SIGNAL]?.art)
        assertEquals(Meldung.Art.KEINE, g.meldungen[Quelle.WHATSAPP]?.art)
    }

    @Test
    fun noArchiveOnTheBoxStopsWithHaussSentence() {
        legeWhatsapp()
        haus.keinArchiv = true
        assertEquals(Lauf.Ergebnis.HALT, lauf().laufen())
        assertEquals(
            Meldung.Art.HAUS to "Auf dieser Box ist noch kein Nachrichten-Archiv eingerichtet.",
            g.meldungen[Quelle.WHATSAPP]!!.let { it.art to it.satz },
        )
        assertTrue(haus.fertig.isEmpty())
    }

    @Test
    fun aFullBoxStopsTheRun() {
        legeWhatsapp()
        signal.lege("signal-2026-11-01-03-00-00.backup", FakeHaus.bytes(3500, 2))
        haus.voll = true
        assertEquals(Lauf.Ergebnis.HALT, lauf().laufen())
        assertEquals(Meldung.Art.HAUS, g.meldungen[Quelle.WHATSAPP]?.art)
        assertNull("Signal is not tried once the box is full", g.meldungen[Quelle.SIGNAL])
    }

    /** Stopped mid-run (WLAN gone): the next run goes on and sends nothing twice. */
    @Test
    fun aStoppedRunGoesOnNextTime() {
        val dateien = legeWhatsapp()
        var stop = true
        assertEquals(Lauf.Ergebnis.WEITER, lauf(halt = { stop && haus.patches().size >= 3 }).laufen())
        assertEquals(Meldung.Art.SPAETER, g.meldungen[Quelle.WHATSAPP]?.art)
        stop = false
        assertEquals(Lauf.Ergebnis.FERTIG, lauf().laufen())
        assertArrayEquals(dateien["Databases/msgstore.db.crypt15"], haus.fertig["whatsapp|Databases/msgstore.db.crypt15"])
        val gesendet = haus.patches().sumOf { it.laenge }
        val noetig = listOf(
            "Databases/msgstore.db.crypt15",
            "Media/WhatsApp Images/IMG-20261101-WA0001.jpg",
            "Media/WhatsApp Images/Sent/IMG-20261101-WA0002.jpg",
            "Media/WhatsApp Video/VID-20261101-WA0003.mp4",
        ).sumOf { dateien.getValue(it).size.toLong() }
        assertEquals("every byte once", noetig, gesendet)
    }

    /**
     * Haus refuses a whole `fehlend` for one dot-name (400): even from a folder
     * reader that lists them, no path with a dot-name at any level is asked
     * about or sent, and the run goes through.
     */
    @Test
    fun dotNamesAtAnyLevelAreNeverSent() {
        legeWhatsapp()
        whatsapp.lege("Media/WhatsApp Images/Sent/.thumbs/t.jpg", FakeHaus.bytes(50, 8))
        whatsapp.lege("Media/.trash/x.jpg", FakeHaus.bytes(50, 9))
        whatsapp.zeigtVersteckte = true
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(ordner = { q -> if (q == Quelle.WHATSAPP) whatsapp else null }).laufen())
        assertTrue(haus.fertig.keys.none { k -> k.substringAfter('|').split('/').any { it.startsWith(".") } })
        assertEquals(4, haus.fertig.size)
        assertEquals(Meldung.Art.OK, g.meldungen[Quelle.WHATSAPP]?.art)
    }

    /** A busy box (429) ends the run with "soon again"; the next run sends the rest. */
    @Test
    fun aBusyBoxEndsTheRunForNow() {
        legeWhatsapp()
        haus.beschaeftigt = 1
        assertEquals(Lauf.Ergebnis.BESCHAEFTIGT, lauf().laufen())
        assertEquals(Lauf.BESCHAEFTIGT, g.meldungen[Quelle.WHATSAPP]?.satz)
        assertEquals(Lauf.Ergebnis.FERTIG, lauf().laufen())
        assertEquals(4, haus.fertig.count { it.key.startsWith("whatsapp|") })
    }

    @Test
    fun aDeviceOfNobodyStopsWithHaussSentence() {
        assertEquals(
            "Dieses Gerät gehört zu keiner Person: kopple die Haus-App neu, angemeldet mit deinem Namen.",
            Lauf.satzFuer(403, null),
        )
        assertEquals(Lauf.NICHT_GEKOPPELT, Lauf.satzFuer(401, "unauthorized"))
    }

    @Test
    fun haussUnreachableWaitsForTheNextRun() {
        legeWhatsapp()
        val base = haus.base // read before the shutdown: MockWebServer starts again on url()
        haus.schliessen()
        val lauf = Lauf(SicherungClient(base, FakeHaus.TOKEN, haus.http), g, { q -> if (q == Quelle.SIGNAL) signal else whatsapp }, teil = 1024)
        assertEquals(Lauf.Ergebnis.UNERREICHBAR, lauf.laufen())
        assertEquals(Lauf.UNERREICHBAR, g.meldungen[Quelle.WHATSAPP]?.satz)
    }
}
