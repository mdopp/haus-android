package cloud.dopp.haus

import cloud.dopp.haus.data.Napi.Quelle
import cloud.dopp.haus.data.SicherungClient
import cloud.dopp.haus.sicherung.Adressbuch
import cloud.dopp.haus.sicherung.Art
import cloud.dopp.haus.sicherung.Auszug
import cloud.dopp.haus.sicherung.Kontakt
import cloud.dopp.haus.sicherung.KontakteLauf
import cloud.dopp.haus.sicherung.Kontaktgedaechtnis
import cloud.dopp.haus.sicherung.Lauf
import cloud.dopp.haus.sicherung.Marken
import cloud.dopp.haus.sicherung.Meldung
import cloud.dopp.haus.sicherung.NachrichtenLauf
import cloud.dopp.haus.sicherung.SicherungStore
import cloud.dopp.haus.sicherung.Telefon
import cloud.dopp.haus.sicherung.Vcard
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
import java.io.ByteArrayInputStream
import java.io.File

/** [Marken] in memory. */
class MapMarken : Marken {
    val marken = HashMap<Art, Long>()
    val ausstehend = HashMap<String, Map<Art, Long>>()
    override fun marke(art: Art) = marken[art] ?: 0L
    override fun ausstehend(name: String) = ausstehend[name]
    override fun merkeAusstehend(name: String, marken: Map<Art, Long>?) {
        if (marken == null) ausstehend.remove(name) else ausstehend[name] = marken
    }
    override fun setzeMarke(art: Art, wert: Long) { marken[art] = wert }
}

