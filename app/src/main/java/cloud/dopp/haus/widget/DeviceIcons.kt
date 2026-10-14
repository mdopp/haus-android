package cloud.dopp.haus.widget

import androidx.annotation.DrawableRes
import cloud.dopp.haus.R

/**
 * Shared HA-domain → type-icon mapping used by both the widget renderer and the
 * device-picker row, so a light/switch/cover/climate reads the same everywhere.
 * Pure (no Android context) → unit-testable on the JVM.
 */
object DeviceIcons {
    @DrawableRes
    fun forDomain(domain: String?): Int = when (domain) {
        "light" -> R.drawable.ic_light
        "switch" -> R.drawable.ic_switch
        "cover" -> R.drawable.ic_cover
        "climate" -> R.drawable.ic_climate
        // A door with a keyhole (#84) — the padlock ic_lock stays the confirm badge.
        "lock" -> R.drawable.ic_door_lock
        else -> R.drawable.ic_device
    }

    /**
     * Domain → **app-icon shortcut** badge (#99). Deliberately *not* [forDomain]:
     * those glyphs are white on transparent because they sit on the widget's dark
     * field and are tinted there. The launcher draws a shortcut icon on a light
     * background under a round adaptive mask, so a white glyph with no background
     * of its own collapses into a plain white disc — the reported symptom.
     *
     * The `ic_shortcut_*` drawables are therefore self-contained: the same glyph,
     * but on an opaque brand-blue field and inset into the mask's safe zone.
     * [forDomain] stays exactly as it is — it is right for the widgets.
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
     * Type icon for a **live** card (#91). Everything but a lock is a domain, so
     * it falls through to [forDomain]; a lock is drawn by its *state*, because on
     * a home screen a tile is read in passing and `aufgeschlossen` vs `entriegelt`
     * is exactly the distinction that gets lost when only the words differ.
     */
    @DrawableRes
    fun forState(domain: String?, state: String?): Int =
        if (domain == "lock") forLockState(state) else forDomain(domain)

    /**
     * Lock state → glyph (#91). The four glyphs differ by **silhouette, not
     * colour** — the accent ([WidgetRender.lockAccent]) already carries meaning
     * and the tile has to stay readable with a red-green weakness. Transitions
     * borrow the picture of the state they are heading for; anything unmapped —
     * including `unavailable` and `null` — lands on the shackle-less question
     * mark, which, like the neutral grey accent, claims nothing. Pure →
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
