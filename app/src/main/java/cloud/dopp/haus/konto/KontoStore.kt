package cloud.dopp.haus.konto

import android.content.Context

/**
 * What the Haus account's two syncs remember (#206): the ETag of the last
 * state they wrote (so an unchanged one costs Haus's 304), and for the
 * screen when the last good run was, how many entries it left, and its last
 * word when one went wrong. No secret: the token stays in TokenStore.
 */
class KontoStore(ctx: Context) {

    private val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The last good run of a part: when, and how many entries the phone holds. */
    data class Stand(val zeit: Long, val anzahl: Int)

    fun etag(teil: HausKonto.Teil): String? = p.getString(key(teil, "etag"), null)

    /** A good run: [etag] written, [anzahl] entries on the phone; its last word cleared. */
    fun geschafft(teil: HausKonto.Teil, etag: String?, anzahl: Int?, zeit: Long = System.currentTimeMillis()) {
        val e = p.edit().putLong(key(teil, "zeit"), zeit).remove(key(teil, "meldung"))
        if (etag != null) e.putString(key(teil, "etag"), etag)
        if (anzahl != null) e.putInt(key(teil, "anzahl"), anzahl)
        e.apply()
    }

    /** A run that went wrong: its sentence, kept until the next good one. */
    fun melde(teil: HausKonto.Teil, satz: String) {
        p.edit().putString(key(teil, "meldung"), satz).apply()
    }

    fun stand(teil: HausKonto.Teil): Stand? {
        val zeit = p.getLong(key(teil, "zeit"), 0L)
        return if (zeit == 0L) null else Stand(zeit, p.getInt(key(teil, "anzahl"), 0))
    }

    fun meldung(teil: HausKonto.Teil): String? = p.getString(key(teil, "meldung"), null)

    /** The part is off: next time it starts from nothing. */
    fun vergiss(teil: HausKonto.Teil) {
        p.edit().remove(key(teil, "etag")).remove(key(teil, "zeit")).remove(key(teil, "anzahl"))
            .remove(key(teil, "meldung")).apply()
    }

    /** The ETag only: the phone's entries were changed from outside, the next run writes them all again. */
    fun vergissEtag(teil: HausKonto.Teil) {
        p.edit().remove(key(teil, "etag")).apply()
    }

    private fun key(teil: HausKonto.Teil, was: String) = "${teil.name.lowercase()}_$was"

    private companion object {
        const val PREFS = "haus_konto"
    }
}
