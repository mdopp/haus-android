package cloud.dopp.homes.konto

import cloud.dopp.homes.data.Napi.FeedIcal
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The household's calendars as Homes sends them (`/napi/gemeinsam/kalender`,
 * #206): an iCalendar stream (RFC 5545 §3.4), one VCALENDAR per shared
 * calendar, named by `X-WR-CALNAME` and identified by `X-HAUS-KALENDER`
 * ([FeedIcal]). Each VEVENT becomes a [Termin], the shape the phone's
 * calendar takes: start and end in epoch ms, a whole day as UTC midnights
 * (as CalendarContract wants it), a time zone for a recurring one. Series
 * come whole (`?schreibbar=1`, owner 2026-11-05): the series with its RRULE
 * and EXDATE, each changed instance with its RECURRENCE-ID, every alarm,
 * and the version ([FeedIcal.ETAG]) the phone writes back over. Pure.
 */
object IcalLeser {

    data class Kalender(val id: String, val name: String, val art: String, val termine: List<Termin>, val schreibbar: Boolean = false)

    data class Termin(
        val uid: String,
        /** RECURRENCE-ID as written: which instance of a series; null for a single one. */
        val instanz: String?,
        val beginn: Long,
        val ende: Long,
        val ganztags: Boolean,
        /** The time zone the start was written in; `UTC` for UTC and whole days. */
        val zone: String,
        val titel: String,
        val ort: String? = null,
        val beschreibung: String? = null,
        val rrule: String? = null,
        /** Minutes before the start each display alarm rings. */
        val alarme: List<Int> = emptyList(),
        /** EXDATE as CalendarContract keeps it: `<zone>;<dates>` or the UTC or whole-day dates. */
        val exdate: String? = null,
        /** A changed instance: the start it replaces, epoch ms. */
        val original: Long? = null,
        /** The version Homes has, the phone writes back over it. */
        val etag: String? = null,
    ) {
        /** The entry's id on the phone (`_SYNC_ID`). */
        val syncId: String get() = if (instanz == null) uid else "$uid#$instanz"

        /** Whether the phone's entry must be written again: every field the phone shows, and the version. */
        val stand: String
            get() = listOf(beginn, ende, ganztags, zone, titel, ort, beschreibung, rrule, alarme, exdate, original, etag).joinToString("\u0001")
    }

    fun lies(text: String): List<Kalender> {
        val out = ArrayList<Kalender>()
        var kal: MutableMap<String, String>? = null
        var termine = ArrayList<Termin>()
        var ereignis: MutableList<VcardLeser.Zeile>? = null
        var alarm: MutableList<VcardLeser.Zeile>? = null
        val alarme = ArrayList<List<VcardLeser.Zeile>>()
        for (roh in VcardLeser.entfalte(text)) {
            if (roh.isBlank()) continue
            val z = VcardLeser.zeile(roh) ?: continue
            when {
                z.name == "BEGIN" && z.wert.equals("VCALENDAR", true) -> {
                    kal = HashMap()
                    termine = ArrayList()
                }
                z.name == "END" && z.wert.equals("VCALENDAR", true) -> {
                    val k = kal ?: continue
                    val id = k[FeedIcal.KALENDER]?.ifBlank { null }
                    if (id != null) {
                        out += Kalender(id, k[FeedIcal.NAME]?.ifBlank { null } ?: id, k[FeedIcal.ART].orEmpty(), termine, k[FeedIcal.SCHREIBBAR] == "TRUE")
                    }
                    kal = null
                }
                z.name == "BEGIN" && z.wert.equals("VEVENT", true) -> ereignis = ArrayList()
                z.name == "END" && z.wert.equals("VEVENT", true) -> {
                    ereignis?.let { e -> termin(e, alarme)?.let(termine::add) }
                    ereignis = null
                    alarme.clear()
                }
                z.name == "BEGIN" && z.wert.equals("VALARM", true) -> if (ereignis != null) alarm = ArrayList()
                z.name == "END" && z.wert.equals("VALARM", true) -> {
                    alarm?.let(alarme::add)
                    alarm = null
                }
                alarm != null -> alarm.add(z)
                ereignis != null -> ereignis.add(z)
                kal != null && z.name in KOPF -> kal[z.name] = VcardLeser.text(z.wert).trim()
            }
        }
        return out
    }

