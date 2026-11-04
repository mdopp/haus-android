package cloud.dopp.homes.widget

import androidx.annotation.DrawableRes
import cloud.dopp.homes.R

/**
 * Shared HA-domain → type-icon mapping used by the start screen's device rows
 * and the app-icon shortcuts, so a light/switch/cover/climate reads the same
 * everywhere. Pure (no Android context) → unit-testable on the JVM.
 */
object DeviceIcons {
    @DrawableRes
    fun forDomain(domain: String?): Int = when (domain) {
        "light" -> R.drawable.ic_light
        "switch" -> R.drawable.ic_switch
        "cover" -> R.drawable.ic_cover
        "climate" -> R.drawable.ic_climate
        // A door with a keyhole (#84).
        "lock" -> R.drawable.ic_door_lock
        else -> R.drawable.ic_device
    }

    /**
     * Domain → **app-icon shortcut** badge (#99). Deliberately *not* [forDomain]:
     * those glyphs are white on transparent because they sit on the app's dark
     * screen and are tinted there. The launcher draws a shortcut icon on a light
     * background under a round adaptive mask, so a white glyph with no background
     * of its own collapses into a plain white disc — the reported symptom.
     *
     * The `ic_shortcut_*` drawables are therefore self-contained: the same glyph,
     * but on an opaque brand-blue field and inset into the mask's safe zone.
     * [forDomain] stays exactly as it is — it is right for the dark screen.
     */
    @DrawableRes
    fun forShortcut(domain: String?): Int = when (domain) {
        "light" -> R.drawable.ic_shortcut_light
        "switch" -> R.drawable.ic_shortcut_switch
        "cover" -> R.drawable.ic_shortcut_cover
        "climate" -> R.drawable.ic_shortcut_climate
        "lock" -> R.drawable.ic_shortcut_lock
        else -> R.drawable.ic_shortcut_device
    }

    /**
     * Type icon for a **live** card (#91). A lock is drawn by its *state*,
     * because a row is read in passing and `aufgeschlossen` vs `entriegelt` is
     * exactly the distinction that gets lost when only the words differ. A
     * cover by its [deviceClass] and state: a garage or a gate as a garage, any
     * other (shutter, blind, curtain, none named) as a shutter up or down —
     * „Rollo“ showed the garage (owner, 2026-11-03). Everything else is its
     * domain ([forDomain]).
     */
    @DrawableRes
    fun forState(domain: String?, state: String?, deviceClass: String? = null): Int = when (domain) {
        "lock" -> forLockState(state)
        "cover" -> forCover(deviceClass, state)
        else -> forDomain(domain)
    }

    /** A cover's picture: garage/gate as a garage, every other class as a shutter; open or opening is up. */
    @DrawableRes
    private fun forCover(deviceClass: String?, state: String?): Int {
        val offen = state?.trim()?.lowercase() in setOf("open", "opening")
        return if (deviceClass?.trim()?.lowercase() in GARAGE_CLASSES) {
            if (offen) R.drawable.ic_garage_open else R.drawable.ic_garage_closed
        } else {
            if (offen) R.drawable.ic_blind_open else R.drawable.ic_blind_closed
        }
    }

    private val GARAGE_CLASSES = setOf("garage", "gate")

    /**
     * Lock state → glyph (#91). The four glyphs differ by **silhouette, not
     * colour**: the row has to stay readable with a red-green weakness. Transitions
     * borrow the picture of the state they are heading for; anything unmapped —
     * including `unavailable` and `null` — lands on the shackle-less question
     * mark, which claims nothing. Pure →
     * JVM-testable.
     */
    @DrawableRes
    fun forLockState(state: String?): Int = when (state?.trim()?.lowercase()) {
        "locked", "locking" -> R.drawable.ic_lock_locked
        "unlocked", "unlocking" -> R.drawable.ic_lock_unlocked
        "open", "opening" -> R.drawable.ic_lock_open
        "jammed" -> R.drawable.ic_lock_jammed
        else -> R.drawable.ic_lock_unknown
    }
}
