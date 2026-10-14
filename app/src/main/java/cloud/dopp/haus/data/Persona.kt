package cloud.dopp.haus.data

import cloud.dopp.haus.data.Napi.PersonaJson
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
            val display = o.optString(PersonaJson.DISPLAY).trim()
            if (display.isEmpty()) return null
            return Persona(
                display = display,
                greeting = o.optString(PersonaJson.GREETING).trim(),
                accent = o.optString(PersonaJson.ACCENT).trim(),
                ground = o.optString(PersonaJson.GROUND).trim(),
                hasIcon = o.optString(PersonaJson.ICON).trim().isNotEmpty(),
            )
        }
    }
}

/**
 * The persona's icon: SVG text Haus serves at `/napi/persona/icon.svg`. Kept
 * small ([MAX_BYTES]) — it is fetched, stored and parsed whole, and parsing
 * runs off the main thread (`HomeActivity`), so a large or broken SVG costs a
 * missing icon, never a frozen screen.
 */
object PersonaIcon {
    /** The largest icon the app takes; Haus's personas ship a few KB. */
    const val MAX_BYTES = 64 * 1024

    /** [svg] when it is SVG and within [MAX_BYTES], else null. */
    fun accept(svg: String?): String? =
        svg?.takeIf { it.length <= MAX_BYTES && it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES && it.contains("<svg") }
}
