package cloud.dopp.haus.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.Device
import kotlin.concurrent.thread

/**
 * Publishes the app-icon long-press menu (#97): one **dynamic** shortcut per
 * device of the household.
 *
 * The list has to be dynamic — a static shortcut is written at build time and
 * can never know the user's devices. `res/xml/shortcuts.xml` holds only the
 * share target of the persona's sharing shortcut ([ShareTarget], #188), which is
 * published here with the devices and takes one of the launcher's slots.
 *
 * The device set is the **household catalogue** (#100), cached in [WidgetStore]
 * so the menu survives an offline republish.
 *
 * Which entries exist, in which order, and how many — [DeviceShortcuts], pure
 * and JVM-tested. This object only does the parts that need a `Context`: ask the
 * launcher for the cap, read the store, build the icons and intents.
 *
 * Everything is best-effort: a launcher that rejects the publish (or one of the
 * OEM launchers that supports no shortcuts at all) must never take the app down
 * with it.
 */
object AppShortcuts {

    /**
     * Republish the menu: when the app's screen becomes visible, so a
     * restore/reinstall is picked up too, and when the persona changes. Off the
     * caller's thread: it touches SharedPreferences and the launcher.
     */
    fun refreshAsync(ctx: Context) {
        val app = ctx.applicationContext
        thread { runCatching { refresh(app) } }
    }

    /** Synchronous publish — [refreshAsync] is the normal entry point. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        // Ask, never assume: launchers commonly show four, but the allowed number
        // is the launcher's to state (0 below API 25 → nothing is published).
        val cap = ShortcutManagerCompat.getMaxShortcutCountPerActivity(app)
        val share = if (cap > 0) runCatching { ShareTarget.info(app, rank = cap - 1) }.getOrNull() else null
        if (share == null) {
            // Android keeps a long-lived shortcut for the share sheet after it left the dynamic set.
            runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(app, listOf(ShareTarget.ID)) }
        }
        val entries = DeviceShortcuts.entries(
            catalogDevices(app), if (share == null) cap else cap - 1, WidgetStore.recentEntities(app),
        )
        val infos = entries.mapIndexed { i, e -> info(app, e, i) } + listOfNotNull(share)
        if (infos.isEmpty()) {
            runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(app) }
            return
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(app, infos) }
    }

    /**
     * The household catalogue: the cache while it is fresh, otherwise refetched
     * and remembered, and the (stale) cache again when the fetch fails.
     */
    private fun catalogDevices(ctx: Context): List<ShortcutDevice> {
        val cached = WidgetStore.shortcutCatalog(ctx)
        if (WidgetStore.shortcutCatalogFresh(ctx)) return cached
        val fresh = runCatching { catalogOf(ApiClient(ctx).listAddable()) }.getOrNull()
        if (fresh.isNullOrEmpty()) return cached
        WidgetStore.setShortcutCatalog(ctx, fresh)
        return fresh
    }

    /** The household's devices as shortcut candidates. */
    fun catalogOf(devices: List<Device>): List<ShortcutDevice> =
        devices.map { ShortcutDevice(it.entityId, it.name, it.domain, it.deviceClass) }

    private fun info(ctx: Context, entry: ShortcutEntry, rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(ctx, entry.id)
            .setShortLabel(entry.label)
            .setLongLabel(entry.label)
            // Not the widget glyph: that one is white on transparent and the
            // launcher turned it into a white disc (#99). See DeviceIcons.forShortcut.
            .setIcon(IconCompat.createWithResource(ctx, DeviceIcons.forShortcut(entry.device.domain)))
            .setRank(rank)
            .setIntent(intentFor(ctx, entry))
            .build()

    /**
     * The intent behind one entry. **No shortcut carries a service** — a tap
     * either opens a screen, or asks [WidgetActionReceiver] for the device's own
     * toggle, which is domain-checked there and gated by the server's 403. A
     * lock lands on the #92 chooser and nothing else, so the entry sits behind
     * the device lock and the latch stays unreachable from here.
     *
     * The **entity id is what identifies the device** (#100).
     */
    fun intentFor(ctx: Context, entry: ShortcutEntry): Intent {
        return when (entry.action) {
            ShortcutAction.CHOOSE -> Intent(ctx, WidgetActionActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(WidgetActionReceiver.EXTRA_ENTITY, entry.device.entityId)
                .putExtra(WidgetActionActivity.EXTRA_LOCK_CHOOSE, true)

            ShortcutAction.TOGGLE -> Intent(ctx, WidgetActionActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(WidgetActionReceiver.EXTRA_ENTITY, entry.device.entityId)
                .putExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, true)

            // A device the shortcut cannot act on opens the devices page in the browser.
            ShortcutAction.OPEN -> HausPages.intent(ctx, HausPages.RAEUME)
        }
            .apply {
                // For wording only.
                if (entry.action != ShortcutAction.OPEN) {
                    putExtra(WidgetActionActivity.EXTRA_NAME, entry.label)
                }
            }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
