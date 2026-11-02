package cloud.dopp.haus

import cloud.dopp.haus.sicherung.Adresse
import cloud.dopp.haus.sicherung.Eintrag
import cloud.dopp.haus.sicherung.Kontakt
import cloud.dopp.haus.sicherung.Vcard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contacts go to Haus as vCard 3.0 the app writes itself (haus#210):
 * every card with `UID:<lookup key>`, text escaped, lines folded at 75
 * octets, CRLF line ends.
 */
class VcardTest {

    /** The card's lines, unfolded, as a reader sees them. */
    private fun zeilen(karte: String): List<String> = karte.replace("\r\n ", "").split("\r\n").dropLast(1)

    @Test
    fun aCardCarriesVersionUidAndName() {
        val k = Kontakt(
            uid = "3789r12-2F4B.1234i5",
            anzeigename = "Anna Müller",
            nachname = "Müller", vorname = "Anna",
            telefone = listOf(Eintrag("+49 170 1234567", listOf("cell", "voice")), Eintrag("030 123", listOf("home", "fax"))),
            mails = listOf(Eintrag("anna@example.org", listOf("home"))),
            adressen = listOf(Adresse(typen = listOf("home"), strasse = "Hauptstr. 1", ort = "Berlin", plz = "10115", land = "Deutschland")),
            firma = "Firma GmbH", abteilung = "Einkauf", titel = "Leitung",
            geburtstag = "1980-05-17", notiz = "Erste Zeile\nzweite; mit, Zeichen", webseiten = listOf("https://example.org"),
        )
        val karte = Vcard.karte(k)
        assertTrue(karte.endsWith("END:VCARD\r\n"))
        assertFalse("only CRLF", karte.replace("\r\n", "").contains('\n') || karte.replace("\r\n", "").contains('\r'))
        assertEquals(
            listOf(
                "BEGIN:VCARD",
                "VERSION:3.0",
                "UID:3789r12-2F4B.1234i5",
                "FN:Anna Müller",
                "N:Müller;Anna;;;",
                "TEL;TYPE=CELL,VOICE:+49 170 1234567",
                "TEL;TYPE=HOME,FAX:030 123",
                "EMAIL;TYPE=INTERNET,HOME:anna@example.org",
                "ADR;TYPE=HOME:;;Hauptstr. 1;Berlin;;10115;Deutschland",
                "ORG:Firma GmbH;Einkauf",
                "TITLE:Leitung",
                "BDAY:1980-05-17",
                "NOTE:Erste Zeile\\nzweite\\; mit\\, Zeichen",
                "URL:https://example.org",
                "END:VCARD",
            ),
            zeilen(karte),
        )
        assertEquals(2, Vcard.anzahl(karte + Vcard.karte(Kontakt(uid = "b", anzeigename = "Ben"))))
    }

    @Test
    fun textIsEscaped() {
        assertEquals("a\\\\b\\,c\\;d\\ne\\nf", Vcard.text("a\\b,c;d\ne\r\nf"))
        val k = Kontakt(uid = "x;y,z", nachname = "Doe; Jr.", vorname = "A,B")
        val l = zeilen(Vcard.karte(k))
        assertTrue(l.contains("UID:x\\;y\\,z"))
        assertTrue(l.contains("N:Doe\\; Jr.;A\\,B;;;"))
        assertTrue("FN from the name's parts", l.contains("FN:A\\,B Doe\\; Jr."))
    }

    @Test
    fun aNamelessContactStillHasAnFn() {
        assertEquals("+49 1", Vcard.anzeigename(Kontakt(uid = "u", telefone = listOf(Eintrag("+49 1")))))
        assertEquals("Firma", Vcard.anzeigename(Kontakt(uid = "u", firma = "Firma")))
        assertEquals("Ohne Namen", Vcard.anzeigename(Kontakt(uid = "u")))
    }

    @Test
    fun aBirthdayWithoutYearIsLeftOut() {
        val l = zeilen(Vcard.karte(Kontakt(uid = "u", anzeigename = "A", geburtstag = "--05-17")))
        assertTrue(l.none { it.startsWith("BDAY") })
    }

    @Test
    fun longLinesAreFoldedAt75OctetsNeverInsideACharacter() {
        val note = "ä".repeat(100) + "😀".repeat(30) + "x".repeat(80)
        val karte = Vcard.karte(Kontakt(uid = "u", anzeigename = "A", notiz = note))
        karte.split("\r\n").forEach { physisch ->
            assertTrue("${physisch.toByteArray().size} octets: $physisch", physisch.toByteArray(Charsets.UTF_8).size <= 75)
            assertFalse("a split surrogate", physisch.isNotEmpty() && Character.isLowSurrogate(physisch[0]))
            assertFalse("a split surrogate", physisch.isNotEmpty() && Character.isHighSurrogate(physisch.last()))
        }
        assertTrue(zeilen(karte).contains("NOTE:$note"))
        assertEquals("a short line stays", "FN:A", Vcard.falte("FN:A"))
        val genau = "N:" + "x".repeat(73)
        assertEquals("exactly 75 octets stay one line", genau, Vcard.falte(genau))
        assertEquals("N:" + "x".repeat(73) + "\r\n y", Vcard.falte(genau + "y"))
    }
}
