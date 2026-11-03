package cloud.dopp.homes.sicherung

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How „Daten sichern“ writes sizes and times: German, pure. */
object Anzeige {

    private val RFC3339 = Regex("""(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(\.\d+)?([Zz]|[+-]\d{2}:\d{2})""")

    /** Homes' RFC 3339 time as epoch ms, or null. Without java.time: the app runs from API 23. */
    fun rfc3339(s: String?): Long? {
        val m = RFC3339.matchEntire(s?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
        c.clear()
        c.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        val ms = g[7].takeIf { it.isNotEmpty() }?.drop(1)?.padEnd(3, '0')?.take(3)?.toInt() ?: 0
        val zone = g[8]
        val versatz = if (zone.equals("Z", ignoreCase = true)) {
            0L
        } else {
            val sign = if (zone[0] == '-') -1 else 1
            sign * (zone.substring(1, 3).toLong() * 60 + zone.substring(4, 6).toLong()) * 60_000L
        }
        return c.timeInMillis + ms - versatz
    }

    /** `01.11.2026, 03:12` in the phone's time zone. */
    fun zeit(ms: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).apply { timeZone = zone }.format(Date(ms))

    /**
     * A granted folder as a person finds it in the Files app, from its tree
     * document id: `primary:Android/media/com.whatsapp/WhatsApp` →
     * `Interner Speicher › Android › media › com.whatsapp › WhatsApp`; another
     * volume (`1A2B-3C4D:Signal`) is the memory card; anything else as it is.
     */
    fun ordner(dokumentId: String): String {
        val (volume, pfad) = dokumentId.split(':', limit = 2).let { if (it.size == 2) it[0] to it[1] else return dokumentId }
        val wurzel = when {
            volume == "primary" -> "Interner Speicher"
            volume == "raw" -> return pfad
            Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(volume) -> "Speicherkarte"
            else -> return dokumentId
        }
        val teile = pfad.split('/').filter { it.isNotEmpty() }
        return (listOf(wurzel) + teile).joinToString(" › ")
    }

    /** `3,4 GB`, `120 MB`, `512 KB`, `12 B`: decimal units, as a person reads a disk. */
    fun groesse(bytes: Long): String {
        val einheiten = listOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1000 && i < einheiten.lastIndex) {
            v /= 1000
            i++
        }
        return if (i == 0 || v >= 100) {
            String.format(Locale.GERMANY, "%.0f %s", v, einheiten[i])
        } else {
            String.format(Locale.GERMANY, "%.1f %s", v, einheiten[i])
        }
    }

    /**
     * `1,2 von 9,8 GB`: [bytes] of [gesamt] in [gesamt]'s unit, so the two
     * read side by side; `0 von 120 MB` before the first byte.
     */
    fun anteil(bytes: Long, gesamt: Long): String {
        val einheiten = listOf("B", "KB", "MB", "GB", "TB")
        var teiler = 1.0
        var i = 0
        while (gesamt / teiler >= 1000 && i < einheiten.lastIndex) {
            teiler *= 1000
            i++
        }
        fun zahl(v: Double) = if (i == 0 || gesamt / teiler >= 100 || v == 0.0) {
            String.format(Locale.GERMANY, "%.0f", v)
        } else {
            String.format(Locale.GERMANY, "%.1f", v)
        }
        return "${zahl(bytes.coerceIn(0, gesamt) / teiler)} von ${zahl(gesamt / teiler)} ${einheiten[i]}"
    }

    /** `1.234`: a count as a person reads it in German. */
    fun zahl(n: Int): String = java.text.NumberFormat.getIntegerInstance(Locale.GERMANY).format(n)

    /**
     * What Homes' mirror of the contacts came to: `412 Kontakte, 3 neu,
     * 1 geändert`, with `2 entfernt` when any were; a change of none is left out.
     */
    fun kontakte(kontakte: Int, neu: Int, geaendert: Int, entfernt: Int): String =
        (listOf("${zahl(kontakte)} ${if (kontakte == 1) "Kontakt" else "Kontakte"}") +
            listOfNotNull(
                neu.takeIf { it > 0 }?.let { "${zahl(it)} neu" },
                geaendert.takeIf { it > 0 }?.let { "${zahl(it)} geändert" },
                entfernt.takeIf { it > 0 }?.let { "${zahl(it)} entfernt" },
            )).joinToString(", ")

    /** What the last transfer of SMS and calls carried: `1.234 SMS, 56 MMS, 789 Anrufe`; a kind with none is left out. */
    fun nachrichten(zahlen: Map<Art, Int>): String = listOfNotNull(
        zahlen[Art.SMS]?.takeIf { it > 0 }?.let { "${zahl(it)} SMS" },
        zahlen[Art.MMS]?.takeIf { it > 0 }?.let { "${zahl(it)} MMS" },
        zahlen[Art.ANRUFE]?.takeIf { it > 0 }?.let { "${zahl(it)} ${if (it == 1) "Anruf" else "Anrufe"}" },
    ).joinToString(", ")
}
