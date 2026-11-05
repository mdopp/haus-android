package cloud.dopp.homes.konto

import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import cloud.dopp.homes.data.GemeinsamClient
import cloud.dopp.homes.data.Napi.FeedIcal
import java.util.TimeZone
import java.util.UUID

/**
 * The Homes account's calendars (#206): each shared calendar of Homes one
 * calendar of the account, and „Erinnerungen“, the household's reminders as
 * entries that ring at their time — in the phone's own calendar app, not a
 * widget (the owner, 2026-11-03). Writable since the owner's call of
 * 2026-11-05: a calendar Homes lets the phone write ([IcalLeser.Kalender.schreibbar])
 * is CAL_ACCESS_OWNER, the others (subscriptions, the reminders) stay
 * CAL_ACCESS_READ. What a calendar app changes, adds or deletes there is
 * marked dirty and sent first ([senden]); Homes' version wins a conflict.
 *
 * A calendar's `_SYNC_ID` is Homes' id for it; an entry's is its UID, with
 * its RECURRENCE-ID for a changed instance of a series, SYNC_DATA1 the state it
 * was written from ([IcalLeser.Termin.stand]): an unchanged entry is not
 * written; SYNC_DATA2 its version in Homes, SYNC_DATA3 the calendar Homes has
 * it in. A calendar Homes no longer sends goes with its entries.
 *
 * A single changed instance of a series made on the phone is not sent (not
 * yet): it is dropped and said; a deleted single instance goes as an EXDATE of
 * its series.
 */
