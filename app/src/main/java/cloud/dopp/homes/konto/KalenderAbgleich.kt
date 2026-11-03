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

/**
 * The Homes account's calendars (#206): each shared calendar of Homes one
 * calendar of the account, and „Erinnerungen“, the household's reminders as
 * entries that ring at their time — in the phone's own calendar app, not a
 * widget (the owner, 2026-11-03). Read only: every calendar is
 * CAL_ACCESS_READ, so calendar apps offer no editing; every write goes as
 * the sync adapter, so nothing is marked dirty to be sent back.
 *
 * A calendar's `_SYNC_ID` is Homes' id for it; an entry's is its UID, with
 * its RECURRENCE-ID for an instance of a series, and SYNC_DATA1 the state it
 * was written from ([IcalLeser.Termin.stand]): an unchanged entry is not
 * written. A calendar Homes no longer sends goes with its entries.
 */
class KalenderAbgleich(
    private val provider: ContentProviderClient,
    store: KontoStore,
    holen: (String?) -> GemeinsamClient.Antwort,
) : KontoLauf(HomesKonto.Teil.KALENDER, store, holen) {

    override val keineErlaubnis = KEINE_ERLAUBNIS

    override fun abgelehnt(code: Int) = "Homes hat die Kalender nicht geschickt ($code)."

    override fun anzahl(): Int =
        provider.query(alsAdapter(Events.CONTENT_URI), arrayOf(Events._ID), "$MEINE AND ${Events.DELETED} = 0", KONTO, null)
            ?.use { it.count } ?: 0

    public override fun schreiben(text: String): Int {
        val kalender = IcalLeser.lies(text).associateBy { it.id }
        val vorhanden = ArrayList<Abgleich.Zeile>()
        provider.query(alsAdapter(Calendars.CONTENT_URI), arrayOf(Calendars._ID, Calendars._SYNC_ID, Calendars.CALENDAR_DISPLAY_NAME), MEINE, KONTO, null)
            ?.use { c -> while (c.moveToNext()) vorhanden += Abgleich.Zeile(c.getLong(0), c.getString(1), c.getString(2)) }
        val plan = Abgleich.plane(vorhanden, kalender) { it.name }
        plan.weg.forEach { provider.delete(zeile(Calendars.CONTENT_URI, it), null, null) }
        plan.geaendert.forEach { (id, k) ->
            provider.update(zeile(Calendars.CONTENT_URI, id), ContentValues().apply { put(Calendars.CALENDAR_DISPLAY_NAME, k.name) }, null, null)
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
        plan.weg.forEach {
            ops += ContentProviderOperation.newDelete(zeile(Events.CONTENT_URI, it)).build()
            flush(ops)
        }
        plan.geaendert.forEach { (eid, t) ->
            ops += ContentProviderOperation.newUpdate(zeile(Events.CONTENT_URI, eid)).withValues(werte(t, erinnerung)).build()
            ops += ContentProviderOperation.newDelete(alsAdapter(Reminders.CONTENT_URI))
                .withSelection("${Reminders.EVENT_ID} = ?", arrayOf(eid.toString())).build()
            t.alarm?.let { m ->
                ops += ContentProviderOperation.newInsert(alsAdapter(Reminders.CONTENT_URI))
                    .withValue(Reminders.EVENT_ID, eid).withValue(Reminders.MINUTES, m)
                    .withValue(Reminders.METHOD, Reminders.METHOD_ALERT).build()
            }
            flush(ops)
        }
        plan.neu.forEach { t ->
            val start = ops.size
            ops += ContentProviderOperation.newInsert(alsAdapter(Events.CONTENT_URI))
                .withValues(werte(t, erinnerung)).withValue(Events.CALENDAR_ID, id).withValue(Events._SYNC_ID, t.syncId).build()
            t.alarm?.let { m ->
                ops += ContentProviderOperation.newInsert(alsAdapter(Reminders.CONTENT_URI))
                    .withValueBackReference(Reminders.EVENT_ID, start).withValue(Reminders.MINUTES, m)
                    .withValue(Reminders.METHOD, Reminders.METHOD_ALERT).build()
            }
            flush(ops)
        }
        flush(ops, alles = true)
        return kommend.size
    }

    private fun flush(ops: ArrayList<ContentProviderOperation>, alles: Boolean = false) {
        if (ops.isEmpty() || (!alles && ops.size < STAPEL)) return
        provider.applyBatch(ops)
        ops.clear()
    }

    companion object {
        const val KEINE_ERLAUBNIS =
            "Die App darf den Kalender nicht mehr schreiben: schalte bei „Kalender des Hauses“ den Schalter noch einmal ein."

        private const val STAPEL = 400
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
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
            put(Calendars.OWNER_ACCOUNT, HomesKonto.NAME)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            put(Calendars.ALLOWED_REMINDERS, Reminders.METHOD_ALERT.toString())
            put(Calendars.CAN_ORGANIZER_RESPOND, 0)
        }

        /** An entry's columns; a recurring one by RRULE and DURATION, a single one by DTEND. */
        fun werte(t: IcalLeser.Termin, erinnerung: Boolean) = ContentValues().apply {
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
            put(Events.HAS_ALARM, if (t.alarm != null) 1 else 0)
            put(Events.AVAILABILITY, if (erinnerung) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY)
            put(Events.SYNC_DATA1, t.stand)
        }

        /** RFC 5545's DURATION of [t]: whole days as `P<n>D`, else seconds. */
        fun dauer(t: IcalLeser.Termin): String {
            val s = (t.ende - t.beginn) / 1000
            return if (t.ganztags) "P${maxOf(1, s / 86400)}D" else "PT${s}S"
        }
    }
}
