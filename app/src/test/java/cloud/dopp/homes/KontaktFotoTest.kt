package cloud.dopp.homes

import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.util.Base64
import cloud.dopp.homes.konto.KontakteAbgleich
import cloud.dopp.homes.konto.VcardLeser
import cloud.dopp.homes.sicherung.Kontakt
import cloud.dopp.homes.sicherung.Vcard
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * Contact pictures (the owner's call, 2026-11-05; Homes' side homes#287): the
 * face Homes adds from Immich becomes the contact's picture and never goes
 * back; a picture the person sets on the phone goes to Homes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KontaktFotoTest {

    private val gesicht = jpeg(96, 96)
    private val b64 = Base64.encodeToString(gesicht, Base64.NO_WRAP)

    /** [l] folded as Homes folds it: 75 octets, then a space and 74. */
    private fun gefaltet(l: String): String = Vcard.falte(l)

    private fun karte(version: String, foto: String) =
        "BEGIN:VCARD\r\nVERSION:$version\r\nUID:oma-1\r\nFN:Oma Erna\r\n${gefaltet(foto)}\r\nEND:VCARD\r\n"

    private val von30 = karte("3.0", "homes-foto.PHOTO;ENCODING=b;TYPE=JPEG;X-HOMES=immich:$b64")

    @Test
    fun aPictureIsReadFromVcard30And40() {
        val homes30 = VcardLeser.lies(von30).single().kontakt
        assertEquals(b64, homes30.foto)
        assertTrue(homes30.fotoVonHomes)

        val homes40 = VcardLeser.lies(karte("4.0", "homes-foto.PHOTO;MEDIATYPE=image/jpeg;X-HOMES=immich:data:image/jpeg;base64,$b64")).single().kontakt
        assertEquals(b64, homes40.foto)
        assertTrue(homes40.fotoVonHomes)

        // the person's own: no homes- group, no X-HOMES
        val eigen30 = VcardLeser.lies(karte("3.0", "PHOTO;ENCODING=b;TYPE=JPEG:$b64")).single().kontakt
        assertEquals(b64, eigen30.foto)
        assertFalse(eigen30.fotoVonHomes)
        val eigen40 = VcardLeser.lies(karte("4.0", "PHOTO:data:image/jpeg;base64,$b64")).single().kontakt
        assertEquals(b64, eigen40.foto)
        assertFalse(eigen40.fotoVonHomes)
        // only the group, only the parameter: either marks Homes' face
        assertTrue(VcardLeser.lies(karte("3.0", "homes-foto.PHOTO;ENCODING=b:$b64")).single().kontakt.fotoVonHomes)
        assertTrue(VcardLeser.lies(karte("3.0", "PHOTO;ENCODING=b;X-HOMES=immich:$b64")).single().kontakt.fotoVonHomes)

        // a link to a picture is no picture the phone can show
        assertNull(VcardLeser.lies(karte("3.0", "PHOTO;VALUE=uri:https://example.org/oma.jpg")).single().kontakt.foto)
    }

    @Test
    fun homesFaceIsWrittenAsAPictureRowWithItsMark() {
        val row = KontakteAbgleich.daten(VcardLeser.lies(von30).single().kontakt)
            .single { it.getAsString(ContactsContract.Data.MIMETYPE) == Photo.CONTENT_ITEM_TYPE }
        assertArrayEquals(gesicht, row.getAsByteArray(Photo.PHOTO))
        assertEquals(KontakteAbgleich.HOMES_FOTO, row.getAsString(ContactsContract.Data.SYNC1))
        assertEquals(KontakteAbgleich.NEU, row.getAsString(ContactsContract.Data.SYNC2))

        val eigen = KontakteAbgleich.daten(Kontakt(uid = "oma-1", foto = b64))
            .single { it.getAsString(ContactsContract.Data.MIMETYPE) == Photo.CONTENT_ITEM_TYPE }
        assertNull(eigen.getAsString(ContactsContract.Data.SYNC1))
        assertEquals(KontakteAbgleich.NEU, eigen.getAsString(ContactsContract.Data.SYNC2))

        // broken base64: no picture row, the card still goes in
        assertTrue(KontakteAbgleich.daten(Kontakt(uid = "oma-1", foto = "%%%")).none { it.getAsString(ContactsContract.Data.MIMETYPE) == Photo.CONTENT_ITEM_TYPE })
    }

    /** A picture row as the contacts provider hands it back. */
    private fun zeile(bytes: ByteArray, sync1: String?, sync2: String?) =
        MatrixCursor(arrayOf(Photo.PHOTO, ContactsContract.Data.SYNC1, ContactsContract.Data.SYNC2)).apply {
            addRow(arrayOf<Any?>(bytes, sync1, sync2))
            moveToFirst()
        }

    @Test
    fun onlyAPictureSetOrChangedOnThePhoneGoesBack() {
        val behalten = jpeg(80, 80) // Android re-encodes what it is given
        val stand = KontakteAbgleich.stand(behalten)
        // Homes' face as Android kept it: stays home
        assertNull(KontakteAbgleich.eigenesFoto(zeile(behalten, KontakteAbgleich.HOMES_FOTO, stand)))
        // just written, its hash not read yet: stays home
        assertNull(KontakteAbgleich.eigenesFoto(zeile(behalten, KontakteAbgleich.HOMES_FOTO, KontakteAbgleich.NEU)))
        // the person's own picture as Homes sent it: Homes has it already
        assertNull(KontakteAbgleich.eigenesFoto(zeile(behalten, null, stand)))
        // changed in place (row, SYNC1 and SYNC2 stay): the person's
        val neu = jpeg(120, 90)
        assertArrayEquals(neu, KontakteAbgleich.eigenesFoto(zeile(neu, KontakteAbgleich.HOMES_FOTO, stand)))
        // a row the contacts app made: the person's
        assertArrayEquals(neu, KontakteAbgleich.eigenesFoto(zeile(neu, null, null)))
    }

    @Test
    fun theCardSentBackCarriesOnlyThePersonsPicture() {
        val text = Vcard.karte(Kontakt(uid = "oma-1", anzeigename = "Oma Erna", foto = b64))
        assertTrue(text.contains("\r\nPHOTO;ENCODING=b;TYPE=JPEG:"))
        assertTrue(text.split("\r\n").all { it.toByteArray().size <= 75 })
        assertEquals(b64, VcardLeser.lies(text).single().kontakt.foto)
        assertFalse(VcardLeser.lies(text).single().kontakt.fotoVonHomes)

        assertFalse(Vcard.karte(Kontakt(uid = "oma-1", foto = b64, fotoVonHomes = true)).contains("PHOTO"))
    }

    @Test
    fun aRoundTripSendsNothingUntilThePersonChangesThePicture() {
        val spalten = arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.SYNC1, ContactsContract.Data.SYNC2) +
            (1..10).map { "data$it" } + Photo.PHOTO
        // Homes' card in, the rows written; Android re-encodes the picture, the hash is read back
        val rows = KontakteAbgleich.daten(VcardLeser.lies(von30).single().kontakt)
        val foto = rows.single { it.getAsString(ContactsContract.Data.MIMETYPE) == Photo.CONTENT_ITEM_TYPE }
        val behalten = jpeg(80, 80)
        foto.put(Photo.PHOTO, behalten)
        foto.put(ContactsContract.Data.SYNC2, KontakteAbgleich.stand(behalten))

        fun zurueck(): String {
            val c = MatrixCursor(spalten)
            rows.forEach { v -> c.addRow(spalten.map { v.get(it) }.toTypedArray()) }
            var eigen: ByteArray? = null
            while (c.moveToNext()) if (c.getString(0) == Photo.CONTENT_ITEM_TYPE) eigen = KontakteAbgleich.eigenesFoto(c)
            return Vcard.karte(Kontakt(uid = "oma-1", foto = eigen?.let { Base64.encodeToString(it, Base64.NO_WRAP) }))
        }
        assertFalse(zurueck().contains("PHOTO"))

        // the person sets another picture in the contacts app
        val neu = jpeg(200, 150)
        foto.put(Photo.PHOTO, neu)
        val text = zurueck()
        assertTrue(text.contains("PHOTO;ENCODING=b;TYPE=JPEG:"))
        val gelesen = VcardLeser.lies(text).single().kontakt
        assertArrayEquals(neu, Base64.decode(gelesen.foto, Base64.DEFAULT))
        assertFalse(gelesen.fotoVonHomes)
    }

    @Test
    fun aLargePictureGoesAsAJpegOfAtMost256Pixels() {
        val gross = KontakteAbgleich.verkleinert(jpeg(720, 480))!!
        val b = BitmapFactory.decodeByteArray(gross, 0, gross.size)
        assertEquals(256, b.width)
        assertEquals(171, b.height)
        val klein = jpeg(96, 96)
        assertSame(klein, KontakteAbgleich.verkleinert(klein))
        assertNull(KontakteAbgleich.verkleinert(byteArrayOf(1, 2, 3)))
    }

    private fun jpeg(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff3ddc84.toInt() + w + h) }
        return ByteArrayOutputStream().use { out ->
            b.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }
}