    private val KOPF = setOf(FeedIcal.NAME, FeedIcal.KALENDER, FeedIcal.ART, FeedIcal.SCHREIBBAR)

    private fun termin(e: List<VcardLeser.Zeile>, alarme: List<List<VcardLeser.Zeile>>): Termin? {
        fun eins(name: String) = e.firstOrNull { it.name == name }
        fun text(name: String) = eins(name)?.let { VcardLeser.text(it.wert).trim() }?.ifEmpty { null }
        val uid = text("UID") ?: return null
        val start = eins("DTSTART")?.let(::zeit) ?: return null
        val ende = eins("DTEND")?.let(::zeit)?.ms
            ?: eins("DURATION")?.let { dauer(it.wert) }?.let { start.ms + it * 1000L }
            ?: if (start.ganztags) start.ms + TAG else start.ms
        return Termin(
            uid = uid,
            instanz = eins("RECURRENCE-ID")?.wert?.trim()?.ifEmpty { null },
            beginn = start.ms,
            ende = maxOf(ende, start.ms),
            ganztags = start.ganztags,
            zone = start.zone,
            titel = text("SUMMARY") ?: "",
            ort = text("LOCATION"),
            beschreibung = text("DESCRIPTION"),
            rrule = eins("RRULE")?.wert?.trim()?.ifEmpty { null },
            alarme = alarme.map { a -> a.firstOrNull { it.name == "TRIGGER" }?.let { t -> dauer(t.wert) }?.let { s -> maxOf(0, (-s / 60).toInt()) } ?: 0 },
            exdate = ausnahmen(e.filter { it.name == "EXDATE" }),
            original = eins("RECURRENCE-ID")?.let(::zeit)?.ms,
            etag = text(FeedIcal.ETAG),
        )
    }

    /** EXDATE lines as CalendarContract's column: `<zone>;<dates>` when the first names a zone, the dates joined by commas. */
    private fun ausnahmen(z: List<VcardLeser.Zeile>): String? {
        if (z.isEmpty()) return null
        val zone = z.first().parameter["TZID"]?.firstOrNull()?.trimStart('/')
        val daten = z.joinToString(",") { it.wert.trim() }
        return if (zone != null) "$zone;$daten" else daten
    }

    /** A DTSTART or DTEND: epoch ms, whether a whole day, the zone it was written in. */
    data class Zeit(val ms: Long, val ganztags: Boolean, val zone: String)

    fun zeit(z: VcardLeser.Zeile): Zeit? {
        val v = z.wert.trim()
        val typ = z.parameter["VALUE"]?.firstOrNull()
        return try {
            when {
                typ.equals("DATE", true) || v.length == 8 -> Zeit(format("yyyyMMdd", UTC).parse(v)!!.time, true, "UTC")
                v.endsWith("Z") -> Zeit(format("yyyyMMdd'T'HHmmss'Z'", UTC).parse(v)!!.time, false, "UTC")
                else -> {
                    val zone = z.parameter["TZID"]?.firstOrNull()?.trimStart('/')?.let(::bekannt) ?: TimeZone.getDefault()
                    Zeit(format("yyyyMMdd'T'HHmmss", zone).parse(v)!!.time, false, zone.id)
                }
            }
        } catch (e: ParseException) {
            null
        }
    }

    /** A zone by its IANA name; null when the phone does not know it. */
    private fun bekannt(id: String): TimeZone? = TimeZone.getTimeZone(id).takeIf { it.id == id }

    /** A DURATION in seconds (`PT1H30M`, `-PT15M`, `P1D`, and Android's own `P3600S`); null when unreadable. */
    fun dauer(v: String): Long? {
        val m = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$""").matchEntire(v.trim()) ?: return null
        fun n(i: Int) = m.groupValues[i].ifEmpty { "0" }.toLong()
        val s = n(2) * 7 * 86400 + n(3) * 86400 + n(4) * 3600 + n(5) * 60 + n(6)
        return if (m.groupValues[1] == "-") -s else s
    }

    fun format(muster: String, zone: TimeZone) =
        SimpleDateFormat(muster, Locale.ROOT).apply { timeZone = zone; isLenient = false }

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private const val TAG = 24L * 60 * 60 * 1000
}
