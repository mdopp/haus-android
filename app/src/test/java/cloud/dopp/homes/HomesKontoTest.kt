package cloud.dopp.homes

import cloud.dopp.homes.konto.Abgleich
import cloud.dopp.homes.konto.HomesKonto
import cloud.dopp.homes.konto.IcalLeser
import cloud.dopp.homes.konto.KalenderAbgleich
import cloud.dopp.homes.konto.KontakteAbgleich
import cloud.dopp.homes.konto.VcardLeser
import cloud.dopp.homes.sicherung.Adresse
import cloud.dopp.homes.sicherung.Eintrag
import android.provider.ContactsContract.CommonDataKinds.Phone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Das Homes-Konto (#206): the readers of what Homes sends
 * (`/napi/gemeinsam/…`, homes#218), the plan of one sync, and the lookup key
 * a joined contact keeps in the archive. Pure, no Android.
 */
class HomesKontoTest {

    // --- vCard ---------------------------------------------------------------------

    @Test
    fun aCardFromRadicaleIsReadAsThePhoneKeepsIt() {
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:oma-1\r\nFN:Oma Erna\r\nN:Müller;Erna;;Dr.;\r\n" +
            "TEL;TYPE=CELL:+49 170 1234567\r\nitem1.TEL;TYPE=\"home,voice\":+49 89 123\r\nTEL;WORK;FAX:+49 89 999\r\n" +
            "EMAIL;TYPE=INTERNET,HOME:erna@example.org\r\n" +
            "ADR;TYPE=HOME:;;Hauptstr. 1\\, Hinterhaus;München;Bayern;80331;Deutschland\r\n" +
            "ORG:Müller GmbH;Einkauf\r\nTITLE:Chefin\r\nBDAY:19500102\r\nNOTE:Zeile eins\\nZeile zwei\\; mit Strichpunkt\r\n" +
            "URL:https://example.org\r\nEND:VCARD\r\n" +
            // vCard 4.0 as Homes writes one, folded
            "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:urn:uuid:abc\r\nFN:Ein sehr langer Name der über die fünfundsiebzig Zeiche\r\n n hinausgeht\r\n" +
            "TEL;VALUE=uri;TYPE=cell:tel:+4915112345\r\nBDAY:--0315\r\nEND:VCARD\r\n" +
            // no UID: the phone could not tell it from the next
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ohne UID\r\nEND:VCARD\r\n"
        val karten = VcardLeser.lies(text)
        assertEquals(2, karten.size)
        val oma = karten[0].kontakt
        assertEquals("oma-1", oma.uid)
        assertEquals("Oma Erna", oma.anzeigename)
        assertEquals("Müller", oma.nachname)
        assertEquals("Erna", oma.vorname)
        assertEquals("Dr.", oma.praefix)
        assertEquals(
            listOf(Eintrag("+49 170 1234567", listOf("cell")), Eintrag("+49 89 123", listOf("home", "voice")), Eintrag("+49 89 999", listOf("work", "fax"))),
            oma.telefone,
        )
        assertEquals(listOf(Eintrag("erna@example.org", listOf("home"))), oma.mails)
        assertEquals(
            listOf(Adresse(listOf("home"), null, null, "Hauptstr. 1, Hinterhaus", "München", "Bayern", "80331", "Deutschland")),
            oma.adressen,
        )
        assertEquals("Müller GmbH", oma.firma)
        assertEquals("Einkauf", oma.abteilung)
        assertEquals("Chefin", oma.titel)
        assertEquals("1950-01-02", oma.geburtstag)
        assertEquals("Zeile eins\nZeile zwei; mit Strichpunkt", oma.notiz)
        assertEquals(listOf(Eintrag("https://example.org")), oma.webseiten)
        val lang = karten[1].kontakt
        assertEquals("Ein sehr langer Name der über die fünfundsiebzig Zeichen hinausgeht", lang.anzeigename)
        assertEquals(listOf(Eintrag("+4915112345", listOf("cell"))), lang.telefone)
        assertEquals("--03-15", lang.geburtstag)
    }

    @Test
    fun phoneTypesComeBackAsAndroidsOwn() {
        assertEquals(Phone.TYPE_MOBILE, KontakteAbgleich.telefonTyp(listOf("cell", "voice")))
        assertEquals(Phone.TYPE_WORK_MOBILE, KontakteAbgleich.telefonTyp(listOf("work", "cell")))
        assertEquals(Phone.TYPE_FAX_WORK, KontakteAbgleich.telefonTyp(listOf("work", "fax")))
        assertEquals(Phone.TYPE_HOME, KontakteAbgleich.telefonTyp(listOf("home", "voice")))
        assertEquals(Phone.TYPE_OTHER, KontakteAbgleich.telefonTyp(emptyList()))
    }

    // --- iCalendar -----------------------------------------------------------------

    /** What homes#218's FeedICS writes, by its test's shapes. */
    private val feed = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//mdopp//Homes Haushalt//DE\r\nX-WR-CALNAME:Urlaub\\, Reisen\r\n" +
        "X-HAUS-KALENDER:familie-urlaub\r\nX-HAUS-ART:familie\r\n" +
        "BEGIN:VEVENT\r\nUID:see\r\nDTSTAMP:20261103T000000Z\r\nDTSTART;VALUE=DATE:20261220\r\nDTEND;VALUE=DATE:20261223\r\n" +
        "SUMMARY:Am See\r\nLOCATION:Ammersee\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n" +
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-CALNAME:Haushalt\r\nX-HAUS-KALENDER:haushalt\r\nX-HAUS-ART:haushalt\r\n" +
        "BEGIN:VEVENT\r\nUID:chor\r\nRECURRENCE-ID:20261110T180000Z\r\nDTSTART:20261110T180000Z\r\nDTEND:20261110T193000Z\r\n" +
        "SUMMARY:Chor\r\nDESCRIPTION:Noten\\nmitbringen\r\nEND:VEVENT\r\n" +
        "BEGIN:VEVENT\r\nUID:chor\r\nRECURRENCE-ID:20261117T180000Z\r\nDTSTART:20261117T180000Z\r\nDTEND:20261117T193000Z\r\n" +
        "SUMMARY:Chor\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n" +
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-CALNAME:Erinnerungen\r\nX-HAUS-KALENDER:erinnerungen\r\nX-HAUS-ART:erinnerungen\r\n" +
        "BEGIN:VEVENT\r\nUID:arzt\r\nDTSTART:20261104T080000Z\r\nDTEND:20261104T080000Z\r\nSUMMARY:Arzt anrufen\r\n" +
        "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Arzt anrufen\r\nTRIGGER:PT0S\r\nEND:VALARM\r\nEND:VEVENT\r\n" +
        "BEGIN:VEVENT\r\nUID:blumen\r\nDTSTART;TZID=Europe/Berlin:20261106T180000\r\nDTEND;TZID=Europe/Berlin:20261106T180000\r\n" +
        "RRULE:FREQ=WEEKLY\r\nSUMMARY:Blumen gießen\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\nEND:VEVENT\r\n" +
        "END:VCALENDAR\r\n"

    @Test
    fun theFeedIsReadIntoCalendarsAndEntries() {
        val kal = IcalLeser.lies(feed)
        assertEquals(listOf("familie-urlaub", "haushalt", "erinnerungen"), kal.map { it.id })
        assertEquals("Urlaub, Reisen", kal[0].name)
        assertEquals("familie", kal[0].art)

        val see = kal[0].termine.single()
        assertTrue(see.ganztags)
        assertEquals("UTC", see.zone)
        assertEquals(utc(2026, 12, 20), see.beginn)
        assertEquals(utc(2026, 12, 23), see.ende)
        assertEquals("Ammersee", see.ort)
        assertTrue(see.alarme.isEmpty())

        val chor = kal[1].termine
        assertEquals(listOf("chor#20261110T180000Z", "chor#20261117T180000Z"), chor.map { it.syncId })
        assertEquals(90 * 60 * 1000L, chor[0].ende - chor[0].beginn)
        assertEquals("Noten\nmitbringen", chor[0].beschreibung)

        val (arzt, blumen) = kal[2].termine
        assertEquals("arzt", arzt.syncId)
        assertEquals(listOf(0), arzt.alarme)
        assertEquals(arzt.beginn, arzt.ende)
        assertEquals("Europe/Berlin", blumen.zone)
        assertEquals("FREQ=WEEKLY", blumen.rrule)
        assertEquals(listOf(15), blumen.alarme)
        // 18:00 in Berlin on 6 November (CET) is 17:00 UTC
        assertEquals(utc(2026, 11, 6) + 17 * 3600_000L, blumen.beginn)
        assertEquals("PT0S", KalenderAbgleich.dauer(blumen))
        assertEquals("P3D", KalenderAbgleich.dauer(see))
    }

    @Test
    fun aCalendarWithoutItsIdOrAnEntryWithoutAStartIsLeftOut() {
        val text = "BEGIN:VCALENDAR\r\nX-WR-CALNAME:Fremd\r\nBEGIN:VEVENT\r\nUID:x\r\nDTSTART:20261104T080000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n" +
            "BEGIN:VCALENDAR\r\nX-HAUS-KALENDER:haushalt\r\nBEGIN:VEVENT\r\nUID:ohne-start\r\nSUMMARY:?\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val kal = IcalLeser.lies(text)
        assertEquals(1, kal.size)
        assertEquals("haushalt", kal[0].name)
        assertTrue(kal[0].termine.isEmpty())
    }

    @Test
    fun theCalendarsComeInTheHousesOrder() {
        val kal = IcalLeser.lies(feed).sortedBy(KalenderAbgleich::reihe).map { it.id }
        assertEquals(listOf("haushalt", "familie-urlaub", "erinnerungen"), kal)
    }

    private fun utc(j: Int, m: Int, t: Int): Long =
        java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(j, m - 1, t) }.timeInMillis

    // --- one sync's plan -------------------------------------------------------------

    @Test
    fun theSyncAddsChangesAndRemovesExactlyWhatDiffers() {
        val vorhanden = listOf(
            Abgleich.Zeile(1, "a", "1"), // unchanged
            Abgleich.Zeile(2, "b", "1"), // changed
            Abgleich.Zeile(3, "c", "1"), // gone at Homes
            Abgleich.Zeile(4, "a", "1"), // a second row of a: goes
            Abgleich.Zeile(5, null, null), // not ours to keep
        )
        val plan = Abgleich.plane(vorhanden, mapOf("a" to "1", "b" to "2", "d" to "1")) { it }
        assertEquals(listOf("1"), plan.neu)
        assertEquals(listOf(2L to "2"), plan.geaendert)
        assertEquals(listOf(3L, 4L, 5L), plan.weg)
        assertTrue(Abgleich.plane(listOf(Abgleich.Zeile(1, "a", "1")), mapOf("a" to "1")) { it }.nichts)
    }

    // --- the lookup key a joined contact keeps ---------------------------------------

    @Test
    fun aJoinedContactKeepsTheKeyItHasWithoutTheSharedCard() {
        val q = HomesKonto.quelle("urn:uuid:oma")
        assertEquals(40, q.length)
        assertFalse(q.contains('.'))
        val homes = HomesKonto.schluesselTeil(q)
        val eigen = "0r1-4D2F4D2F.1473i63fbb30c8fa4bc5d"
        assertEquals(eigen, HomesKonto.ohneHomes("$eigen.$homes", listOf(q)))
        assertEquals(eigen, HomesKonto.ohneHomes("$homes.$eigen", listOf(q)))
        assertEquals("0r1-4D2F4D2F", HomesKonto.ohneHomes("0r1-4D2F4D2F.$homes", listOf(q)))
        // an escaped source id (a dot doubled) stays whole
        val escaped = "1473eab..cd"
        assertEquals(escaped, HomesKonto.ohneHomes("$escaped.$homes", listOf(q)))
        // a contact without a shared card is untouched
        assertEquals(eigen, HomesKonto.ohneHomes(eigen, emptyList()))
        // another account's part with the same id is not ours (4096: no 12-bit hash)
        assertEquals("$eigen.4096i$q", HomesKonto.ohneHomes("$eigen.4096i$q.$homes", listOf(q)))
    }
}
