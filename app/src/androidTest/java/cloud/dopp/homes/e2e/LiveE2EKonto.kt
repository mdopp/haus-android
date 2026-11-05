package cloud.dopp.homes.e2e

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dopp.homes.konto.HomesKonto
import cloud.dopp.homes.konto.HomesKonto.Teil
import cloud.dopp.homes.konto.KontoLauf
import cloud.dopp.homes.konto.KontoStore
import cloud.dopp.homes.sicherung.HandyKontakte
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Das Homes-Konto on a real Android (#206; e2e/live_e2e.sh, after
 * LiveE2ESetup paired the app with the fake Homes, which serves
 * `/napi/gemeinsam/…` as homes#218 does). Each switch turned on syncs through
 * Android's SyncManager: the shared contacts land in the account, a number
 * resolves to its name as the dialer and WhatsApp look it up, the household's
 * calendar is writable and the reminders' read only, and the reminder rings;
 * „Kontakte übertragen“ sends none of it, and the person's own contact Android
 * joined with a shared one keeps its UID and its own data. Writable (owner,
 * 2026-11-05): a contact saved into the account and an appointment made in
 * the household's calendar, as the contacts and calendar apps write them, go
 * to Homes and stay with Homes' version; a deleted contact goes; a change
 * Homes has another version of is dropped and said. The permissions come
 * with `adb install -g`.
 */
@RunWith(AndroidJUnit4::class)
class LiveE2EKonto {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var eigene = -1L

    @Before
    fun setUp() {
        HomesKonto.entfernen(ctx)
        // the person's own contact, on the phone's local account
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, "Oma Erna").build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, "+49 170 1111111").withValue(Phone.TYPE, Phone.TYPE_MOBILE).build(),
        )
        eigene = ContentUris.parseId(ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)[0].uri!!)
    }

    @After
    fun tearDown() {
        HomesKonto.entfernen(ctx)
        if (eigene > 0) {
            ctx.contentResolver.delete(
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, eigene).buildUpon()
                    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
                null, null,
            )
        }
    }

    private fun export(): String {
        val f = File(ctx.cacheDir, "e2e-kontakte.vcf")
        HandyKontakte(ctx).exportieren(f)
        return f.readText().also { f.delete() }
    }

    private fun uidVon(text: String, fn: String): String? =
        text.split("END:VCARD").firstOrNull { it.contains("FN:$fn\r\n") }
            ?.lineSequence()?.firstOrNull { it.startsWith("UID:") }?.removePrefix("UID:")?.trim()

    /** Sync [teil] now and wait for a run after this one ended. */
    private fun nochmal(teil: Teil) {
        val zeit = KontoStore(ctx).stand(teil)!!.zeit
        HomesKonto.jetzt(teil)
        var warte = 180
        while (KontoStore(ctx).stand(teil)!!.zeit <= zeit && warte-- > 0) Thread.sleep(500)
        assertTrue("no run of $teil; last word: ${KontoStore(ctx).meldung(teil)}", KontoStore(ctx).stand(teil)!!.zeit > zeit)
    }

    /** One column of the account's rows at [uri] that match [wo]. */
    private fun spalte(uri: Uri, spalte: String, wo: String, vararg args: String): List<String?> =
        ctx.contentResolver.query(uri, arrayOf(spalte), wo, arrayOf(*args), null)
            ?.use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }.orEmpty()

    /** Turn [teil] on and wait for its first good run through Android's SyncManager. */
    private fun einUndWarte(teil: Teil) {
        HomesKonto.einschalten(ctx, teil)
        val store = KontoStore(ctx)
        repeat(180) {
            if (store.stand(teil) != null) return
            Thread.sleep(500)
        }
        throw AssertionError("no sync of $teil within 90 s; last word: ${store.meldung(teil)}")
    }

    @Test
    fun theSharedContactsAreThePhonesAndNotTheArchives() {
        val vorher = export()
        val uid = uidVon(vorher, "Oma Erna")
        assertNotNull("the person's own contact is exported:\n$vorher", uid)

        einUndWarte(Teil.KONTAKTE)
        assertEquals(2, KontoStore(ctx).stand(Teil.KONTAKTE)!!.anzahl)

        // the dialer's and WhatsApp's lookup finds the shared number by its name
        val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode("+49 89 3333333"))
        val name = ctx.contentResolver.query(lookup, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        assertEquals("Klempner Huber", name)

        // shown without a group, as a phone's own contacts are
        val sichtbar = ctx.contentResolver.query(
            ContactsContract.Settings.CONTENT_URI, arrayOf(ContactsContract.Settings.UNGROUPED_VISIBLE),
            "${ContactsContract.Settings.ACCOUNT_TYPE} = ?", arrayOf(HomesKonto.TYP), null,
        )?.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        assertEquals(1, sichtbar)

        // „Kontakte übertragen“: the person's own Oma, with her own UID, nothing shared
        val nachher = export()
        assertEquals(nachher, uid, uidVon(nachher, "Oma Erna"))
        listOf("Klempner", "3333333", "2222222", "erna@homes.example").forEach {
            assertFalse("$it went into the archive:\n$nachher", nachher.contains(it))
        }

        // a second run: Homes' 304, nothing written again
        val zeit = KontoStore(ctx).stand(Teil.KONTAKTE)!!.zeit
        HomesKonto.jetzt(Teil.KONTAKTE)
        var warte = 120
        while (KontoStore(ctx).stand(Teil.KONTAKTE)!!.zeit <= zeit && warte-- > 0) Thread.sleep(500)
        assertTrue("no second run", KontoStore(ctx).stand(Teil.KONTAKTE)!!.zeit > zeit)
        assertEquals(2, KontoStore(ctx).stand(Teil.KONTAKTE)!!.anzahl)

        // saved into the Homes account as the contacts app writes it (not as the sync adapter): it goes to Homes
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, HomesKonto.TYP)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, HomesKonto.NAME).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, "Tante Inge").build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, "+49 30 4444444").withValue(Phone.TYPE, Phone.TYPE_MOBILE).build(),
        )
        val inge = ContentUris.parseId(ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)[0].uri!!).toString()
        // and Klempner Huber deleted as the contacts app deletes: it goes from Homes too
        val klempner = ctx.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.SYNC2} = ?", arrayOf(HomesKonto.TYP, "urn:uuid:e2e-klempner"), null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        assertNotNull("Klempner Huber's raw contact keeps its UID", klempner)
        ctx.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, klempner!!), null, null)
        nochmal(Teil.KONTAKTE)
        val roh = ContactsContract.RawContacts.CONTENT_URI
        assertEquals(listOf("\"2\""), spalte(roh, ContactsContract.RawContacts.SYNC3, "${ContactsContract.RawContacts._ID} = ?", inge))
        assertEquals(listOf("0"), spalte(roh, ContactsContract.RawContacts.DIRTY, "${ContactsContract.RawContacts._ID} = ?", inge))
        assertEquals(2, KontoStore(ctx).stand(Teil.KONTAKTE)!!.anzahl) // Oma and Tante Inge, Homes' versions
        assertEquals(null, KontoStore(ctx).meldung(Teil.KONTAKTE))

        // off: the shared contacts leave the phone
        HomesKonto.ausschalten(ctx, Teil.KONTAKTE)
        Thread.sleep(2000)
        val left = ctx.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.DELETED} = 0", arrayOf(HomesKonto.TYP), null,
        )?.use { it.count }
        assertEquals(0, left)
    }

    @Test
    fun theHouseholdsCalendarIsWritableAndTheReminderRings() {
        einUndWarte(Teil.KALENDER)
        val kalender = HashMap<String, Int>()
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?", arrayOf(HomesKonto.TYP), null,
        )?.use { c -> while (c.moveToNext()) kalender[c.getString(0)] = c.getInt(1) }
        assertEquals(setOf("Haushalt", "Erinnerungen"), kalender.keys)
        assertEquals("the household's calendar is writable", CalendarContract.Calendars.CAL_ACCESS_OWNER, kalender["Haushalt"])
        assertEquals("the reminders are read only", CalendarContract.Calendars.CAL_ACCESS_READ, kalender["Erinnerungen"])

        val termine = HashMap<String, Long>()
        ctx.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events._ID),
            "${CalendarContract.Events.ACCOUNT_TYPE} = ?", arrayOf(HomesKonto.TYP), null,
        )?.use { c -> while (c.moveToNext()) termine[c.getString(0)] = c.getLong(1) }
        assertEquals(setOf("Müll raus", "Arzt anrufen"), termine.keys)
        val alarm = ctx.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(termine.getValue("Arzt anrufen").toString()), null,
        )?.use { if (it.moveToFirst()) it.getInt(0) to it.getInt(1) else null }
        assertEquals(0 to CalendarContract.Reminders.METHOD_ALERT, alarm)
        // the instance is where a calendar app looks: 6 January 2030, 08:00 UTC
        val von = 1893888000000L // 2030-01-06T00:00Z
        val instanzen = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, von)
            ContentUris.appendId(it, von + 24 * 3600_000L)
        }.build()
        val gefunden = ctx.contentResolver.query(instanzen, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN), null, null, null)
            ?.use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to c.getLong(1) else null }.toList() }.orEmpty()
        assertTrue("$gefunden", gefunden.contains("Arzt anrufen" to von + 8 * 3600_000L))

        // made in the household's calendar as a calendar app writes it, with a reminder: it goes to Homes and back
        val haushalt = spalte(
            CalendarContract.Calendars.CONTENT_URI, CalendarContract.Calendars._ID,
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} = ?", HomesKonto.TYP, "Haushalt",
        ).single()!!.toLong()
        val zahnarzt = ContentUris.parseId(
            ctx.contentResolver.insert(
                CalendarContract.Events.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, haushalt)
                    put(CalendarContract.Events.TITLE, "Zahnarzt")
                    put(CalendarContract.Events.DTSTART, von + 2 * 24 * 3600_000L + 10 * 3600_000L)
                    put(CalendarContract.Events.DTEND, von + 2 * 24 * 3600_000L + 11 * 3600_000L)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "Europe/Berlin")
                },
            )!!,
        )
        ctx.contentResolver.insert(
            CalendarContract.Reminders.CONTENT_URI,
            ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, zahnarzt)
                put(CalendarContract.Reminders.MINUTES, 30)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            },
        )
        // and „Müll raus“ changed on the phone, which Homes has another version of
        ctx.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, termine.getValue("Müll raus")),
            ContentValues().apply { put(CalendarContract.Events.TITLE, "Müll raus!") }, null, null,
        )
        nochmal(Teil.KALENDER)
        val ereignis = CalendarContract.Events.CONTENT_URI
        val zeile = "${CalendarContract.Events._ID} = ?"
        assertEquals(listOf("\"2\""), spalte(ereignis, CalendarContract.Events.SYNC_DATA2, zeile, zahnarzt.toString()))
        assertEquals(listOf("0"), spalte(ereignis, CalendarContract.Events.DIRTY, zeile, zahnarzt.toString()))
        assertEquals(
            "the reminder went to Homes and came back",
            listOf("30"), spalte(CalendarContract.Reminders.CONTENT_URI, CalendarContract.Reminders.MINUTES, "${CalendarContract.Reminders.EVENT_ID} = ?", zahnarzt.toString()),
        )
        assertEquals("Homes' version wins", listOf("Müll raus"), spalte(ereignis, CalendarContract.Events.TITLE, zeile, termine.getValue("Müll raus").toString()))
        assertEquals(KontoLauf.VERWORFEN, KontoStore(ctx).meldung(Teil.KALENDER))
    }
}
