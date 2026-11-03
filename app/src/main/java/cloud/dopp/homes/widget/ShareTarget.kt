package cloud.dopp.homes.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import cloud.dopp.homes.R
import cloud.dopp.homes.data.Assistent
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.ui.HomeActivity

/**
 * The assistant in the share sheet (#188): one sharing shortcut, its name
 * („Homes“ until Homes names the box's own) with the app's icon, in the top
 * row of Android's share sheet (Direct Share). A tap sends the share to [cloud.dopp.homes.ui.ShareActivity], the same flow as the
 * sheet's own entry „In Homes merken“, which stays; so does the app's name.
 *
 * The shortcut names [CATEGORY]; `res/xml/shortcuts.xml` declares the share
 * target that ties that category to `text/plain` and the share activity.
 * Android 10 and up show it; below that Direct Share needs a chooser service,
 * which this app does not run (one service only, `ManifestTest`).
 *
 * [AppShortcuts] publishes it with the device entries, so a republish of
 * either never drops the other; it takes one of the launcher's slots. Without
 * a pairing there is no target, and the long-lived copy Android keeps for the
 * share sheet is removed too.
 */
object ShareTarget {

    /** What every share id of this app starts with, this one and any retired one. */
    const val ID_PREFIX = "share_"

    const val ID = ID_PREFIX + "assistent"

    /** The category the share target in `res/xml/shortcuts.xml` names. */
    const val CATEGORY = "cloud.dopp.homes.category.SHARE_TO_HOMES"

    /** The target with the assistant's name, or null while unpaired. */
    fun info(ctx: Context, rank: Int = 0): ShortcutInfoCompat? {
        if (!ServerStore.isConfigured(ctx) || !TokenStore.isPaired(ctx)) return null
        val name = Assistent.name(ctx)
        return ShortcutInfoCompat.Builder(ctx, ID)
            .setShortLabel(name)
            .setLongLabel(name)
            .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
            .setLongLived(true)
            .setCategories(setOf(CATEGORY))
            .setRank(rank)
            // Direct Share sends the share intent to the share activity; this
            // one is only used where the app icon's menu lists the shortcut
            .setIntent(Intent(ctx, HomeActivity::class.java).setAction(Intent.ACTION_VIEW))
            // Not excluded from the launcher: Android 13 and up keep no such
            // shortcut, so the share sheet never found it (androidx below 13
            // drops it before publishing). It is in the app icon's menu too.
            .build()
    }
}
