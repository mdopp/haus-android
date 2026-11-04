package cloud.dopp.homes.widget

import androidx.annotation.DrawableRes
import cloud.dopp.homes.R
import cloud.dopp.homes.data.Card
import cloud.dopp.homes.data.SENSITIVE_COVER_CLASSES

/**
 * How the device widget shows its device: a picture first (owner,
 * 2026-11-03: „Das Garagentor Widget ist viel schlechter als früher. Kaum noch
 * benutzbar. Viel Text statt Bildern.“). The state is told by the picture's
 * silhouette — a garage door down or up, a shutter down or up, a bulb lit or
 * dark, the padlock's shackle — and again by its colour, so neither has to
 * stand alone (#91). Words only where a picture cannot say it: a reading, a
 * position in between, a device that does not answer.
 *
 * The colours are Homes' (internal/pages/family.go): amber for what is on,
 * green for shut and locked, orange for open where open is worth noticing,
 * blue while moving, red for a jammed lock, grey for off, a dimmer grey for
 * nothing known. Pure → JVM-testable.
 */
object DeviceLook {
    const val ON = 0xFFFFC107.toInt()       // Homes --state: what is on
    const val SECURE = 0xFF10B981.toInt()   // Homes --ok: shut, locked
    const val OPEN = 0xFFF59E0B.toInt()     // Homes --warn: a garage or a lock open
    private const val LATCH = 0xFFFF7043.toInt()    // a lock's latch pulled
    const val MOVING = 0xFF7E9CFF.toInt()   // opening, closing, locking
    private const val JAMMED = 0xFFEF5350.toInt()   // Homes --danger
    const val OFF = 0xFF9AA0AE.toInt()      // Homes --muted: off, shut shutter
    const val UNKNOWN = 0xFF6B7280.toInt()  // Homes --faint: nothing known, or not reachable

    /** A garage, a gate, a door: a cover whose open is worth noticing. */
    private fun isGarage(domain: String?, deviceClass: String?): Boolean = domain == "cover" && deviceClass in SENSITIVE_COVER_CLASSES

    private fun norm(state: String?) = state?.trim()?.lowercase()

    /** The picture of [domain] (with [deviceClass]) in [state]. */
    @DrawableRes
    fun icon(domain: String?, deviceClass: String?, state: String?): Int {
        val s = norm(state)
        return when {
            domain == "lock" -> DeviceIcons.forLockState(s)
            isGarage(domain, deviceClass) -> if (s in OPENISH) R.drawable.ic_garage_open else R.drawable.ic_garage_closed
            domain == "cover" -> if (s in OPENISH) R.drawable.ic_blind_open else R.drawable.ic_blind_closed
            domain == "light" -> if (s == "on") R.drawable.ic_light_on else R.drawable.ic_light_off
            else -> DeviceIcons.forDomain(domain)
        }
    }

    private val OPENISH = setOf("open", "opening")

    /** The picture's colour: [card] null is nothing known yet. */
    fun tint(domain: String?, deviceClass: String?, card: Card?): Int {
        if (card == null) return UNKNOWN
        val s = norm(card.state)
        if (s == null || s == "unavailable" || s == "unknown") return UNKNOWN
        return when {
            domain == "lock" -> when (s) {
                "locked" -> SECURE
                "unlocked" -> OPEN
                "open" -> LATCH
                "locking", "unlocking", "opening" -> MOVING
                "jammed" -> JAMMED
                else -> OFF
            }
            domain == "cover" -> when (s) {
                "opening", "closing" -> MOVING
                "open" -> if (isGarage(domain, deviceClass)) OPEN else ON
                "closed" -> if (isGarage(domain, deviceClass)) SECURE else OFF
                else -> OFF
            }
            card.isOn -> ON
            else -> OFF
        }
    }

    /**
     * Does the tile need its state in words even where room is short? Only
     * where the picture cannot say it: a reading (a temperature, a heating),
     * a shutter in between, a device that does not answer or is not known.
     */
    fun needsWords(card: Card?): Boolean {
        if (card == null) return true
        val s = norm(card.state)
        if (s == null || s == "unavailable" || s == "unknown" || s == "jammed") return true
        return when (card.domain) {
            "light", "switch", "lock", "fan", "input_boolean" -> false
            "cover" -> card.position?.let { it in 1..99 } == true
            else -> true
        }
    }

    /**
     * The name as one short line: a trailing or leading word that only
     * repeats the start of another (the room HA appends, „Garagentor Garage“)
     * goes — „Garagentor“. A name that is nothing else stays as it is.
     */
    fun shortName(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return name.trim()
        fun repeats(w: String, rest: List<String>) = w.length >= 3 && rest.any { it.startsWith(w, ignoreCase = true) }
        val last = words.last()
        if (repeats(last, words.dropLast(1))) return words.dropLast(1).joinToString(" ")
        val first = words.first()
        if (repeats(first, words.drop(1))) return words.drop(1).joinToString(" ")
        return words.joinToString(" ")
    }

    /**
     * The state in the fewest words, for one narrow cell: a heating's
     * temperature alone, a shutter's position alone (its picture says open);
     * else [full], the start screen's words.
     */
    fun compactWords(card: Card, full: String): String = when {
        card.domain == "climate" && card.temperature != null -> String.format(java.util.Locale.GERMANY, "%.1f °C", card.temperature)
        card.domain == "cover" && card.position in 1..99 -> "${card.position} %"
        else -> full
    }

    /** A level to show as a bar: a lit lamp's brightness, a shutter's position in between; else null. */
    fun level(card: Card?): Int? = when {
        card == null -> null
        card.domain == "light" && card.isOn -> card.brightnessPct
        card.domain == "cover" -> card.position?.takeIf { it in 1..99 }
        else -> null
    }
}
