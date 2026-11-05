package cloud.dopp.homes.konto

import cloud.dopp.homes.sicherung.Vcard
import java.util.TimeZone

/**
 * One appointment of the Homes account as the phone sends it back (owner,
 * 2026-11-05): one VCALENDAR with its VEVENT, the fields the phone shows and
 * nothing else — Homes keeps the rest of what it has. A whole day as DATE, a
 * single appointment in UTC, a series in its time zone (so it keeps its hour
 * across daylight saving) with its RRULE, EXDATE and a DURATION; each alarm
 * a VALARM. Text escaped and folded as a vCard's ([Vcard]). Pure.
 */
object IcalSchreiber {

    /** An appointment as CalendarContract holds it: [ende] null for a series, which has [dauer] instead. */
    data class Eintrag(
        val uid: String,
        val beginn: Long,
        val ende: Long?,
        val dauer: String?,
        val ganztags: Boolean,
        val zone: String?,
        val titel: String?,
        val ort: String?,
        val beschreibung: String?,
        val rrule: String?,
        val exdate: String?,
        val alarme: List<Int>,
    )

    fun text(e: Eintrag, jetzt: Long): String {
        val z = ArrayList<String>()
        z += listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//mdopp//Homes App//DE", "BEGIN:VEVENT")
        z += "UID:" + Vcard.text(e.uid)
        z += "DTSTAMP:" + utc(jetzt)
        val zone = e.zone?.takeIf { e.rrule != null && !e.ganztags && it != "UTC" && TimeZone.getTimeZone(it).id == it }
        z += "DTSTART" + zeit(e.beginn, e.ganztags, zone)
        if (e.rrule != null) {
            val s = e.dauer?.let(IcalLeser::dauer) ?: ((e.ende ?: e.beginn) - e.beginn) / 1000
            z += "DURATION:" + if (e.ganztags) "P${maxOf(1, s / 86400)}D" else "PT${s}S"
            z += "RRULE:" + e.rrule
        } else {
            z += "DTEND" + zeit(maxOf(e.ende ?: e.beginn, e.beginn), e.ganztags, null)
        }
        e.exdate?.let { z += exdate(it) }
        e.titel?.let { z += "SUMMARY:" + Vcard.text(it) }
        e.ort?.takeIf { it.isNotBlank() }?.let { z += "LOCATION:" + Vcard.text(it) }
        e.beschreibung?.takeIf { it.isNotBlank() }?.let { z += "DESCRIPTION:" + Vcard.text(it) }
        e.alarme.forEach { m ->
            z += listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:" + Vcard.text(e.titel ?: ""), "TRIGGER:-PT${m}M", "END:VALARM")
        }
        z += listOf("END:VEVENT", "END:VCALENDAR")
        return z.joinToString("") { Vcard.falte(it) + "\r\n" }
    }

    /** A DTSTART's or DTEND's parameters and value: a day as DATE, a time in [zone] or UTC. */
    private fun zeit(ms: Long, ganztags: Boolean, zone: String?): String = when {
        ganztags -> ";VALUE=DATE:" + IcalLeser.format("yyyyMMdd", UTC).format(ms)
        zone != null -> ";TZID=$zone:" + IcalLeser.format("yyyyMMdd'T'HHmmss", TimeZone.getTimeZone(zone)).format(ms)
        else -> ":" + utc(ms)
    }

    /** CalendarContract's EXDATE (`<zone>;<dates>`, or the dates) as one EXDATE line. */
    fun exdate(v: String): String {
        val zone = v.substringBefore(';', "").takeIf { it.isNotEmpty() }
        val daten = v.substringAfter(';')
        return when {
            zone != null && zone != "UTC" -> "EXDATE;TZID=$zone:$daten"
            daten.split(',').all { it.trim().length == 8 } -> "EXDATE;VALUE=DATE:$daten"
            else -> "EXDATE:$daten"
        }
    }

    private fun utc(ms: Long) = IcalLeser.format("yyyyMMdd'T'HHmmss'Z'", UTC).format(ms)

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
}
