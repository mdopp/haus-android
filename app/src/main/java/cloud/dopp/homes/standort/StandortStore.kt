package cloud.dopp.homes.standort

import android.content.Context
import android.content.SharedPreferences

/**
 * Standortverlauf's own prefs: the switch and the mode, [Ruhe]'s state,
 * the request in force, and how the sending goes.
 * Plain prefs: no secret here; the Reitti token lives on Homes, never on the
 * phone. The points themselves are in [Puffer].
 */
class StandortStore(ctx: Context) : Sendegedaechtnis {

    private val p: SharedPreferences = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun an(): Boolean = p.getBoolean(AN, false)
    fun setzeAn(an: Boolean) = p.edit().putBoolean(AN, an).remove(IN_KRAFT).apply()

    fun modus(): Modus = Modus.aus(p.getString(MODUS, null))
    fun setzeModus(m: Modus) = p.edit().putString(MODUS, m.wire).apply()

    /** [Ruhe]'s decision so far. */
    fun ruhe(): Ruhe.Stand {
        val ruht = p.getBoolean(RUHT, false)
        if (!p.contains(ANKER_ZEIT)) return Ruhe.Stand(null, false)
        val a = Anker(
            p.getLong(ANKER_ZEIT, 0L),
            java.lang.Double.longBitsToDouble(p.getLong(ANKER_BREITE, 0L)),
            java.lang.Double.longBitsToDouble(p.getLong(ANKER_LAENGE, 0L)),
            p.getFloat(ANKER_GENAU, 0f),
        )
        return Ruhe.Stand(a, ruht)
    }

    fun setzeRuhe(s: Ruhe.Stand) {
        val e = p.edit().putBoolean(RUHT, s.ruht)
        val a = s.anker
        if (a == null) {
            e.remove(ANKER_ZEIT).remove(ANKER_BREITE).remove(ANKER_LAENGE).remove(ANKER_GENAU)
        } else {
            e.putLong(ANKER_ZEIT, a.zeit).putLong(ANKER_BREITE, java.lang.Double.doubleToRawLongBits(a.breite))
                .putLong(ANKER_LAENGE, java.lang.Double.doubleToRawLongBits(a.laenge)).putFloat(ANKER_GENAU, a.genauigkeit)
        }
        e.commit() // the receiver's thread ends right after; the next delivery reads it
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
        const val ANKER_ZEIT = "anker_zeit"
        const val ANKER_BREITE = "anker_breite"
        const val ANKER_LAENGE = "anker_laenge"
        const val ANKER_GENAU = "anker_genau"
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
