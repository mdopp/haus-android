package cloud.dopp.haus.sicherung

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import cloud.dopp.haus.data.Napi.Quelle
import cloud.dopp.haus.data.SicherungClient

/**
 * Nachrichten sichern's own prefs (#194): the granted folders, the backup sent
 * last per source, each source's last word and when a file last arrived, and
 * the ids of unfinished uploads; the two switches for contacts and for SMS and
 * calls, the high-water marks of SMS, MMS and calls, and the contacts' last
 * result (laib ADR 0029). Plain prefs: nothing here is a secret, and the app
 * never stores a messenger's key; the archive key is a public one, asked anew
 * each run.
 */
class SicherungStore(ctx: Context) : Gedaechtnis, Marken, Kontaktgedaechtnis {

    private val p: SharedPreferences = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The folder granted for [quelle] (a tree URI), or null. */
    fun ordner(quelle: Quelle): Uri? = p.getString(ORDNER + quelle.wire, null)?.let(Uri::parse)

    fun setzeOrdner(quelle: Quelle, baum: Uri) {
        p.edit().putString(ORDNER + quelle.wire, baum.toString()).remove(MELDUNG + quelle.wire).apply()
    }

    override fun gesendet(quelle: Quelle): Gesendet? = Gesendet.aus(p.getString(GESENDET + quelle.wire, null))

    override fun merkeGesendet(quelle: Quelle, g: Gesendet) {
        p.edit().putString(GESENDET + quelle.wire, g.zeile()).apply()
    }

    override fun merkeUebertragen(quelle: Quelle, zeit: Long, dateien: Int) {
        p.edit().putLong(UEBERTRAGEN + quelle.wire, zeit).putInt(UEBERTRAGEN_DATEIEN + quelle.wire, dateien).apply()
    }

    /** When a file of [quelle] last reached the box whole, and how many in that run; null when never. */
    fun uebertragen(quelle: Quelle): Pair<Long, Int>? {
        val zeit = p.getLong(UEBERTRAGEN + quelle.wire, 0L).takeIf { it > 0 } ?: return null
        return zeit to p.getInt(UEBERTRAGEN_DATEIEN + quelle.wire, 0)
    }

    override fun melde(quelle: Quelle, meldung: Meldung) {
        p.edit().putString(MELDUNG + quelle.wire, "${meldung.zeit}|${meldung.art.name}|${meldung.satz.orEmpty()}").apply()
    }

    /** [quelle]'s last word, or null before the first run. */
    fun meldung(quelle: Quelle): Meldung? {
        val t = p.getString(MELDUNG + quelle.wire, null)?.split('|', limit = 3) ?: return null
        if (t.size != 3) return null
        val art = runCatching { Meldung.Art.valueOf(t[1]) }.getOrNull() ?: return null
        return Meldung(t[0].toLongOrNull() ?: return null, art, t[2].ifBlank { null })
    }

    override fun upload(quelle: Quelle, pfad: String): String? = p.getString(upload(quelle.wire, pfad), null)

    override fun merkeUpload(quelle: Quelle, pfad: String, zeile: String) {
        // commit, not apply: the id must outlive a process killed right after the upload began
        p.edit().putString(upload(quelle.wire, pfad), zeile).commit()
    }

    override fun vergissUpload(quelle: Quelle, pfad: String) {
        p.edit().remove(upload(quelle.wire, pfad)).apply()
    }

    private fun upload(quelle: String, pfad: String) = "$UPLOAD$quelle|$pfad"

    // --- contacts, SMS and calls (laib ADR 0029) ---------------------------------

    /** „Kontakte übertragen“: off until the person turns it on. */
    fun kontakteAn(): Boolean = p.getBoolean(KONTAKTE_AN, false)

    fun setzeKontakteAn(an: Boolean) {
        p.edit().putBoolean(KONTAKTE_AN, an).remove(KONTAKTE_MELDUNG).apply()
    }

    /** „SMS und Anrufliste übertragen“: off until the person turns it on. */
    fun smsAn(): Boolean = p.getBoolean(SMS_AN, false)

