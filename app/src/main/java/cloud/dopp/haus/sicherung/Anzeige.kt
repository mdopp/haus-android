package cloud.dopp.haus.sicherung

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How „Nachrichten sichern“ writes sizes and times: German, pure. */
object Anzeige {

    private val RFC3339 = Regex("""(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(\.\d+)?([Zz]|[+-]\d{2}:\d{2})""")

    /** Haus's RFC 3339 time as epoch ms, or null. Without java.time: the app runs from API 23. */
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
}
