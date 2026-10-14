package cloud.dopp.haus.data

import android.content.Context
import java.io.File

/** The last persona Haus named, and its icon; cleared on unpairing. Plain prefs and a file, no secret. */
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

    private fun iconFile(ctx: Context) = File(ctx.filesDir, "persona_icon.png")

    /** The icon's PNG, as last fetched. */
    fun icon(ctx: Context): ByteArray? = runCatching { iconFile(ctx).readBytes() }.getOrNull()

    fun saveIcon(ctx: Context, png: ByteArray?) {
        runCatching { if (png == null) iconFile(ctx).delete() else iconFile(ctx).writeBytes(png) }
    }

    fun clear(ctx: Context) {
        p(ctx).edit().clear().apply()
        saveIcon(ctx, null)
    }

    /** The name to show: the persona's, else the app's own. */
    fun name(ctx: Context, fallback: String): String = get(ctx)?.display ?: fallback
}
