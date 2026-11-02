package cloud.dopp.haus.standort

import android.content.Context
import android.content.SharedPreferences

/**
 * Standortverlauf's own prefs: the switch and the mode, activity
 * recognition's last word, the request in force, and how the sending goes.
 * Plain prefs: no secret here; the Reitti token lives on Haus, never on the
 * phone. The points themselves are in [Puffer].
 */
class StandortStore(ctx: Context) : Sendegedaechtnis {

    private val p: SharedPreferences = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun an(): Boolean = p.getBoolean(AN, false)
    fun setzeAn(an: Boolean) = p.edit().putBoolean(AN, an).remove(IN_KRAFT).apply()

    fun modus(): Modus = Modus.aus(p.getString(MODUS, null))
    fun setzeModus(m: Modus) = p.edit().putString(MODUS, m.wire).apply()

    /** Activity recognition's last word: true still, false moving, null not known. */
    fun ruht(): Boolean? = if (p.contains(RUHT)) p.getBoolean(RUHT, false) else null
    fun setzeRuht(ruht: Boolean?) {
        if (ruht == null) p.edit().remove(RUHT).apply() else p.edit().putBoolean(RUHT, ruht).apply()
    }

    /** The request asked of Android last, as `<modus>/<lage>`; null when none is in force. */
    fun inKraft(): String? = p.getString(IN_KRAFT, null)
    fun setzeInKraft(k: String?) = p.edit().putString(IN_KRAFT, k).apply()

    /** Points the full buffer had to drop, all told. */
    fun verloren(): Int = p.getInt(VERLOREN, 0)
    fun mehrVerloren(n: Int) {
        if (n > 0) p.edit().putInt(VERLOREN, verloren() + n).apply()
    }

    override val fehlschlaege: Int get() = p.getInt(FEHLSCHLAEGE, 0)
    override val letzterFehlschlag: Long get() = p.getLong(LETZTER_FEHLSCHLAG, 0L)

    /** The last failure's sentence, until a batch goes through. */
    fun meldung(): String? = p.getString(MELDUNG, null)

    override fun fehlgeschlagen(zeit: Long, satz: String?) {
        p.edit().putInt(FEHLSCHLAEGE, fehlschlaege + 1).putLong(LETZTER_FEHLSCHLAG, zeit).putString(MELDUNG, satz).apply()
    }

    override fun gesendet(zeit: Long, anzahl: Int, verworfen: Int) {
        p.edit().putInt(FEHLSCHLAEGE, 0).remove(MELDUNG).putLong(GESENDET_ZEIT, zeit).putInt(GESENDET_ANZAHL, anzahl)
            .putInt(VERWORFEN, p.getInt(VERWORFEN, 0) + verworfen).apply()
    }

    /** When Reitti last took a batch, and how many points; null when never. */
    fun zuletztGesendet(): Pair<Long, Int>? {
        val z = p.getLong(GESENDET_ZEIT, 0L).takeIf { it > 0 } ?: return null
        return z to p.getInt(GESENDET_ANZAHL, 0)
    }

    private companion object {
        const val PREFS = "standort"
        const val AN = "an"
        const val MODUS = "modus"
        const val RUHT = "ruht"
        const val IN_KRAFT = "in_kraft"
        const val VERLOREN = "verloren"
        const val VERWORFEN = "verworfen"
        const val FEHLSCHLAEGE = "fehlschlaege"
        const val LETZTER_FEHLSCHLAG = "letzter_fehlschlag"
        const val MELDUNG = "meldung"
        const val GESENDET_ZEIT = "gesendet_zeit"
        const val GESENDET_ANZAHL = "gesendet_anzahl"
    }
}
