package cloud.dopp.homes

import android.database.MatrixCursor
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Website
import cloud.dopp.homes.konto.KontakteAbgleich
import cloud.dopp.homes.konto.VcardLeser
import cloud.dopp.homes.sicherung.Eintrag
import cloud.dopp.homes.sicherung.HandyKontakte
import cloud.dopp.homes.sicherung.Kontakt
import cloud.dopp.homes.sicherung.Vcard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The links Homes adds to each household card (Dokumente, Nachrichten, Fotos,
 * as grouped URL + X-ABLabel): read with their label, written as a Website
 * row of that label, and left out when the phone sends the card back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KontaktLinksTest {

    private val homes = "https://homes.example.org"

    private val karte = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:oma-1\r\nFN:Oma Erna\r\n" +
        "URL:https://erna.example.org\r\n" +
        "item1.URL:https://blog.example.org\r\nitem1.X-ABLabel:Blog\r\n" +
        "homes-dokumente.URL;TYPE=homes:$homes/kontakte/oma-1/dokumente\r\nhomes-dokumente.X-ABLabel:Dokumente\r\n" +
        "homes-nachrichten.URL;TYPE=homes:$homes/kontakte/oma-1/nachrichten\r\nhomes-nachrichten.X-ABLabel:Nachrichten\r\n" +
        "END:VCARD\r\n"

    @Test
    fun aUrlTakesTheLabelOfItsGroup() {
        val w = VcardLeser.lies(karte).single().kontakt.webseiten
        assertEquals(
            listOf(
                Eintrag("https://erna.example.org"),
                Eintrag("https://blog.example.org", label = "Blog", gruppe = "item1"),
                Eintrag("$homes/kontakte/oma-1/dokumente", label = "Dokumente", gruppe = "homes-dokumente"),
                Eintrag("$homes/kontakte/oma-1/nachrichten", label = "Nachrichten", gruppe = "homes-nachrichten"),
            ),
            w,
        )
    }

    @Test
    fun aLabelledUrlIsACustomWebsiteRowAnUnlabelledOneStaysOther() {
        val rows = KontakteAbgleich.daten(VcardLeser.lies(karte).single().kontakt)
            .filter { it.getAsString(ContactsContract.Data.MIMETYPE) == Website.CONTENT_ITEM_TYPE }
        assertEquals(4, rows.size)
        assertEquals(Website.TYPE_OTHER, rows[0].getAsInteger(Website.TYPE))
        assertNull(rows[0].getAsString(Website.LABEL))
        assertEquals(listOf("Blog", "Dokumente", "Nachrichten"), rows.drop(1).map { it.getAsString(Website.LABEL) })
        assertTrue(rows.drop(1).all { it.getAsInteger(Website.TYPE) == Website.TYPE_CUSTOM })
        assertEquals("homes-dokumente", rows[2].getAsString(ContactsContract.Data.SYNC1))
    }

    @Test
    fun theHomesLinksDoNotGoBackThePersonsOwnDo() {
        val k = Kontakt(
            uid = "oma-1",
            webseiten = listOf(
                Eintrag("https://erna.example.org"),
                Eintrag("https://blog.example.org", label = "Dokumente"), // the person's own, whatever its label
                Eintrag("$homes/kontakte/oma-1/dokumente", label = "Dokumente"), // by path on the paired address
                Eintrag("HTTPS://HOMES.EXAMPLE.ORG/kontakte/oma-1/nachrichten/"),
                Eintrag("https://anders.example.org/kontakte/oma-1/dokumente"), // another address: the person's
                Eintrag("$homes/kontakte/oma-1/fotos"), // the Fotos link (homes#287)
                Eintrag("$homes/kontakte/oma-1/bilder"), // another path on Homes: the person's
                Eintrag("https://wo.example.org/x", gruppe = "homes-dokumente"), // by its group
            ),
        )
        val zurueck = Vcard.ohneHomesLinks(k, "$homes/").webseiten.map { it.wert }
        assertEquals(
            listOf(
                "https://erna.example.org", "https://blog.example.org",
                "https://anders.example.org/kontakte/oma-1/dokumente", "$homes/kontakte/oma-1/bilder",
            ),
            zurueck,
        )
        // not paired: only the group tells a Homes link
        assertEquals(7, Vcard.ohneHomesLinks(k, null).webseiten.size)
    }

    @Test
    fun aCardReadBackFromThePhoneLeavesTheHomesLinksOut() {
        // the rows the sync adapter wrote, as the contacts provider hands them back
        val spalten = arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.SYNC1) + (1..10).map { "data$it" }
        val c = MatrixCursor(spalten)
        KontakteAbgleich.daten(VcardLeser.lies(karte).single().kontakt).forEach { v ->
            c.addRow(spalten.map { v.get(it) }.toTypedArray())
        }
        var k = Kontakt(uid = "oma-1")
        while (c.moveToNext()) k = HandyKontakte.dazu(k, c)
        // re-paired under another address: the group still tells them
        val text = Vcard.karte(Vcard.ohneHomesLinks(k, "https://neu.example.org"))
        assertTrue(text.contains("URL:https://erna.example.org\r\n"))
        assertTrue(text.contains("URL:https://blog.example.org\r\n"))
        assertFalse(text.contains("/kontakte/oma-1/"))
    }
}
