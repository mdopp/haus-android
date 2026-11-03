package cloud.dopp.homes.ui

import android.content.Context
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.Card
import java.util.Locale

/**
 * The start screen's devices (owner, 2026-11-02: devices only in the app, no
 * device widgets): the person's favorites from Homes (`/napi/portal/favorites`,
 * the things they pinned with ☆ on Homes), first on the screen, in their
 * order, then „Alle Geräte“, which opens Homes' devices page.
 *
 * A tap acts the way the device widget's did: a light or a switch toggles, a
 * cover opens or closes (a garage asks first, the server's 403), a lock opens
 * its chooser (#92). Anything else opens the device's own page on Homes.
 *
 * The list last read is kept, so the screen draws at once and a failed read
 * keeps it (stale-then-fresh). Pure but for [load]/[save]: JVM-testable.
 */
object StartDevices {

    private const val PREFS = "homes_start"
    private const val KEY_FAVORITES = "favorites_json"

    /** The domains a tap acts on; everything else opens its page. */
    val ACTING = setOf("light", "switch", "cover", "lock")

    /** Does a tap on [card] switch it (or, for a lock, open its chooser)? */
    fun acts(card: Card): Boolean = card.domain in ACTING

    /** The favorites read last, or null when there is none yet. */
    fun load(ctx: Context): List<Card>? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FAVORITES, null)
            ?.let { ApiClient.parseFavorites(it) }

    /** Keep Homes' answer [body] as the list read last. */
    fun save(ctx: Context, body: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FAVORITES, body).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** The state as the row says it, in plain German. */
    fun stateText(card: Card): String {
        val state = card.state?.trim()?.lowercase(Locale.ROOT)
        if (card.domain == "lock") return lockLabel(state)
        if (state == null || state == "unknown") return "—"
        if (state == "unavailable") return "nicht erreichbar"
        return when (card.domain) {
            "light" -> if (state == "on") listOfNotNull("an", card.brightnessPct?.let { "$it %" }).joinToString(" · ") else "aus"
            "switch" -> if (state == "on") "an" else "aus"
            "cover" -> when (state) {
                "open" -> listOfNotNull("offen", card.position?.takeIf { it in 1..99 }?.let { "$it %" }).joinToString(" · ")
                "closed" -> "zu"
                "opening" -> "öffnet …"
                "closing" -> "schließt …"
                else -> state
            }
            "climate" -> card.temperature?.let { String.format(Locale.GERMANY, "%.1f °C", it) } ?: words(state)
            "media_player" -> when (state) {
                "playing" -> "spielt"
                "paused" -> "pausiert"
                "off" -> "aus"
                "idle", "standby", "on" -> "bereit"
                else -> state
            }
            "binary_sensor" -> if (state == "on") "an" else "aus"
            else -> listOfNotNull(card.state?.trim()?.replace('.', ','), card.unit).joinToString(" ")
        }
    }

    /**
     * German state text for a lock (#84). It talks about the **bolt**, never the
     * door: there is no door sensor, so a lock reporting `locked` says
     * *abgeschlossen*, not *zu*. `unknown` (no MQTT retain message yet after a
     * broker or phone restart) is its own word — it must never read as
     * *abgeschlossen*.
     */
    fun lockLabel(state: String?): String = when (state?.trim()?.lowercase()) {
        "locked" -> "abgeschlossen"
        "unlocked" -> "aufgeschlossen"
        "open" -> "entriegelt"
        "locking" -> "schließt ab …"
        "unlocking" -> "schließt auf …"
        "opening" -> "entriegelt …"
        "jammed" -> "klemmt"
        "unavailable" -> "nicht erreichbar"
        null, "", "unknown", "none" -> "unbekannt"
        else -> state
    }

    private fun words(state: String): String = when (state) {
        "heat" -> "heizt"
        "cool" -> "kühlt"
        "off" -> "aus"
        "auto" -> "automatisch"
        else -> state
    }
}
