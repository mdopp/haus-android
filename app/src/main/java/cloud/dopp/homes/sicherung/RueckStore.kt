package cloud.dopp.homes.sicherung

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import cloud.dopp.homes.data.Napi.Quelle

/**
 * Sicherung zurückholen's own prefs (homes-android#197): the folder granted
 * for each source's restore, and which files there the restore wrote itself
 * ([Rueckgedaechtnis]): those alone it goes on writing without asking.
 * Nothing here is a secret.
 */
class RueckStore(ctx: Context) : Rueckgedaechtnis {

    private val p: SharedPreferences = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The folder granted for [quelle]'s restore (a tree URI), or null. */
    fun ziel(quelle: Quelle): Uri? = p.getString(ZIEL + quelle.wire, null)?.let(Uri::parse)

    fun setzeZiel(quelle: Quelle, baum: Uri) {
        p.edit().putString(ZIEL + quelle.wire, baum.toString()).apply()
    }

    override fun unser(quelle: Quelle, pfad: String): Pair<Rueckgedaechtnis.Stand, String>? {
        val t = p.getString(key(quelle, pfad), null)?.split('|', limit = 2) ?: return null
        val stand = runCatching { Rueckgedaechtnis.Stand.valueOf(t[0]) }.getOrNull() ?: return null
        return stand to t.getOrElse(1) { "" }
    }

    override fun merke(quelle: Quelle, pfad: String, stand: Rueckgedaechtnis.Stand, etag: String) {
        // commit, not apply: a file the restore began must stay known as ours
        p.edit().putString(key(quelle, pfad), "${stand.name}|$etag").commit()
    }

    private fun key(quelle: Quelle, pfad: String) = "$UNSER${quelle.wire}|$pfad"

    private companion object {
        const val PREFS = "zurueckholen"
        const val ZIEL = "ziel."
        const val UNSER = "unser."
    }
}
