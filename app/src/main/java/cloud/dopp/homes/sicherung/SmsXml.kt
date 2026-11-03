package cloud.dopp.homes.sicherung

import org.bouncycastle.util.encoders.Base64
import java.io.InputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The phone's SMS, MMS and calls as XML in the format of „SMS Backup &
 * Restore“ by SyncTech: what the box imports (`msgvault import-synctech-sms`).
 * Messages go in `<smses count=…>` with `<sms …/>` and `<mms …><parts>…</parts>
 * <addrs>…</addrs></mms>`, the call log in its own `<calls count=…>` file.
 *
 * Pure and streaming: each element is written as it comes, an MMS part's
 * media are base64 in pieces from a stream, never all in memory. An attribute
 * is a provider column by its name, as SyncTech writes it; an absent value is
 * `null`, as SyncTech writes it.
 */
class SmsXml(private val w: Writer, private val zone: TimeZone = TimeZone.getDefault()) {

    /** `<smses count=…>`: [anzahl] messages follow. */
    fun nachrichtenAnfang(anzahl: Int, stand: Long) {
        w.write(XML_KOPF)
        w.write("<smses count=\"$anzahl\" backup_date=\"$stand\" type=\"incremental\">\n")
    }

    fun nachrichtenEnde() = w.write("</smses>\n")

    /** `<calls count=…>`: [anzahl] calls follow. */
    fun anrufeAnfang(anzahl: Int, stand: Long) {
        w.write(XML_KOPF)
        w.write("<calls count=\"$anzahl\" backup_date=\"$stand\" type=\"incremental\">\n")
    }

    fun anrufeEnde() = w.write("</calls>\n")

    /** One SMS: [spalten] as the provider has them (date in ms), then its readable date and the contact's name. */
    fun sms(spalten: List<Pair<String, String?>>, datum: Long, kontakt: String?) {
        element("  <sms", spalten + lesbar(datum, kontakt), "/>\n")
    }

    /** One call, as [sms]. */
    fun anruf(spalten: List<Pair<String, String?>>, datum: Long, kontakt: String?) {
        element("  <call", spalten + lesbar(datum, kontakt), "/>\n")
    }

    /** An MMS opens: its columns (date in ms), the address line (`a~b`), readable date, contact name. */
    fun mmsAnfang(spalten: List<Pair<String, String?>>, adresse: String, datum: Long, kontakt: String?) {
        element("  <mms", spalten + ("address" to adresse) + lesbar(datum, kontakt), ">\n")
        w.write("    <parts>\n")
    }

    /**
     * One part: its columns (`seq`, `ct`, `name`, `chset`, `cl`, `text`, …) and,
     * for media, [daten] as the `data` attribute in base64, read in pieces.
     */
    fun teil(spalten: List<Pair<String, String?>>, daten: InputStream?) {
        element("      <part", spalten, if (daten == null) "/>\n" else null)
        if (daten != null) {
            w.write(" data=\"")
            base64(daten)
            w.write("\"/>\n")
        }
    }

    /** The parts are done; the addresses follow, each `address`, `type` (137 from, 151 to, 130 cc, 129 bcc), `charset`. */
    fun adressen(adressen: List<List<Pair<String, String?>>>) {
        w.write("    </parts>\n")
        w.write("    <addrs>\n")
        adressen.forEach { element("      <addr", it, "/>\n") }
        w.write("    </addrs>\n")
    }

    fun mmsEnde() = w.write("  </mms>\n")

    private fun lesbar(datum: Long, kontakt: String?): List<Pair<String, String?>> =
        listOf("readable_date" to lesbaresDatum(datum, zone), "contact_name" to (kontakt?.ifBlank { null } ?: UNBEKANNT))

    /** `<name a="…" b="…"` and then [schluss], or nothing more when [schluss] is null. */
    private fun element(name: String, attribute: List<Pair<String, String?>>, schluss: String?) {
        w.write(name)
        for ((k, v) in attribute) {
            w.write(" ")
            w.write(k)
            w.write("=\"")
            w.write(attribut(v ?: NULL))
            w.write("\"")
        }
        if (schluss != null) w.write(if (schluss.startsWith("/")) " $schluss" else schluss)
    }

    /** [ein] as base64, in pieces of whole 3-byte groups: only the last one is padded. */
    private fun base64(ein: InputStream) {
        val puffer = ByteArray(BASE64_STUECK)
        while (true) {
            var n = 0
            while (n < puffer.size) {
                val r = ein.read(puffer, n, puffer.size - n)
                if (r < 0) break
                n += r
            }
            if (n > 0) w.write(Base64.toBase64String(puffer, 0, n))
            if (n < puffer.size) return
        }
    }

    companion object {
        const val XML_KOPF = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"

        /** SyncTech's word for a value the phone does not have. */
        const val NULL = "null"

        /** SyncTech's name for a number not in the address book. */
        const val UNBEKANNT = "(Unknown)"

        /** A multiple of 3, so every piece but the last encodes without padding. */
        private const val BASE64_STUECK = 3 * 8192

        /** `02.11.2026 03:12:45` in [zone]. */
        fun lesbaresDatum(ms: Long, zone: TimeZone = TimeZone.getDefault()): String =
            SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).apply { timeZone = zone }.format(Date(ms))

        /**
         * [s] as the value of an XML attribute in double quotes: `& < > " '`
         * as entities, a newline, carriage return and tab as character
         * references (a parser would fold them into spaces otherwise), an
         * emoji as itself in UTF-8. A character XML 1.0 does not allow at all
         * (a control character, a lone surrogate) becomes U+FFFD: a strict
         * parser refuses the whole file for one.
         */
        fun attribut(s: String): String {
            val out = StringBuilder(s.length + 16)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '&' -> out.append("&amp;")
                    c == '<' -> out.append("&lt;")
                    c == '>' -> out.append("&gt;")
                    c == '"' -> out.append("&quot;")
                    c == '\'' -> out.append("&apos;")
                    c == '\n' -> out.append("&#10;")
                    c == '\r' -> out.append("&#13;")
                    c == '\t' -> out.append("&#9;")
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                        out.append(c).append(s[i + 1])
                        i++
                    }
                    c < ' ' || Character.isSurrogate(c) || c == '￾' || c == '￿' -> out.append('�')
                    else -> out.append(c)
                }
                i++
            }
            return out.toString()
        }
    }
}
