package cloud.dopp.homes.konto

import android.content.Context
import androidx.core.content.edit

/**
 * What the Homes account's two syncs remember (#206): the ETag of the last
 * state they wrote (so an unchanged one costs Homes' 304), and for the
 * screen when the last good run was, how many entries it left, and its last
 * word when one went wrong. No secret: the token stays in TokenStore.
 */
class KontoStore(ctx: Context) {

    private val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The last good run of a part: when, and how many entries the phone holds. */
    data class Stand(val zeit: Long, val anzahl: Int)

    fun etag(teil: HomesKonto.Teil): String? = p.getString(key(teil, "etag"), null)

    /** A good run: [etag] written, [anzahl] entries on the phone; its last word cleared, unless it is a day's ([melde]). */
    fun geschafft(teil: HomesKonto.Teil, etag: String?, anzahl: Int?, zeit: Long = System.currentTimeMillis()) {
        val e = p.edit().putLong(key(teil, "zeit"), zeit)
        if (zeit - p.getLong(key(teil, BLEIBT), 0L) > TAG) e.remove(key(teil, "meldung"))
        if (etag != null) e.putString(key(teil, "etag"), etag)
        if (anzahl != null) e.putInt(key(teil, "anzahl"), anzahl)
        e.apply()
    }

    /**
     * A run that went wrong: its sentence, kept until the next good one; [bleibt] (a change on the phone Homes'
     * version won over) for a day, so the next sync Android starts at once does not hide it.
     */
    fun melde(teil: HomesKonto.Teil, satz: String, bleibt: Boolean = false) {
        val e = p.edit().putString(key(teil, "meldung"), satz)
        if (bleibt) e.putLong(key(teil, BLEIBT), System.currentTimeMillis())
        e.apply()
    }

    fun stand(teil: HomesKonto.Teil): Stand? {
        val zeit = p.getLong(key(teil, "zeit"), 0L)
        return if (zeit == 0L) null else Stand(zeit, p.getInt(key(teil, "anzahl"), 0))
    }

    fun meldung(teil: HomesKonto.Teil): String? = p.getString(key(teil, "meldung"), null)

    /** The part is off: next time it starts from nothing. */
    fun vergiss(teil: HomesKonto.Teil) {
        p.edit().remove(key(teil, "etag")).remove(key(teil, "zeit")).remove(key(teil, "anzahl"))
            .remove(key(teil, "meldung")).remove(key(teil, ANGEHALTEN)).remove(key(teil, BLEIBT)).apply()
    }

    /** The part syncs no more because the box lacks it (homes ADR 0001 §3): it comes back on with the box. */
    fun angehalten(teil: HomesKonto.Teil): Boolean = p.getBoolean(key(teil, ANGEHALTEN), false)

    fun setzeAngehalten(teil: HomesKonto.Teil, an: Boolean) {
        p.edit { putBoolean(key(teil, ANGEHALTEN), an) }
    }

    /** The ETag only: the phone's entries were changed from outside, the next run writes them all again. */
    fun vergissEtag(teil: HomesKonto.Teil) {
        p.edit().remove(key(teil, "etag")).apply()
    }

    private fun key(teil: HomesKonto.Teil, was: String) = "${teil.name.lowercase()}_$was"

    private companion object {
        const val PREFS = "homes_konto"
        const val ANGEHALTEN = "angehalten"
        const val BLEIBT = "bleibt"
        const val TAG = 24L * 60 * 60 * 1000
    }
}
