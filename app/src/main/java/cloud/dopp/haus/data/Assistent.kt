package cloud.dopp.haus.data

import android.content.Context
import cloud.dopp.haus.R
import cloud.dopp.haus.data.Napi.AssistentJson
import org.json.JSONObject

/**
 * The household assistant's name, set once on the box (Laib's box.yml
 * `assistant`) and served by Haus at `GET /napi/assistent` as `{"name": …}`.
 * The app takes nothing else about it: its colours are fixed in the app's
 * resources, its icon is the app's own. The last name read is kept in plain
 * prefs (no secret) and forgotten on unpairing; until then, „Homes“.
 */
object Assistent {
    private const val PREFS = "assistent"
    private const val KEY_NAME = "assistent_name"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Haus's answer, or null when it names nobody. */
    fun parse(o: JSONObject): String? = o.optString(AssistentJson.NAME).trim().ifEmpty { null }

    /** The name to show: the one Haus named last, else „Homes“. */
    fun name(ctx: Context): String =
        p(ctx).getString(KEY_NAME, null) ?: ctx.getString(R.string.assistent_name)

    /** Keep [name]; true when it differs from the one kept before. */
    fun save(ctx: Context, name: String): Boolean {
        if (p(ctx).getString(KEY_NAME, null) == name) return false
        p(ctx).edit().putString(KEY_NAME, name).apply()
        return true
    }

    fun clear(ctx: Context) {
        p(ctx).edit().clear().apply()
    }
}
