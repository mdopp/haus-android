package cloud.dopp.haus.sicherung

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import cloud.dopp.haus.data.Napi.Quelle

/**
 * Nachrichten sichern's own prefs (#194): the granted folders, the backup sent
 * last per source, each source's last word and when a file last arrived, and
 * the ids of unfinished uploads. Plain prefs: nothing here is a secret, and
 * the app never stores a messenger's key.
 */
class SicherungStore(ctx: Context) : Gedaechtnis {

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

    private companion object {
        const val PREFS = "haus_sicherung"
        const val ORDNER = "ordner."
        const val GESENDET = "gesendet."
        const val UEBERTRAGEN = "uebertragen."
        const val UEBERTRAGEN_DATEIEN = "uebertragen_dateien."
        const val MELDUNG = "meldung."
        const val UPLOAD = "upload."
    }
}
