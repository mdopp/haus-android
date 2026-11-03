package cloud.dopp.haus.e2e

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dopp.haus.konto.HausKonto
import cloud.dopp.haus.konto.HausKonto.Teil
import cloud.dopp.haus.konto.KontoStore
import cloud.dopp.haus.sicherung.HandyKontakte
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
 * Das Haus-Konto on a real Android (#206; e2e/live_e2e.sh, after
 * LiveE2ESetup paired the app with the fake Haus, which serves
 * `/napi/gemeinsam/…` as haus#218 does). Each switch turned on syncs through
 * Android's SyncManager: the shared contacts land in the account, a number
 * resolves to its name as the dialer and WhatsApp look it up, the calendars
 * are read only and the reminder rings; „Kontakte übertragen“ sends none of
 * it, and the person's own contact Android joined with a shared one keeps
 * its UID and its own data. The permissions come with `adb install -g`.
 */
@RunWith(AndroidJUnit4::class)
class LiveE2EKonto {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var eigene = -1L

    @Before
    fun setUp() {
        HausKonto.entfernen(ctx)
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
        HausKonto.entfernen(ctx)
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

    /** Turn [teil] on and wait for its first good run through Android's SyncManager. */
    private fun einUndWarte(teil: Teil) {
        HausKonto.einschalten(ctx, teil)
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
            "${ContactsContract.Settings.ACCOUNT_TYPE} = ?", arrayOf(HausKonto.TYP), null,
        )?.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        assertEquals(1, sichtbar)

        // „Kontakte übertragen“: the person's own Oma, with her own UID, nothing shared
        val nachher = export()
        assertEquals(nachher, uid, uidVon(nachher, "Oma Erna"))
        listOf("Klempner", "3333333", "2222222", "erna@haus.example").forEach {
            assertFalse("$it went into the archive:\n$nachher", nachher.contains(it))
        }

        // a second run: Haus's 304, nothing written again
        val zeit = KontoStore(ctx).stand(Teil.KONTAKTE)!!.zeit
        HausKonto.jetzt(Teil.KONTAKTE)
        var warte = 120
        while (KontoStore(ctx).stand(Teil.KONTAKTE)!!.zeit <= zeit && warte-- > 0) Thread.sleep(500)
        assertTrue("no second run", KontoStore(ctx).stand(Teil.KONTAKTE)!!.zeit > zeit)
        assertEquals(2, KontoStore(ctx).stand(Teil.KONTAKTE)!!.anzahl)

        // off: the shared contacts leave the phone
        HausKonto.ausschalten(ctx, Teil.KONTAKTE)
        Thread.sleep(2000)
        val left = ctx.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.DELETED} = 0", arrayOf(HausKonto.TYP), null,
        )?.use { it.count }
        assertEquals(0, left)
    }

    @Test
    fun theSharedCalendarsAreReadOnlyAndTheReminderRings() {
        einUndWarte(Teil.KALENDER)
        val kalender = HashMap<String, Int>()
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?", arrayOf(HausKonto.TYP), null,
        )?.use { c -> while (c.moveToNext()) kalender[c.getString(0)] = c.getInt(1) }
        assertEquals(setOf("Haushalt", "Erinnerungen"), kalender.keys)
        kalender.values.forEach { assertEquals("read only", CalendarContract.Calendars.CAL_ACCESS_READ, it) }

        val termine = HashMap<String, Long>()
        ctx.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events._ID),
            "${CalendarContract.Events.ACCOUNT_TYPE} = ?", arrayOf(HausKonto.TYP), null,
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
    }
}
