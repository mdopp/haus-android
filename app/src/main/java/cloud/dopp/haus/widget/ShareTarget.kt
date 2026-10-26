package cloud.dopp.haus.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import cloud.dopp.haus.R
import cloud.dopp.haus.data.PersonaIcon
import cloud.dopp.haus.data.PersonaStore
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.ui.HomeActivity

/**
 * The persona in the share sheet (#188): one sharing shortcut, „Joshua“ with
 * his icon, in the top row of Android's share sheet (Direct Share). A tap
 * sends the share to [cloud.dopp.haus.ui.ShareActivity], the same flow as the
 * sheet's own entry „In Haus merken“, which stays; so does the app's name.
 *
 * The shortcut names [CATEGORY]; `res/xml/shortcuts.xml` declares the share
 * target that ties that category to `text/plain` and the share activity.
 * Android 10 and up show it; below that Direct Share needs a chooser service,
 * which this app does not run (one service only, `ManifestTest`).
 *
 * [AppShortcuts] publishes it with the device entries, so a republish of
 * either never drops the other; it takes one of the launcher's slots. Without
 * a pairing or a persona there is no target, and the long-lived copy Android
 * keeps for the share sheet is removed too.
 */
object ShareTarget {

    const val ID = "share_persona"

    /** The category the share target in `res/xml/shortcuts.xml` names. */
    const val CATEGORY = "cloud.dopp.haus.category.SHARE_TO_HAUS"

    /** The largest side of the icon handed to the launcher. */
    private const val ICON_SIDE = 256

    /** The target for the persona Haus last named, or null when there is none to show. */
    fun info(ctx: Context, rank: Int = 0): ShortcutInfoCompat? {
        if (!ServerStore.isConfigured(ctx) || !TokenStore.isPaired(ctx)) return null
        val persona = PersonaStore.get(ctx) ?: return null
        val b = ShortcutInfoCompat.Builder(ctx, ID)
            .setShortLabel(persona.display)
            .setLongLabel(persona.display)
            .setIcon(icon(ctx, persona.hasIcon))
            .setLongLived(true)
            .setCategories(setOf(CATEGORY))
            .setRank(rank)
            // Direct Share sends the share intent to the share activity; this
            // one is only used where the app icon's menu lists the shortcut
            .setIntent(Intent(ctx, HomeActivity::class.java).setAction(Intent.ACTION_VIEW))
        // Android 13 can keep it out of the app icon's menu. Below that androidx
        // would not publish an excluded shortcut at all, so there it is in both.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            b.setExcludedFromSurfaces(ShortcutInfoCompat.SURFACE_LAUNCHER)
        }
        return b.build()
    }

    /** The persona's own icon, else the app's. */
    private fun icon(ctx: Context, hasIcon: Boolean): IconCompat {
        val png = if (hasIcon) PersonaIcon.accept(PersonaStore.icon(ctx)) else null
        val bitmap = png?.let { runCatching { PersonaIcon.decode(it, ICON_SIDE) }.getOrNull() }
        return bitmap?.let(IconCompat::createWithBitmap) ?: IconCompat.createWithResource(ctx, R.mipmap.ic_launcher)
    }
}