class KalenderAbgleich(
    private val provider: ContentProviderClient,
    store: KontoStore,
    holen: (String?) -> GemeinsamClient.Antwort,
    private val nachHomes: (String?, String, String?, String?) -> GemeinsamClient.Antwort = { _, _, _, _ -> GemeinsamClient.Antwort.Unerreichbar },
) : KontoLauf(HomesKonto.Teil.KALENDER, store, holen) {

    override val keineErlaubnis = KEINE_ERLAUBNIS

    override fun abgelehnt(code: Int) = "Homes hat die Kalender nicht geschickt ($code)."

    override fun anzahl(): Int =
        provider.query(alsAdapter(Events.CONTENT_URI), arrayOf(Events._ID), "$MEINE AND ${Events.DELETED} = 0", KONTO, null)
            ?.use { it.count } ?: 0

    public override fun senden(): String? {
        val schreibbar = HashMap<Long, String>()
        provider.query(
            alsAdapter(Calendars.CONTENT_URI), arrayOf(Calendars._ID, Calendars._SYNC_ID),
            "$MEINE AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ${Calendars.CAL_ACCESS_CONTRIBUTOR}", KONTO, null,
        )?.use { c -> while (c.moveToNext()) schreibbar[c.getLong(0)] = c.getString(1) }
        var verworfen: String? = null
        abgesagt()
        val zeilen = ArrayList<Pair<Long, Map<String, String?>>>()
        provider.query(alsAdapter(Events.CONTENT_URI), arrayOf(Events._ID) + SPALTEN, "$MEINE AND ${Events.DIRTY} = 1", KONTO, null)?.use { c ->
            while (c.moveToNext()) zeilen += c.getLong(0) to SPALTEN.associateWith { s -> c.getString(c.getColumnIndexOrThrow(s)) }
        }
        for ((id, z) in zeilen) verworfen = einer(id, z, z[Events.CALENDAR_ID]?.toLongOrNull()?.let(schreibbar::get)) ?: verworfen
        return verworfen
    }

    /** One dirty entry [id] ([z]) of [kalender] (null: one the phone does not write) to Homes: what was dropped, if anything. */
    private fun einer(id: Long, z: Map<String, String?>, kalender: String?): String? {
        var verworfen: String? = null
        val etag = z[Events.SYNC_DATA2]
        val instanz = z[Events.ORIGINAL_SYNC_ID] != null || z[Events.ORIGINAL_ID] != null
        when {
            // gone from the phone: from Homes too; else (refused, or read only) Homes' version comes back below
            z[Events.DELETED] == "1" || kalender == null -> {
                if (etag != null && kalender != null && !instanz) {
                    gesendet(nachHomes(z[Events.SYNC_DATA3] ?: kalender, z[Events._SYNC_ID].orEmpty(), null, etag)) { verworfen = it }
                }
                provider.delete(zeile(Events.CONTENT_URI, id), null, null)
            }
            instanz -> {
                provider.delete(zeile(Events.CONTENT_URI, id), null, null)
                verworfen = SERIE
            }
            else -> verworfen = schicken(id, z, kalender)
        }
        return verworfen
    }

    /** Send entry [id] as it is now ([z]) into [kalender]; moved from another of Homes' calendars, out of that one after. */
    private fun schicken(id: Long, z: Map<String, String?>, kalender: String): String? {
        var verworfen: String? = null
        val etag = z[Events.SYNC_DATA2]
        val heim = z[Events.SYNC_DATA3]
        val uid = z[Events._SYNC_ID]?.takeIf { etag != null } ?: UUID.randomUUID().toString()
        val umgezogen = etag != null && heim != null && heim != kalender
        val neu = gesendet(nachHomes(kalender, uid, eintrag(id, uid, z), etag.takeUnless { umgezogen })) { verworfen = it }
        if (neu != null && umgezogen) gesendet(nachHomes(heim, uid, null, etag)) { }
        val werte = ContentValues().apply {
            put(Events.DIRTY, 0)
            putNull(Events.SYNC_DATA1)
            if (neu != null) {
                put(Events._SYNC_ID, uid)
                put(Events.SYNC_DATA2, neu)
                put(Events.SYNC_DATA3, kalender)
            }
        }
        provider.update(zeile(Events.CONTENT_URI, id), werte, null, null)
        return verworfen
    }

    /** The appointment [id] as the phone has it, as iCalendar. */
    private fun eintrag(id: Long, uid: String, z: Map<String, String?>): String {
        val alarme = ArrayList<Int>()
        provider.query(alsAdapter(Reminders.CONTENT_URI), arrayOf(Reminders.MINUTES), "${Reminders.EVENT_ID} = ?", arrayOf(id.toString()), null)
            ?.use { c -> while (c.moveToNext()) alarme += maxOf(0, c.getInt(0)) }
        return IcalSchreiber.text(
            IcalSchreiber.Eintrag(
                uid, z[Events.DTSTART]?.toLongOrNull() ?: 0L, z[Events.DTEND]?.toLongOrNull(), z[Events.DURATION], z[Events.ALL_DAY] == "1",
                z[Events.EVENT_TIMEZONE], z[Events.TITLE], z[Events.EVENT_LOCATION], z[Events.DESCRIPTION], z[Events.RRULE], z[Events.EXDATE], alarme,
            ),
            System.currentTimeMillis(),
        )
    }

    /**
     * A single instance deleted on the phone (a cancelled exception, as calendar apps write it): an EXDATE of its
     * series, which is then sent; the exception goes.
     */
    private fun abgesagt() {
        val ausnahmen = ArrayList<Triple<Long, Long, Long>>()
        provider.query(
            alsAdapter(Events.CONTENT_URI), arrayOf(Events._ID, Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME),
            "$MEINE AND ${Events.DIRTY} = 1 AND ${Events.STATUS} = ${Events.STATUS_CANCELED} AND ${Events.ORIGINAL_ID} IS NOT NULL", KONTO, null,
        )?.use { c -> while (c.moveToNext()) ausnahmen += Triple(c.getLong(0), c.getLong(1), c.getLong(2)) }
        for ((id, serie, zeit) in ausnahmen) {
            provider.query(zeile(Events.CONTENT_URI, serie), arrayOf(Events.EXDATE, Events.ALL_DAY), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use
                val bisher = c.getString(0)
                val zone = bisher?.substringBefore(';', "")?.ifEmpty { null }
                val neu = when {
                    c.getInt(1) == 1 -> IcalLeser.format("yyyyMMdd", UTC).format(zeit)
                    zone != null -> IcalLeser.format("yyyyMMdd'T'HHmmss", TimeZone.getTimeZone(zone)).format(zeit)
                    else -> IcalLeser.format("yyyyMMdd'T'HHmmss'Z'", UTC).format(zeit)
                }
                val werte = ContentValues().apply {
                    put(Events.EXDATE, if (bisher.isNullOrEmpty()) neu else "$bisher,$neu")
                    put(Events.DIRTY, 1)
                }
                provider.update(zeile(Events.CONTENT_URI, serie), werte, null, null)
            }
            provider.delete(zeile(Events.CONTENT_URI, id), null, null)
        }
    }

    public override fun schreiben(text: String): Int {
        val kalender = IcalLeser.lies(text).associateBy { it.id }
        val vorhanden = ArrayList<Abgleich.Zeile>()
        provider.query(
            alsAdapter(Calendars.CONTENT_URI), arrayOf(Calendars._ID, Calendars._SYNC_ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_ACCESS_LEVEL),
            MEINE, KONTO, null,
        )?.use { c -> while (c.moveToNext()) vorhanden += Abgleich.Zeile(c.getLong(0), c.getString(1), "${c.getString(2)}\u0001${c.getInt(3)}") }
        val plan = Abgleich.plane(vorhanden, kalender) { "${it.name}\u0001${zugriff(it)}" }
        plan.weg.forEach { provider.delete(zeile(Calendars.CONTENT_URI, it), null, null) }
        plan.geaendert.forEach { (id, k) ->
            val werte = ContentValues().apply {
                put(Calendars.CALENDAR_DISPLAY_NAME, k.name)
                put(Calendars.CALENDAR_ACCESS_LEVEL, zugriff(k))
            }
            provider.update(zeile(Calendars.CONTENT_URI, id), werte, null, null)
        }
        val ids = HashMap<String, Long>()
        vorhanden.filter { it.id !in plan.weg }.forEach { z -> z.syncId?.let { ids[it] = z.id } }
        kalender.values.sortedBy { reihe(it) }.forEachIndexed { i, k ->
            if (k.id !in ids) {
                val uri = provider.insert(alsAdapter(Calendars.CONTENT_URI), kalenderWerte(k, i)) ?: return@forEachIndexed
                ids[k.id] = ContentUris.parseId(uri)
            }
        }
        var n = 0
        for (k in kalender.values) {
            val id = ids[k.id] ?: continue
            n += termine(id, k)
        }
        return n
    }

    /** The entries of calendar [id] as Homes sent [k]: how many it holds. */
    private fun termine(id: Long, k: IcalLeser.Kalender): Int {
        val kommend = k.termine.associateBy { it.syncId }
        val vorhanden = ArrayList<Abgleich.Zeile>()
        provider.query(
            alsAdapter(Events.CONTENT_URI), arrayOf(Events._ID, Events._SYNC_ID, Events.SYNC_DATA1),
            "${Events.CALENDAR_ID} = ?", arrayOf(id.toString()), null,
        )?.use { c -> while (c.moveToNext()) vorhanden += Abgleich.Zeile(c.getLong(0), c.getString(1), c.getString(2)) }
        val plan = Abgleich.plane(vorhanden, kommend) { it.stand }
        val erinnerung = k.id == FeedIcal.ERINNERUNGEN
        val ops = ArrayList<ContentProviderOperation>()
        fun alarme(t: IcalLeser.Termin, eid: Long?, start: Int) = t.alarme.forEach { m ->
            val op = ContentProviderOperation.newInsert(alsAdapter(Reminders.CONTENT_URI))
            ops += (if (eid != null) op.withValue(Reminders.EVENT_ID, eid) else op.withValueBackReference(Reminders.EVENT_ID, start))
                .withValue(Reminders.MINUTES, m).withValue(Reminders.METHOD, Reminders.METHOD_ALERT).build()
        }
        plan.weg.forEach {
            ops += ContentProviderOperation.newDelete(zeile(Events.CONTENT_URI, it)).build()
            provider.flush(ops)
        }
        plan.geaendert.forEach { (eid, t) ->
            ops += ContentProviderOperation.newUpdate(zeile(Events.CONTENT_URI, eid)).withValues(werte(t, erinnerung, k.id)).build()
            ops += ContentProviderOperation.newDelete(alsAdapter(Reminders.CONTENT_URI))
                .withSelection("${Reminders.EVENT_ID} = ?", arrayOf(eid.toString())).build()
            alarme(t, eid, 0)
            provider.flush(ops)
        }
        plan.neu.sortedBy { it.instanz != null }.forEach { t -> // a series before its changed instances
            val start = ops.size
            ops += ContentProviderOperation.newInsert(alsAdapter(Events.CONTENT_URI))
                .withValues(werte(t, erinnerung, k.id)).withValue(Events.CALENDAR_ID, id).withValue(Events._SYNC_ID, t.syncId).build()
            alarme(t, null, start)
            provider.flush(ops)
        }
        provider.flush(ops, alles = true)
        return kommend.size
    }

    companion object {
        const val KEINE_ERLAUBNIS =
            "Die App darf den Kalender nicht mehr schreiben: schalte bei „Kalender des Hauses“ den Schalter noch einmal ein."

        const val SERIE = "Einen einzelnen Termin einer Serie ändert das Handy noch nicht im Haus: Die Änderung ist verworfen. Ändere die ganze Serie, oder den Termin in Homes."

        private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

        /** What [senden] reads of a dirty entry. */
        private val SPALTEN = arrayOf(
            Events._SYNC_ID, Events.CALENDAR_ID, Events.DELETED, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.ORIGINAL_ID,
            Events.ORIGINAL_SYNC_ID, Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE,
            Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.RRULE, Events.EXDATE,
        )

        /** A calendar the phone writes into is its own to change; the others are read only. */
        fun zugriff(k: IcalLeser.Kalender) = if (k.schreibbar) Calendars.CAL_ACCESS_OWNER else Calendars.CAL_ACCESS_READ
        private const val MEINE = "${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.ACCOUNT_NAME} = ?"
        private val KONTO = arrayOf(HomesKonto.TYP, HomesKonto.NAME)

        /** [uri] as the sync adapter of the Homes account writes it. */
        fun alsAdapter(uri: Uri): Uri = uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, HomesKonto.NAME)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, HomesKonto.TYP)
            .build()

        private fun zeile(uri: Uri, id: Long): Uri = alsAdapter(ContentUris.withAppendedId(uri, id))

        /** The Haushalt first, then the family's, the subscriptions, the reminders last. */
        fun reihe(k: IcalLeser.Kalender): String = when (k.art) {
            "haushalt" -> "0"
            "familie" -> "1"
            "abo" -> "2"
            FeedIcal.ERINNERUNGEN -> "4"
            else -> "3"
        } + k.name.lowercase()

        /** A calendar's colour: the house's green for the Haushalt, the reminders amber, the rest from a short list. */
        fun farbe(k: IcalLeser.Kalender, i: Int): Int = when (k.art) {
            "haushalt" -> 0xFF3DDC84.toInt()
            FeedIcal.ERINNERUNGEN -> 0xFFF6B93B.toInt()
            else -> FARBEN[i % FARBEN.size]
        }

        private val FARBEN = intArrayOf(0xFF4F8EF7.toInt(), 0xFFB57CF2.toInt(), 0xFFF2789F.toInt(), 0xFF2EC4B6.toInt(), 0xFF9AA5B1.toInt())

        fun kalenderWerte(k: IcalLeser.Kalender, i: Int) = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, HomesKonto.NAME)
            put(Calendars.ACCOUNT_TYPE, HomesKonto.TYP)
            put(Calendars.NAME, k.id)
            put(Calendars._SYNC_ID, k.id)
            put(Calendars.CALENDAR_DISPLAY_NAME, k.name)
            put(Calendars.CALENDAR_COLOR, farbe(k, i))
            put(Calendars.CALENDAR_ACCESS_LEVEL, zugriff(k))
            put(Calendars.OWNER_ACCOUNT, HomesKonto.NAME)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            put(Calendars.ALLOWED_REMINDERS, Reminders.METHOD_ALERT.toString())
            put(Calendars.CAN_ORGANIZER_RESPOND, 0)
        }

        /** An entry's columns; a recurring one by RRULE and DURATION, a single one by DTEND; a changed instance by its series. */
        fun werte(t: IcalLeser.Termin, erinnerung: Boolean, kalender: String) = ContentValues().apply {
            put(Events.TITLE, t.titel)
            put(Events.EVENT_LOCATION, t.ort)
            put(Events.DESCRIPTION, t.beschreibung)
            put(Events.DTSTART, t.beginn)
            put(Events.ALL_DAY, if (t.ganztags) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (t.ganztags) "UTC" else t.zone)
            if (t.rrule != null) {
                put(Events.RRULE, t.rrule)
                put(Events.DURATION, dauer(t))
                putNull(Events.DTEND)
            } else {
                put(Events.DTEND, t.ende)
                putNull(Events.RRULE)
                putNull(Events.DURATION)
            }
            put(Events.HAS_ALARM, if (t.alarme.isEmpty()) 0 else 1)
            put(Events.AVAILABILITY, if (erinnerung) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY)
            put(Events.EXDATE, t.exdate)
            if (t.original != null) {
                put(Events.ORIGINAL_SYNC_ID, t.uid)
                put(Events.ORIGINAL_INSTANCE_TIME, t.original)
                put(Events.ORIGINAL_ALL_DAY, if (t.ganztags) 1 else 0)
            }
            put(Events.SYNC_DATA1, t.stand)
            put(Events.SYNC_DATA2, t.etag)
            put(Events.SYNC_DATA3, kalender)
            put(Events.DIRTY, 0)
        }

        /** RFC 5545's DURATION of [t]: whole days as `P<n>D`, else seconds. */
        fun dauer(t: IcalLeser.Termin): String {
            val s = (t.ende - t.beginn) / 1000
            return if (t.ganztags) "P${maxOf(1, s / 86400)}D" else "PT${s}S"
        }
    }
}
