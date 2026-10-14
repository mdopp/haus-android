package cloud.dopp.haus.widget

import android.app.Activity

/**
 * How a widget's configuration screen was entered (#96).
 *
 * Declaring `android:widgetFeatures="reconfigurable"` is what puts *Einrichten*
 * into the launcher's long-press menu — the one entry an app may contribute
 * there. It also means every config activity is now re-entered on a widget that
 * already exists, and the two paths are **not** the same screen:
 *
 * On [FIRST_PLACEMENT] the host hands over a **fresh** `appWidgetId` and waits for
 * the verdict. Backing out must answer `RESULT_CANCELED` — that is how the host
 * learns to drop the half-placed widget again.
 *
 * On [RECONFIGURE] the same screen opens on an **already bound** id: the widget
 * sits on the homescreen and works, the user only asked to change what it points
 * at. `RESULT_CANCELED` there does not read as "nothing happened" but as "this
 * widget is not wanted", which hosts are free to act on — a cancelled edit could
 * take a working widget down with it. So the back-out answer flips to
 * `RESULT_OK` carrying the **unchanged** id: a no-op applied to a binding that
 * never moved.
 */
enum class ConfigEntry {
    /** A new widget, dropped on the homescreen, not yet bound to anything. */
    FIRST_PLACEMENT,

    /** An existing, bound widget re-opened through the long-press menu. */
    RECONFIGURE,
    ;

    /** The result to arm **before** anything is shown — what backing out means. */
    val backOutResult: Int
        get() = if (this == FIRST_PLACEMENT) Activity.RESULT_CANCELED else Activity.RESULT_OK

    /** True while an existing binding is on screen and must survive a back-out. */
    val isReconfigure: Boolean
        get() = this == RECONFIGURE

    companion object {

        /**
         * The host sends the same extras on both paths, so the only reliable
         * signal is the store: [alreadyBound] — does this `appWidgetId` already
         * carry a binding?
         */
        fun of(alreadyBound: Boolean): ConfigEntry =
            if (alreadyBound) RECONFIGURE else FIRST_PLACEMENT
    }
}
