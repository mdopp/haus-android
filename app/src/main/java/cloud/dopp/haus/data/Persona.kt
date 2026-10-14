package cloud.dopp.haus.data

import android.content.Context
import org.json.JSONObject

/**
 * The house persona (laib ADR 0020, laib#130): who the house is to the family
 * — "Joshua" — as Haus serves it at `GET /napi/persona`: its name, its two
 * colours, and an icon at `/napi/persona/icon.svg` when it has one. The app
 * shows the name, the colours and the icon on its home screen.
 */
data class Persona(
    val display: String,
    val greeting: String,
    val accent: String,
    val ground: String = "",
    val hasIcon: Boolean = false,
) {
    companion object {
        /** Haus's answer, or null when it names nobody. */
        fun parse(o: JSONObject): Persona? {
            val display = o.optString("display").trim()
            if (display.isEmpty()) return null
            return Persona(
                display = display,
                greeting = o.optString("greeting").trim(),
                accent = o.optString("accent").trim(),
                ground = o.optString("ground").trim(),
                hasIcon = o.optString("icon").trim().isNotEmpty(),
            )
        }
    }
}

/** The last persona Haus named, and its icon; cleared on unpairing. Plain prefs, no secret. */
object PersonaStore {
    private const val PREFS = "house_persona"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context): Persona? {
        val p = p(ctx)
        val display = p.getString("display", null) ?: return null
        return Persona(
            display = display,
            greeting = p.getString("greeting", "") ?: "",
            accent = p.getString("accent", "") ?: "",
            ground = p.getString("ground", "") ?: "",
            hasIcon = p.getBoolean("has_icon", false),
        )
    }

    fun save(ctx: Context, persona: Persona) {
        p(ctx).edit()
            .putString("display", persona.display)
            .putString("greeting", persona.greeting)
            .putString("accent", persona.accent)
            .putString("ground", persona.ground)
            .putBoolean("has_icon", persona.hasIcon)
            .apply()
    }

    /** The icon's SVG text, as last fetched. */
    fun icon(ctx: Context): String? = p(ctx).getString("icon_svg", null)

    fun saveIcon(ctx: Context, svg: String?) {
        p(ctx).edit().apply { if (svg == null) remove("icon_svg") else putString("icon_svg", svg) }.apply()
    }

    fun clear(ctx: Context) = p(ctx).edit().clear().apply()

    /** The name to show: the persona's, else the app's own. */
    fun name(ctx: Context, fallback: String): String = get(ctx)?.display ?: fallback
}