/**
 * Kontakte, SMS und Anrufe (laib ADR 0029) against the fake Haus: the
 * contacts go whole as vCard 3.0 with UIDs, none means no request; the SMS
 * and calls go age-encrypted as `quelle` `sms`, readable with the archive's
 * identity, and the marks move only once a file is on the box.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandyLaufTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var haus: FakeHaus
    private val g = MapGedaechtnis()
    private val m = MapMarken()
    private val identitaet = AgeLeser.identitaet("AGE-SECRET-KEY-1EGTZVFFV20835NWYV6270LXYVK2VKNX2MMDKWYKLMGR48UAWX40Q2P2LM0")

    @Before
    fun auf() {
        haus = FakeHaus()
    }

    @After
    fun zu() = haus.schliessen()

    private fun client() = SicherungClient(haus.base, FakeHaus.TOKEN, haus.http)

    // --- contacts ---------------------------------------------------------------

    private class Kg : Kontaktgedaechtnis {
        var meldung: Meldung? = null
        var ergebnis: SicherungClient.Kontakte? = null
        override fun meldeKontakte(meldung: Meldung) { this.meldung = meldung }
        override fun merkeKontakte(zeit: Long, ergebnis: SicherungClient.Kontakte) { this.ergebnis = ergebnis }
    }

    private class Buch(val kontakte: List<Kontakt>) : Adressbuch {
        override fun exportieren(datei: File): Int {
            datei.writeText(kontakte.joinToString("") { Vcard.karte(it) })
            return kontakte.size
        }
    }

    @Test
    fun theContactsGoWholeAsVcardsWithUids() {
        val kg = Kg()
        val ordner = tmp.newFolder("kontakte")
        val buch = Buch(listOf(Kontakt(uid = "a1", anzeigename = "Anna"), Kontakt(uid = "b2", anzeigename = "Ben")))
        assertEquals(Lauf.Ergebnis.FERTIG, KontakteLauf(client(), kg, buch, ordner).laufen())
        val (typ, text) = haus.kontakte.single()
        assertEquals("text/vcard", typ)
        assertEquals(2, Vcard.anzahl(text))
        assertTrue(text.contains("VERSION:3.0\r\nUID:a1\r\n"))
        assertEquals(SicherungClient.Kontakte(2, 2, 0, 0), kg.ergebnis)
        assertEquals(Meldung.Art.OK, kg.meldung?.art)
        assertTrue("the file is gone", ordner.listFiles()!!.isEmpty())
    }

    @Test
    fun noContactsNoRequest() {
        val kg = Kg()
        assertEquals(Lauf.Ergebnis.FERTIG, KontakteLauf(client(), kg, Buch(emptyList()), tmp.newFolder()).laufen())
        assertTrue(haus.anfragen.isEmpty())
        assertEquals(KontakteLauf.KEINE, kg.meldung?.satz)
    }

    // --- SMS and calls ----------------------------------------------------------

    /** A phone with [sms] (date → text) and [anrufe] (dates); writes what is newer than the marks. */
    private class FakeTelefon(val sms: Map<Long, String>, val anrufe: List<Long>) : Telefon {
        val gefragt = ArrayList<Long>()
        override fun nachrichten(datei: File, seitSms: Long, seitMms: Long): Auszug? {
            gefragt += seitSms
            val neu = sms.filterKeys { it > seitSms }
            if (neu.isEmpty()) return null
            datei.writeText("<smses count=\"${neu.size}\">" + neu.entries.joinToString("") { "<sms date=\"${it.key}\" body=\"${it.value}\" />" } + "</smses>")
            return Auszug(neu.size, mapOf(Art.SMS to neu.keys.max(), Art.MMS to seitMms))
        }
        override fun anrufe(datei: File, seit: Long): Auszug? {
            val neu = anrufe.filter { it > seit }
            if (neu.isEmpty()) return null
            datei.writeText("<calls count=\"${neu.size}\">" + neu.joinToString("") { "<call date=\"$it\" />" } + "</calls>")
            return Auszug(neu.size, mapOf(Art.ANRUFE to neu.max()))
        }
    }

    private fun lauf(t: Telefon, ordner: File, uhr: Long = 1_762_050_000_000) =
        NachrichtenLauf(client(), g, m, t, ordner, uhr = { uhr }, teil = 1024)

    private fun klar(b: ByteArray) = String(AgeLeser.entschluesseln(identitaet, ByteArrayInputStream(b)), Charsets.UTF_8)

    @Test
    fun smsAndCallsGoEncryptedAndTheMarksMoveAfterwards() {
        val ordner = tmp.newFolder("sms")
        val t = FakeTelefon(mapOf(100L to "eins", 200L to "zwei"), listOf(150L))
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(t, ordner).laufen())
        assertEquals(setOf("sms|sms-20251102T022000Z.xml.age", "sms|anrufe-20251102T022000Z.xml.age"), haus.fertig.keys)
        val sms = klar(haus.fertig.getValue("sms|sms-20251102T022000Z.xml.age"))
        assertTrue(sms, sms.contains("body=\"zwei\""))
        assertTrue(klar(haus.fertig.getValue("sms|anrufe-20251102T022000Z.xml.age")).startsWith("<calls"))
        assertEquals(200L, m.marke(Art.SMS))
        assertEquals(150L, m.marke(Art.ANRUFE))
        assertTrue("nothing left on the phone", ordner.listFiles()!!.isEmpty())
        assertTrue(m.ausstehend.isEmpty())
        assertEquals(Meldung.Art.OK, g.meldungen[Quelle.SMS]?.art)
        assertEquals(2, g.uebertragen[Quelle.SMS]?.second)

        // the next run: nothing new, nothing asked, not even the key
        val vorher = haus.anfragen.size
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(t, ordner).laufen())
        assertEquals(vorher, haus.anfragen.size)
    }

    @Test
    fun aCutUploadKeepsTheMarksAndTheNextRunGoesOnWithTheSameFile() {
        val ordner = tmp.newFolder("sms")
        val t = FakeTelefon((1L..400L).associateWith { "Nachricht Nummer $it, etwas länger" }, emptyList())
        haus.schneideNach = 100
        assertEquals(Lauf.Ergebnis.UNERREICHBAR, lauf(t, ordner).laufen())
        assertEquals("the mark waits for the file", 0L, m.marke(Art.SMS))
        val unterwegs = ordner.listFiles()!!.map { it.name }
        assertEquals(listOf("sms-20251102T022000Z.xml.age"), unterwegs)
        val datei = File(ordner, unterwegs.single()).readBytes()

        // a later run, a later clock: it sends the file under way, not a new one
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(t, ordner, uhr = 1_762_060_000_000).laufen())
        assertArrayEquals(datei, haus.fertig.getValue("sms|sms-20251102T022000Z.xml.age"))
        assertEquals(400L, m.marke(Art.SMS))
        assertEquals("written once", 1, t.gefragt.count { it == 0L })
        assertTrue(klar(datei).contains("Nachricht Nummer 400"))
    }

    @Test
    fun withoutAKeyNothingGoesAndHaussSentenceIsShown() {
        val ordner = tmp.newFolder("sms")
        haus.empfaenger = null
        assertEquals(Lauf.Ergebnis.HALT, lauf(FakeTelefon(mapOf(1L to "x"), emptyList()), ordner).laufen())
        assertEquals(FakeHaus.KEIN_SCHLUESSEL, g.meldungen[Quelle.SMS]?.satz)
        assertTrue(haus.patches().isEmpty())
        assertTrue("no plaintext stays", ordner.listFiles()!!.isEmpty())
        assertEquals(0L, m.marke(Art.SMS))
    }

    @Test
    fun aFileNoRecordKnowsIsDropped() {
        val ordner = tmp.newFolder("sms")
        File(ordner, "sms-20250101T000000Z.xml.age").writeText("alt")
        File(ordner, "20250101T000000Z.xml").writeText("<smses/>")
        assertEquals(Lauf.Ergebnis.FERTIG, lauf(FakeTelefon(emptyMap(), emptyList()), ordner).laufen())
        assertTrue(ordner.listFiles()!!.isEmpty())
        assertTrue(haus.anfragen.isEmpty())
    }

    @Test
    fun theStoreKeepsWhatIsUnderWay() {
        val z = SicherungStore.ausstehendZeile(mapOf(Art.MMS to 5L, Art.SMS to 7L))
        assertEquals("sms=7;mms=5", z)
        assertEquals(mapOf(Art.SMS to 7L, Art.MMS to 5L), SicherungStore.ausstehendAus(z))
        assertNull(SicherungStore.ausstehendAus("kaputt"))
        assertNull(SicherungStore.ausstehendAus(null))
    }
}
