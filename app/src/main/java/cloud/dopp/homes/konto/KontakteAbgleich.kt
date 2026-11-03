package cloud.dopp.homes.konto

import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.RawContacts
import cloud.dopp.homes.data.GemeinsamClient
import cloud.dopp.homes.sicherung.Kontakt
import cloud.dopp.homes.sicherung.Vcard
import java.security.MessageDigest

/**
 * The Homes account's contacts (#206): the household's cards from Homes as
 * raw contacts of the account, so the dialer, WhatsApp and mail find them.
 * Each raw contact's SOURCE_ID is its card's UID ([HomesKonto.quelle]), SYNC1
 * the hash of the card's text: a card that did not change is not written.
 * Every write goes as the sync adapter (CALLER_IS_SYNCADAPTER): a deleted
 * one is gone at once, and nothing is marked dirty to be sent back — the
 * account never sends anything (res/xml/kontakte_sync.xml says
 * supportsUploading false; res/xml/kontakte_konto.xml declares no edit
 * schema, so the contacts app offers no editing). The account's contacts
 * are visible without a group (Settings UNGROUPED_VISIBLE).
 */
class KontakteAbgleich(
    private val provider: ContentProviderClient,
    store: KontoStore,
    holen: (String?) -> GemeinsamClient.Antwort,
) : KontoLauf(HomesKonto.Teil.KONTAKTE, store, holen) {

    override val keineErlaubnis = KEINE_ERLAUBNIS

    override fun abgelehnt(code: Int) = "Homes hat die Kontakte nicht geschickt ($code)."

    override fun anzahl(): Int =
        provider.query(alsAdapter(RawContacts.CONTENT_URI), arrayOf(RawContacts._ID), MEINE, KONTO, null)?.use { it.count } ?: 0

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
            flush(ops)
        }
        plan.geaendert.forEach { (id, karte) ->
            ops += ContentProviderOperation.newUpdate(zeileUri(RawContacts.CONTENT_URI, id))
                .withValue(RawContacts.SYNC1, stand(karte.text)).build()
            ops += ContentProviderOperation.newDelete(alsAdapter(ContactsContract.Data.CONTENT_URI))
                .withSelection("${ContactsContract.Data.RAW_CONTACT_ID} = ?", arrayOf(id.toString())).build()
            daten(karte.kontakt).forEach { v ->
                ops += ContentProviderOperation.newInsert(alsAdapter(ContactsContract.Data.CONTENT_URI))
                    .withValues(v).withValue(ContactsContract.Data.RAW_CONTACT_ID, id).build()
            }
            flush(ops)
        }
        plan.neu.forEach { karte ->
            val start = ops.size
            ops += ContentProviderOperation.newInsert(alsAdapter(RawContacts.CONTENT_URI))
                .withValue(RawContacts.ACCOUNT_TYPE, HomesKonto.TYP)
                .withValue(RawContacts.ACCOUNT_NAME, HomesKonto.NAME)
                .withValue(RawContacts.SOURCE_ID, HomesKonto.quelle(karte.kontakt.uid))
                .withValue(RawContacts.SYNC1, stand(karte.text))
                .build()
            daten(karte.kontakt).forEach { v ->
                ops += ContentProviderOperation.newInsert(alsAdapter(ContactsContract.Data.CONTENT_URI))
                    .withValues(v).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, start).build()
            }
            flush(ops)
        }
        flush(ops, alles = true)
        return karten.size
    }

    /** Apply [ops] once they are many (or [alles]): a batch the provider takes in one go, never one cut inside a contact. */
    private fun flush(ops: ArrayList<ContentProviderOperation>, alles: Boolean = false) {
        if (ops.isEmpty() || (!alles && ops.size < STAPEL)) return
        provider.applyBatch(ops)
        ops.clear()
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
            "Die App darf die Kontakte nicht mehr schreiben: schalte bei „Kontakte des Hauses“ den Schalter noch einmal ein."

        /** Operations a batch: the provider's yield limit is 500. */
        private const val STAPEL = 400

        private const val MEINE = "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?"
        private val KONTO = arrayOf(HomesKonto.TYP, HomesKonto.NAME)

        /** [uri] as the sync adapter of the Homes account writes it. */
        fun alsAdapter(uri: Uri): Uri = uri.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, HomesKonto.NAME)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, HomesKonto.TYP)
            .build()

        /** A card's state: the hash of its text. */
        fun stand(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

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
                    put(Website.URL, w)
                    put(Website.TYPE, Website.TYPE_OTHER)
                }
            }
            return out
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
