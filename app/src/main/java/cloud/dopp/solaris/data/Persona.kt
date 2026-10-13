package cloud.dopp.solaris.data

import android.content.Context
import org.json.JSONObject

/**
 * The house persona (mdopp/laib ADR 0020, laib#130): who the house is to the
 * family — "Joshua" — as Haus serves it at `GET /napi/persona`. The app shows
 * its name where the wordmark is and in its notifications. A server without a
 * persona (Solaris today) answers 404, and the app stays as it is.
 */
data class Persona(val display: String, val greeting: String, val accent: String) {
    companion object {
        /** Haus's answer, or null when it names nobody. */
        fun parse(o: JSONObject): Persona? {
            val display = o.optString("display").trim()
            if (display.isEmpty()) return null
            return Persona(display, o.optString("greeting").trim(), o.optString("accent").trim())
        }
    }
}

/** The last persona Haus named; cleared on sign-out. Plain prefs, no secret. */
object PersonaStore {
    private const val PREFS = "house_persona"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context): Persona? {
        val display = p(ctx).getString("display", null) ?: return null
        return Persona(display, p(ctx).getString("greeting", "") ?: "", p(ctx).getString("accent", "") ?: "")
    }

    fun save(ctx: Context, persona: Persona) {
        p(ctx).edit()
            .putString("display", persona.display)
            .putString("greeting", persona.greeting)
            .putString("accent", persona.accent)
            .apply()
    }

    fun clear(ctx: Context) = p(ctx).edit().clear().apply()

    /** The name to show: the persona's, else the app's own. */
    fun name(ctx: Context, fallback: String): String = get(ctx)?.display ?: fallback
}