    fun setzeSmsAn(an: Boolean) {
        p.edit().putBoolean(SMS_AN, an).remove(MELDUNG + Quelle.SMS.wire).apply()
    }

    override fun marke(art: Art): Long = p.getLong(MARKE + art.wire, 0L)

    override fun setzeMarke(art: Art, wert: Long) {
        p.edit().putLong(MARKE + art.wire, wert).commit()
    }

    override fun ausstehend(name: String): Map<Art, Long>? = ausstehendAus(p.getString(AUSSTEHEND + name, null))

    override fun merkeAusstehend(name: String, marken: Map<Art, Long>?) {
        // commit, not apply: a file on its way must still be known after the process was killed
        if (marken == null) {
            p.edit().remove(AUSSTEHEND + name).commit()
        } else {
            p.edit().putString(AUSSTEHEND + name, ausstehendZeile(marken)).commit()
        }
    }

    override fun meldeKontakte(meldung: Meldung) {
        p.edit().putString(KONTAKTE_MELDUNG, "${meldung.zeit}|${meldung.art.name}|${meldung.satz.orEmpty()}").apply()
    }

    /** The contacts' last word, or null before the first run. */
    fun kontakteMeldung(): Meldung? {
        val t = p.getString(KONTAKTE_MELDUNG, null)?.split('|', limit = 3) ?: return null
        if (t.size != 3) return null
        val art = runCatching { Meldung.Art.valueOf(t[1]) }.getOrNull() ?: return null
        return Meldung(t[0].toLongOrNull() ?: return null, art, t[2].ifBlank { null })
    }

    override fun merkeKontakte(zeit: Long, ergebnis: SicherungClient.Kontakte) {
        val e = ergebnis
        p.edit().putString(KONTAKTE_ERGEBNIS, "$zeit|${e.kontakte}|${e.neu}|${e.geaendert}|${e.entfernt}").apply()
    }

    /** When Haus last mirrored the contacts, and what it came to; null when never. */
    fun kontakte(): Pair<Long, SicherungClient.Kontakte>? {
        val t = p.getString(KONTAKTE_ERGEBNIS, null)?.split('|')?.map { it.toLongOrNull() } ?: return null
        if (t.size != 5 || t.any { it == null }) return null
        return t[0]!! to SicherungClient.Kontakte(t[1]!!.toInt(), t[2]!!.toInt(), t[3]!!.toInt(), t[4]!!.toInt())
    }

    companion object {
        /** `sms=1730000000000;mms=…`: the marks a file under way moves. */
        fun ausstehendZeile(marken: Map<Art, Long>): String =
            marken.entries.sortedBy { it.key.ordinal }.joinToString(";") { "${it.key.wire}=${it.value}" }

        fun ausstehendAus(zeile: String?): Map<Art, Long>? {
            zeile ?: return null
            if (zeile.isEmpty()) return emptyMap()
            val out = LinkedHashMap<Art, Long>()
            for (teil in zeile.split(';')) {
                val (k, v) = teil.split('=', limit = 2).takeIf { it.size == 2 } ?: return null
                val art = Art.entries.firstOrNull { it.wire == k } ?: return null
                out[art] = v.toLongOrNull() ?: return null
            }
            return out
        }

        private const val KONTAKTE_AN = "kontakte_an"
        private const val SMS_AN = "sms_an"
        private const val MARKE = "marke."
        private const val AUSSTEHEND = "ausstehend."
        private const val KONTAKTE_MELDUNG = "kontakte_meldung"
        private const val KONTAKTE_ERGEBNIS = "kontakte_ergebnis"

        private const val PREFS = "haus_sicherung"
        private const val ORDNER = "ordner."
        private const val GESENDET = "gesendet."
        private const val UEBERTRAGEN = "uebertragen."
        private const val UEBERTRAGEN_DATEIEN = "uebertragen_dateien."
        private const val MELDUNG = "meldung."
        private const val UPLOAD = "upload."
    }
}
