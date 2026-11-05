package cloud.dopp.homes

import android.app.Application
import android.content.SyncResult
import android.provider.CalendarContract.Calendars
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.GemeinsamClient.Antwort
import cloud.dopp.homes.konto.HomesKonto.Teil
import cloud.dopp.homes.konto.IcalLeser
import cloud.dopp.homes.konto.IcalSchreiber
import cloud.dopp.homes.konto.KalenderAbgleich
import cloud.dopp.homes.konto.KontoLauf
import cloud.dopp.homes.konto.KontoStore
import cloud.dopp.homes.konto.VcardLeser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * The writable Homes account (the owner's call of 2026-11-05, homes#278): the
 * feed a writing phone reads (series whole, alarms, each entry's version),
 * the appointment it sends back, and a sync that sends first and says when
 * Homes' version won.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomesKontoSchreibenTest {

    private val feed = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-CALNAME:Haushalt\r\nX-HAUS-KALENDER:haushalt\r\nX-HAUS-ART:haushalt\r\n" +
        "X-HOMES-SCHREIBBAR:TRUE\r\n" +
        "BEGIN:VEVENT\r\nUID:chor\r\nDTSTART;TZID=Europe/Berlin:20261102T093000\r\nDTEND;TZID=Europe/Berlin:20261102T103000\r\n" +
        "RRULE:FREQ=WEEKLY\r\nEXDATE;TZID=Europe/Berlin:20261116T093000\r\nSUMMARY:Chor\r\n" +
        "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT10M\r\nEND:VALARM\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-P1D\r\nEND:VALARM\r\n" +
        "X-HOMES-ETAG:\"7\"\r\nEND:VEVENT\r\n" +
        "BEGIN:VEVENT\r\nUID:chor\r\nRECURRENCE-ID;TZID=Europe/Berlin:20261109T093000\r\nDTSTART;TZID=Europe/Berlin:20261109T113000\r\n" +
        "DTEND;TZID=Europe/Berlin:20261109T123000\r\nSUMMARY:Chor später\r\nX-HOMES-ETAG:\"7\"\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n" +
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-CALNAME:Ferien\r\nX-HAUS-KALENDER:abo-ferien\r\nX-HAUS-ART:abo\r\nEND:VCALENDAR\r\n"

    @Test
    fun theWritingPhonesFeedKeepsSeriesAlarmsAndVersions() {
        val (haushalt, ferien) = IcalLeser.lies(feed)
        assertTrue(haushalt.schreibbar)
        assertFalse(ferien.schreibbar)
        assertEquals(Calendars.CAL_ACCESS_OWNER, KalenderAbgleich.zugriff(haushalt))
        assertEquals(Calendars.CAL_ACCESS_READ, KalenderAbgleich.zugriff(ferien))
        val (serie, spaeter) = haushalt.termine
        assertEquals("chor", serie.syncId)
        assertEquals("FREQ=WEEKLY", serie.rrule)
        assertEquals("Europe/Berlin;20261116T093000", serie.exdate)
        assertEquals(listOf(10, 1440), serie.alarme)
        assertEquals("\"7\"", serie.etag)
        assertNull(serie.original)
        assertEquals("chor#20261109T093000", spaeter.syncId)
        // 9:30 in Berlin on 9 November (CET) is 8:30 UTC: the instance it replaces
        assertEquals(utc(2026, 11, 9) + (8 * 60 + 30) * 60_000L, spaeter.original)
    }

    @Test
    fun anAppointmentGoesBackAsTheCalendarHasIt() {
        val jetzt = utc(2026, 11, 5)
        val einmal = IcalSchreiber.text(
            IcalSchreiber.Eintrag(
                "neu-1", utc(2026, 11, 10) + 17 * 3600_000L, utc(2026, 11, 10) + 18 * 3600_000L, null, false, "Europe/Berlin",
                "Elternabend; Aula", "Schule, Raum 2", "Bitte\npünktlich", null, null, listOf(15, 0),
            ),
            jetzt,
        )
        listOf(
            "UID:neu-1", "DTSTAMP:20261105T000000Z", "DTSTART:20261110T170000Z", "DTEND:20261110T180000Z", "SUMMARY:Elternabend\\; Aula",
            "LOCATION:Schule\\, Raum 2", "DESCRIPTION:Bitte\\npünktlich", "TRIGGER:-PT15M", "TRIGGER:-PT0M",
        ).forEach { assertTrue("$it in\n$einmal", einmal.contains("$it\r\n")) }

        val ganztags = IcalSchreiber.text(
            IcalSchreiber.Eintrag("tag", utc(2026, 12, 24), utc(2026, 12, 27), null, true, "UTC", "Weihnachten", null, null, null, null, emptyList()),
            jetzt,
        )
        assertTrue(ganztags, ganztags.contains("DTSTART;VALUE=DATE:20261224\r\nDTEND;VALUE=DATE:20261227\r\n"))
        assertFalse(ganztags.contains("VALARM"))

        // a series as CalendarContract keeps it: Android's own DURATION, its EXDATE column
        val serie = IcalSchreiber.Eintrag(
            "chor", utc(2026, 11, 2) + (8 * 60 + 30) * 60_000L, null, "P3600S", false, "Europe/Berlin",
            "Chor", null, null, "FREQ=WEEKLY", "Europe/Berlin;20261116T093000", listOf(10),
        )
        val text = IcalSchreiber.text(serie, jetzt)
        listOf("DTSTART;TZID=Europe/Berlin:20261102T093000", "DURATION:PT3600S", "RRULE:FREQ=WEEKLY", "EXDATE;TZID=Europe/Berlin:20261116T093000")
            .forEach { assertTrue("$it in\n$text", text.contains("$it\r\n")) }
        assertFalse(text.contains("DTEND"))

        // and read back as the phone wrote it
        val zurueck = IcalLeser.lies("BEGIN:VCALENDAR\r\nX-HAUS-KALENDER:haushalt\r\n" + text.substringAfter("PRODID:-//mdopp//Homes App//DE\r\n"))
            .single().termine.single()
        assertEquals(serie.beginn, zurueck.beginn)
        assertEquals(serie.beginn + 3600_000L, zurueck.ende)
        assertEquals("Europe/Berlin", zurueck.zone)
        assertEquals(serie.rrule, zurueck.rrule)
        assertEquals(serie.exdate, zurueck.exdate)
        assertEquals(serie.alarme, zurueck.alarme)
    }

    @Test
    fun exdatesInEachOfAndroidsForms() {
        assertEquals("EXDATE:20261116T083000Z,20261123T083000Z", IcalSchreiber.exdate("20261116T083000Z,20261123T083000Z"))
        assertEquals("EXDATE;VALUE=DATE:20261224", IcalSchreiber.exdate("20261224"))
        assertEquals("EXDATE:20261116T083000Z", IcalSchreiber.exdate("UTC;20261116T083000Z"))
    }

    @Test
    fun aCardCarriesItsVersion() {
        val karte = VcardLeser.lies("BEGIN:VCARD\r\nVERSION:3.0\r\nUID:oma\r\nFN:Oma\r\nX-HOMES-ETAG:\"3\"\r\nEND:VCARD\r\n").single()
        assertEquals("\"3\"", karte.etag)
        assertNull(VcardLeser.lies("BEGIN:VCARD\r\nVERSION:3.0\r\nUID:oma\r\nFN:Oma\r\nEND:VCARD\r\n").single().etag)
    }

    // --- one sync: send first, then fetch --------------------------------------------

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** A part that sends [gesendet]'s answers, one per entry, and records what happened in order. */
    private fun lauf(antworten: List<Antwort>, schritte: MutableList<String>) = object : KontoLauf(Teil.KALENDER, KontoStore(app), { e ->
        schritte += "holen $e"
        Antwort.Neu("feed", "\"f\"")
    }) {
        override fun senden(): String? {
            var verworfen: String? = null
            antworten.forEach { a -> schritte += "senden " + gesendet(a) { verworfen = it } }
            return verworfen
        }
        override fun schreiben(text: String): Int = 1.also { schritte += "schreiben $text" }
        override fun anzahl() = 1
        override fun abgelehnt(code: Int) = "abgelehnt $code"
        override val keineErlaubnis = "keine Erlaubnis"
    }

    @Test
    fun theSyncSendsFirstAndSaysWhenHomesVersionWon() {
        KontoStore(app).vergiss(Teil.KALENDER)
        val schritte = ArrayList<String>()
        lauf(listOf(Antwort.Neu("", "\"2\""), Antwort.Abgelehnt(412, null)), schritte).laufen(SyncResult())
        assertEquals(listOf("senden \"2\"", "senden null", "holen null", "schreiben feed"), schritte)
        assertEquals(KontoLauf.VERWORFEN, KontoStore(app).meldung(Teil.KALENDER))
        assertEquals(1, KontoStore(app).stand(Teil.KALENDER)!!.anzahl)

        // Homes' own sentence for a refusal; the clean run Android starts right after does not hide it, a day later one does
        lauf(listOf(Antwort.Abgelehnt(400, "Homes hat darin nicht genau diesen einen Eintrag gefunden.")), ArrayList()).laufen(SyncResult())
        lauf(emptyList(), ArrayList()).laufen(SyncResult())
        assertEquals("Homes hat darin nicht genau diesen einen Eintrag gefunden.", KontoStore(app).meldung(Teil.KALENDER))
        KontoStore(app).geschafft(Teil.KALENDER, null, null, System.currentTimeMillis() + 25 * 3600_000L)
        assertNull(KontoStore(app).meldung(Teil.KALENDER))
    }

    @Test
    fun homesNotThereWhileSendingFetchesNothing() {
        KontoStore(app).vergiss(Teil.KALENDER)
        listOf(Antwort.Unerreichbar, Antwort.Abgelehnt(502, null)).forEach { a ->
            val schritte = ArrayList<String>()
            val result = SyncResult()
            lauf(listOf(a), schritte).laufen(result)
            assertEquals("the phone's changes wait: $a", emptyList<String>(), schritte)
            assertEquals(1L, result.stats.numIoExceptions)
            assertEquals(KontoLauf.UNERREICHBAR, KontoStore(app).meldung(Teil.KALENDER))
        }
    }

    private fun utc(j: Int, m: Int, t: Int): Long =
        java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(j, m - 1, t) }.timeInMillis
}
