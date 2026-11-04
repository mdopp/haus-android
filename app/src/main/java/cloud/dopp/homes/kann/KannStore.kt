package cloud.dopp.homes.kann

import android.content.Context
import androidx.core.content.edit
import cloud.dopp.homes.data.HomesHttp
import cloud.dopp.homes.data.Napi.KannJson
import cloud.dopp.homes.data.Napi.ModulJson
import org.json.JSONObject

/**
 * The last answer of `/napi/kann` (homes ADR 0001 §3), kept so the app works
 * from it offline and asks again with its ETag. No answer kept: [kann] is
 * null and the app offers everything, as before — also after a 404 from an
 * older Homes, which forgets what was kept.
 */
object KannStore {
    private const val PREFS = "homes_kann"
    private const val KEY_BODY = "body"
    private const val KEY_ETAG = "etag"
    private const val KEY_GEFRAGT = "gefragt_ms"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The body parsed last, so a screen that draws often parses once. */
    @Volatile
    private var parsed: Pair<String, Kann?>? = null

    /** What the box can do, as it said last; null when unknown. */
    fun kann(ctx: Context): Kann? {
        val body = p(ctx).getString(KEY_BODY, null) ?: return null
        parsed?.let { (b, k) -> if (b == body) return k }
        return Kann.lesen(body).also { parsed = body to it }
    }

    fun etag(ctx: Context): String? = p(ctx).getString(KEY_ETAG, null)

    /** Keep a new answer; true when it says something else than the kept one. */
    @Synchronized
    fun neu(ctx: Context, body: String, etag: String?): Boolean {
        if (Kann.lesen(body) == null) return false // not an answer: keep the old one
        val old = p(ctx).getString(KEY_BODY, null)
        p(ctx).edit { putString(KEY_BODY, body).putString(KEY_ETAG, etag) }
        return old != body
    }

    /** An older Homes without `/napi/kann`: forget; true when something was kept. */
    @Synchronized
    fun fehlt(ctx: Context): Boolean {
        val had = p(ctx).contains(KEY_BODY)
        p(ctx).edit { remove(KEY_BODY).remove(KEY_ETAG) }
        return had
    }

    /**
     * A wish's answer: the module's new [eintrag] in place of its old one in
     * the kept answer, so the trace shows its state at once. The ETag stays:
     * the next read brings Homes' whole answer.
     */
    @Synchronized
    fun ersetzen(ctx: Context, eintrag: String) {
        val neu = HomesHttp.json(eintrag) ?: return
        val name = Kann.modul(neu)?.name ?: return
        val root = HomesHttp.json(p(ctx).getString(KEY_BODY, null)) ?: return
        val module = root.optJSONArray(KannJson.MODULE) ?: return
        for (i in 0 until module.length()) {
            val o: JSONObject? = module.optJSONObject(i)
            if (o != null && HomesHttp.text(o, ModulJson.NAME) == name) module.put(i, neu)
        }
        p(ctx).edit { putString(KEY_BODY, root.toString()) }
    }

    /**
     * Is a read due at [now] (ms)? At most once a [MINUTE] unless [immer];
     * a due read is marked asked, so two screens at once ask once.
     */
    @Synchronized
    fun faellig(ctx: Context, now: Long, immer: Boolean): Boolean {
        val last = p(ctx).getLong(KEY_GEFRAGT, 0L)
        if (!immer && now >= last && now - last < MINUTE) return false
        p(ctx).edit { putLong(KEY_GEFRAGT, now) }
        return true
    }

    /** Unpaired: nothing kept, the next Homes is asked afresh. */
    fun clear(ctx: Context) {
        p(ctx).edit { clear() }
    }

    const val MINUTE = 60_000L
}
