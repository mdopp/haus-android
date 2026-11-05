package cloud.dopp.homes.konto

import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.RawContacts
import android.util.Base64
import androidx.core.graphics.scale
import cloud.dopp.homes.data.GemeinsamClient
import cloud.dopp.homes.sicherung.HandyKontakte
import cloud.dopp.homes.sicherung.Kontakt
import cloud.dopp.homes.sicherung.Vcard
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The Homes account's contacts (#206): the household's cards from Homes as
 * raw contacts of the account, so the dialer, WhatsApp and mail find them.
 * Each raw contact's SOURCE_ID is its card's UID ([HomesKonto.quelle]), SYNC1
 * the hash of the card's text: a card that did not change is not written;
 * SYNC2 the UID itself, SYNC3 the card's version in Homes. The sync
 * adapter's own writes (CALLER_IS_SYNCADAPTER) mark nothing dirty. Writable
 * since the owner's call of 2026-11-05 (res/xml/kontakte_konto.xml's edit
 * schema, kontakte_sync.xml's supportsUploading): what the contacts app
 * makes, changes or deletes in the account is dirty and sent first
 * ([senden]) as a vCard 3.0 ([Vcard]); Homes keeps what the phone does not
 * show (the IBANs), and its version wins a conflict. The account's
 * contacts are visible without a group (Settings UNGROUPED_VISIBLE). A card's
 * PHOTO (the person's, or the face Homes adds from Immich, SYNC1 [HOMES_FOTO])
 * is the contact's picture; SYNC2 the hash of its bytes as Android kept them,
 * so only a picture set or changed on the phone goes back ([eigenesFoto]).
 */
class KontakteAbgleich(
    private val provider: ContentProviderClient,
    store: KontoStore,
    holen: (String?) -> GemeinsamClient.Antwort,
    private val nachHomes: (String?, String, String?, String?) -> GemeinsamClient.Antwort = { _, _, _, _ -> GemeinsamClient.Antwort.Unerreichbar },
    private val homes: String? = null,
) : KontoLauf(HomesKonto.Teil.KONTAKTE, store, holen) {

    override val keineErlaubnis = KEINE_ERLAUBNIS

    override fun abgelehnt(code: Int) = "Homes hat die Kontakte nicht geschickt ($code)."

    override fun anzahl(): Int =
        provider.query(alsAdapter(RawContacts.CONTENT_URI), arrayOf(RawContacts._ID), MEINE, KONTO, null)?.use { it.count } ?: 0

    public override fun senden(): String? {
        var verworfen: String? = null
        val zeilen = ArrayList<Triple<Long, Boolean, Pair<String?, String?>>>()
        provider.query(
            alsAdapter(RawContacts.CONTENT_URI), arrayOf(RawContacts._ID, RawContacts.DELETED, RawContacts.SYNC2, RawContacts.SYNC3),
            "$MEINE AND ${RawContacts.DIRTY} = 1", KONTO, null,
        )?.use { c -> while (c.moveToNext()) zeilen += Triple(c.getLong(0), c.getInt(1) == 1, c.getString(2) to c.getString(3)) }
        for ((id, weg, schluessel) in zeilen) {
            val (uid, etag) = schluessel
            if (weg) {
                // gone from the phone: from Homes too; refused, Homes' version comes back below
                if (uid != null && etag != null) gesendet(nachHomes(null, uid, null, etag)) { verworfen = it }
                provider.delete(zeileUri(RawContacts.CONTENT_URI, id), null, null)
                continue
            }
            val karte = uid?.takeIf { etag != null } ?: UUID.randomUUID().toString()
            val neu = gesendet(nachHomes(null, karte, Vcard.karte(Vcard.ohneHomesLinks(kontakt(id, karte), homes)), etag)) { verworfen = it }
            val werte = ContentValues().apply {
                put(RawContacts.DIRTY, 0)
                putNull(RawContacts.SYNC1)
                if (neu != null) {
                    put(RawContacts.SOURCE_ID, HomesKonto.quelle(karte))
                    put(RawContacts.SYNC2, karte)
                    put(RawContacts.SYNC3, neu)
                }
            }
            provider.update(zeileUri(RawContacts.CONTENT_URI, id), werte, null, null)
        }
        return verworfen
    }

    /** Raw contact [id] as the phone has it, as a card of [uid]. */
    private fun kontakt(id: Long, uid: String): Kontakt {
        var k = Kontakt(uid = uid)
        var eigenes: ByteArray? = null
        provider.query(
            alsAdapter(ContactsContract.Data.CONTENT_URI), DATEN, "${ContactsContract.Data.RAW_CONTACT_ID} = ?", arrayOf(id.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) if (c.getString(0) == Photo.CONTENT_ITEM_TYPE) eigenes = eigenes ?: eigenesFoto(c) else k = HandyKontakte.dazu(k, c)
        }
        return eigenes?.let { klein -> k.copy(foto = verkleinert(gross(id) ?: klein)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }) } ?: k
    }

    /** Raw contact [id]'s large picture, as Android keeps it beside the thumbnail; null without one. */
    private fun gross(id: Long): ByteArray? = runCatching {
        val uri = Uri.withAppendedPath(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id), RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
        provider.openAssetFile(uri, "r")?.createInputStream()?.use { it.readBytes() }
    }.getOrNull()

    /** Each picture written this run (SYNC2 [NEU]) gets the hash of its bytes as Android re-encoded and kept them. */
    private fun fotosGelesen() {
        val gelesen = ArrayList<Pair<Long, String>>()
        provider.query(
            alsAdapter(ContactsContract.Data.CONTENT_URI), arrayOf(ContactsContract.Data._ID, Photo.PHOTO),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.SYNC2} = ?", arrayOf(Photo.CONTENT_ITEM_TYPE, NEU), null,
        )?.use { c -> while (c.moveToNext()) gelesen += c.getLong(0) to stand(c.getBlob(1) ?: ByteArray(0)) }
        gelesen.forEach { (id, h) ->
            provider.update(zeileUri(ContactsContract.Data.CONTENT_URI, id), ContentValues().apply { put(ContactsContract.Data.SYNC2, h) }, null, null)
        }
    }

    public override fun schreiben(text: String): Int {
        sichtbar()
        val karten = VcardLeser.lies(text).associateBy { HomesKonto.quelle(it.kontakt.uid) }
        val vorhanden = ArrayList<Abgleich.Zeile>()
        provider.query(
            alsAdapter(RawContacts.CONTENT_URI), arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1),
            MEINE, KONTO, null,
        )?.use { c ->
            while (c.moveToNext()) vorhanden += Abgleich.Zeile(c.getLong(0), c.getString(1), c.getString(2))
        }
        val plan = Abgleich.plane(vorhanden, karten) { stand(it.text) }
        val ops = ArrayList<ContentProviderOperation>()
        plan.weg.forEach { id ->
            ops += ContentProviderOperation.newDelete(zeileUri(RawContacts.CONTENT_URI, id)).build()
            provider.flush(ops)
        }
        plan.geaendert.forEach { (id, karte) ->
            ops += ContentProviderOperation.newUpdate(zeileUri(RawContacts.CONTENT_URI, id))
                .withValue(RawContacts.SYNC1, stand(karte.text)).withValue(RawContacts.SYNC2, karte.kontakt.uid)
                .withValue(RawContacts.SYNC3, karte.etag).build()
            ops += ContentProviderOperation.newDelete(alsAdapter(ContactsContract.Data.CONTENT_URI))
                .withSelection("${ContactsContract.Data.RAW_CONTACT_ID} = ?", arrayOf(id.toString())).build()
            daten(karte.kontakt).forEach { v ->
                ops += ContentProviderOperation.newInsert(alsAdapter(ContactsContract.Data.CONTENT_URI))
                    .withValues(v).withValue(ContactsContract.Data.RAW_CONTACT_ID, id).build()
            }
            provider.flush(ops)
        }
        plan.neu.forEach { karte ->
            val start = ops.size
            ops += ContentProviderOperation.newInsert(alsAdapter(RawContacts.CONTENT_URI))
                .withValue(RawContacts.ACCOUNT_TYPE, HomesKonto.TYP)
                .withValue(RawContacts.ACCOUNT_NAME, HomesKonto.NAME)
                .withValue(RawContacts.SOURCE_ID, HomesKonto.quelle(karte.kontakt.uid))
                .withValue(RawContacts.SYNC1, stand(karte.text))
                .withValue(RawContacts.SYNC2, karte.kontakt.uid)
                .withValue(RawContacts.SYNC3, karte.etag)
                .build()
            daten(karte.kontakt).forEach { v ->
                ops += ContentProviderOperation.newInsert(alsAdapter(ContactsContract.Data.CONTENT_URI))
                    .withValues(v).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, start).build()
            }
            provider.flush(ops)
        }
        provider.flush(ops, alles = true)
        fotosGelesen()
        return karten.size
    }

    /** The account's contacts show without a group, as a phone's own do. */
    private fun sichtbar() {
        val werte = ContentValues().apply {
            put(ContactsContract.Settings.ACCOUNT_NAME, HomesKonto.NAME)
            put(ContactsContract.Settings.ACCOUNT_TYPE, HomesKonto.TYP)
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
            put(ContactsContract.Settings.SHOULD_SYNC, 1)
        }
        val uri = alsAdapter(ContactsContract.Settings.CONTENT_URI)
        val da = provider.query(
            uri, arrayOf(ContactsContract.Settings.UNGROUPED_VISIBLE),
            "${ContactsContract.Settings.ACCOUNT_TYPE} = ? AND ${ContactsContract.Settings.ACCOUNT_NAME} = ?", KONTO, null,
        )?.use { it.count > 0 } ?: false
        if (da) {
            provider.update(uri, werte, "${ContactsContract.Settings.ACCOUNT_TYPE} = ? AND ${ContactsContract.Settings.ACCOUNT_NAME} = ?", KONTO)
        } else {
            provider.insert(uri, werte)
        }
    }

    private fun zeileUri(uri: Uri, id: Long): Uri = alsAdapter(android.content.ContentUris.withAppendedId(uri, id))

    companion object {
        const val KEINE_ERLAUBNIS =
            "Die App darf die Kontakte nicht mehr schreiben: schalte bei „Haushalts-Kontakte“ den Schalter noch einmal ein."

        private const val MEINE = "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?"

        /** What [HandyKontakte.dazu] reads of a data row; SYNC1 a URL's vCard group; a picture and its hash ([eigenesFoto]). */
        private val DATEN = arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.SYNC1) + (1..10).map { "data$it" } +
            arrayOf(Photo.PHOTO, ContactsContract.Data.SYNC2)

        /** A picture row's SYNC1 when it is the face Homes adds from Immich (Android re-encodes the bytes, so the mark is here). */
        const val HOMES_FOTO = "homes-foto"

        /** A picture row's SYNC2 between its insert and [fotosGelesen]. */
        const val NEU = "homes-neu"

        /** At most this many pixels a side for a picture that goes to Homes. */
        private const val KANTE = 256
        private val KONTO = arrayOf(HomesKonto.TYP, HomesKonto.NAME)

        /** [uri] as the sync adapter of the Homes account writes it. */
        fun alsAdapter(uri: Uri): Uri = uri.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, HomesKonto.NAME)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, HomesKonto.TYP)
            .build()

        /** A card's state: the hash of its text. */
        fun stand(text: String): String = stand(text.toByteArray(Charsets.UTF_8))

        /** A picture's state: the hash of its bytes. */
        fun stand(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).take(16).joinToString("") { "%02x".format(it) }

        /**
         * The bytes of picture row [c] when they go to Homes: the person's, set on
         * the phone (no SYNC2: the adapter did not write it) or changed there (no
         * longer SYNC2's hash). A picture as Homes sent it, its face or the
         * person's own, stays: Homes keeps what it has (homes#287).
         */
        fun eigenesFoto(c: Cursor): ByteArray? {
            val bytes = c.getBlob(c.getColumnIndexOrThrow(Photo.PHOTO))?.takeIf { it.isNotEmpty() } ?: return null
            val hash = c.getString(c.getColumnIndexOrThrow(ContactsContract.Data.SYNC2))
            return bytes.takeIf { hash == null || (hash != NEU && hash != stand(bytes)) }
        }

        /** [bild] as a JPEG of at most [KANTE] px a side; as it is when no larger; null when it is no picture. */
        fun verkleinert(bild: ByteArray): ByteArray? {
            val b = BitmapFactory.decodeByteArray(bild, 0, bild.size) ?: return null
            val f = KANTE.toFloat() / maxOf(b.width, b.height)
            if (f >= 1f) return bild
            val klein = b.scale((b.width * f).roundToInt().coerceAtLeast(1), (b.height * f).roundToInt().coerceAtLeast(1))
            return ByteArrayOutputStream().use { out ->
                klein.compress(Bitmap.CompressFormat.JPEG, JPEG_GUETE, out)
                out.toByteArray()
            }
        }

        private const val JPEG_GUETE = 90

        /** The data rows of [k], one ContentValues each, without the raw contact's id. */
        fun daten(k: Kontakt): List<ContentValues> {
            val out = ArrayList<ContentValues>()
            fun zeile(mime: String, f: ContentValues.() -> Unit) {
                out += ContentValues().apply { put(ContactsContract.Data.MIMETYPE, mime); f() }
            }
            zeile(StructuredName.CONTENT_ITEM_TYPE) {
                put(StructuredName.DISPLAY_NAME, Vcard.anzeigename(k))
                put(StructuredName.FAMILY_NAME, k.nachname)
                put(StructuredName.GIVEN_NAME, k.vorname)
                put(StructuredName.MIDDLE_NAME, k.weitere)
                put(StructuredName.PREFIX, k.praefix)
                put(StructuredName.SUFFIX, k.suffix)
            }
            k.telefone.forEach { t ->
                zeile(Phone.CONTENT_ITEM_TYPE) {
                    put(Phone.NUMBER, t.wert)
                    put(Phone.TYPE, telefonTyp(t.typen))
                }
            }
            k.mails.forEach { m ->
                zeile(Email.CONTENT_ITEM_TYPE) {
                    put(Email.ADDRESS, m.wert)
                    put(Email.TYPE, ortTyp(m.typen, Email.TYPE_HOME, Email.TYPE_WORK, Email.TYPE_OTHER))
                }
            }
            k.adressen.forEach { a ->
                zeile(StructuredPostal.CONTENT_ITEM_TYPE) {
                    put(StructuredPostal.POBOX, a.postfach)
                    put(StructuredPostal.NEIGHBORHOOD, a.zusatz)
                    put(StructuredPostal.STREET, a.strasse)
                    put(StructuredPostal.CITY, a.ort)
                    put(StructuredPostal.REGION, a.region)
                    put(StructuredPostal.POSTCODE, a.plz)
                    put(StructuredPostal.COUNTRY, a.land)
                    put(StructuredPostal.TYPE, ortTyp(a.typen, StructuredPostal.TYPE_HOME, StructuredPostal.TYPE_WORK, StructuredPostal.TYPE_OTHER))
                }
            }
            if (k.firma != null || k.abteilung != null || k.titel != null) {
                zeile(Organization.CONTENT_ITEM_TYPE) {
                    put(Organization.COMPANY, k.firma)
                    put(Organization.DEPARTMENT, k.abteilung)
                    put(Organization.TITLE, k.titel)
                    put(Organization.TYPE, Organization.TYPE_WORK)
                }
            }
            k.geburtstag?.let { g ->
                zeile(Event.CONTENT_ITEM_TYPE) {
                    put(Event.START_DATE, g)
                    put(Event.TYPE, Event.TYPE_BIRTHDAY)
                }
            }
            k.notiz?.let { n -> zeile(Note.CONTENT_ITEM_TYPE) { put(Note.NOTE, n) } }
            k.webseiten.forEach { w ->
                zeile(Website.CONTENT_ITEM_TYPE) {
                    put(Website.URL, w.wert)
                    put(Website.TYPE, if (w.label == null) Website.TYPE_OTHER else Website.TYPE_CUSTOM)
                    put(Website.LABEL, w.label)
                    put(ContactsContract.Data.SYNC1, w.gruppe) // a Homes link is known again when the card goes back
                }
            }
            return out + listOfNotNull(foto(k))
        }

        /** [k]'s picture as a data row: SYNC1 [HOMES_FOTO] when it is Homes' face, SYNC2 [NEU] until [fotosGelesen]. */
        private fun foto(k: Kontakt): ContentValues? {
            val jpg = k.foto?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: return null
            return ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                put(Photo.PHOTO, jpg)
                if (k.fotoVonHomes) put(ContactsContract.Data.SYNC1, HOMES_FOTO)
                put(ContactsContract.Data.SYNC2, NEU)
            }
        }

        /** vCard's TEL types as Android's phone type: the reverse of HandyKontakte.telefonTypen. */
        fun telefonTyp(t: List<String>): Int = when {
            "fax" in t && "work" in t -> Phone.TYPE_FAX_WORK
            "fax" in t -> Phone.TYPE_FAX_HOME
            "pager" in t -> Phone.TYPE_PAGER
            "cell" in t && "work" in t -> Phone.TYPE_WORK_MOBILE
            "cell" in t || "mobile" in t -> Phone.TYPE_MOBILE
            "work" in t -> Phone.TYPE_WORK
            "home" in t -> Phone.TYPE_HOME
            "main" in t -> Phone.TYPE_MAIN
            else -> Phone.TYPE_OTHER
        }

        private fun ortTyp(t: List<String>, home: Int, work: Int, other: Int): Int = when {
            "home" in t -> home
            "work" in t -> work
            else -> other
        }
    }
}
