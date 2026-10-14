package cloud.dopp.haus.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
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
 * device the user has on the home screen.
 *
 * The list has to be dynamic — a static `res/xml/shortcuts.xml` is written at
 * build time and can never know the user's devices (the empty Bubblewrap stub
 * that used to sit there is gone with this change, like the notification icon in
 * #88).
 *
 * The device set is the **household catalogue** (#100), cached in [WidgetStore]
 * so the menu survives an offline republish, with the live
 * [DeviceWidgetProvider] instances merged in — a device that *is* on the home
 * screen keeps its `appWidgetId` so its tile can be refreshed after a tap, and a
 * device that is not still gets an entry. Requiring a widget first was the
 * reported defect.
 *
 * Which entries exist, in which order, and how many — [DeviceShortcuts], pure
 * and JVM-tested. This object only does the parts that need a `Context`: ask the
 * launcher for the cap, read the store, build the icons and intents.
 *
 * Everything is best-effort: a launcher that rejects the publish (or one of the
 * OEM launchers that supports no shortcuts at all) must never take a widget or a
 * config screen down with it.
 */
object AppShortcuts {

    /**
     * Republish the menu. Call after every change to the widget set — bind,
     * delete, and once when the hub becomes visible so a restore/reinstall is
     * picked up too. Off the caller's thread: it touches SharedPreferences and
     * the launcher.
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
        val entries = DeviceShortcuts.entries(
            candidates(app), cap, WidgetStore.recentEntities(app),
        )
        if (entries.isEmpty()) {
            runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(app) }
            return
        }
        val infos = entries.mapIndexed { i, e -> info(app, e, i) }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(app, infos) }
    }

    /**
     * Everything the menu may offer: the household catalogue with the placed
     * widgets merged in.
     *
     * The catalogue is refetched here — [refresh] already runs off the caller's
     * thread — and remembered, so the next republish works from the cache when
     * the server is unreachable. A device on a widget carries that instance's
     * `appWidgetId`; a device that is only in the catalogue carries none, which
     * is the case #100 is about. Placed widgets are appended rather than dropped
     * when the catalogue is empty (never paired, or a fetch that failed before
     * anything was ever cached), so the menu falls back to what #97 published.
     */
    fun candidates(ctx: Context): List<ShortcutDevice> {
        val app = ctx.applicationContext
        val widgets = boundDevices(app)
        val byEntity = widgets.associateBy { it.entityId }
        val catalog = catalogDevices(app).map { d ->
            val widget = byEntity[d.entityId] ?: return@map d
            d.copy(appWidgetId = widget.appWidgetId)
        }
        val known = catalog.mapTo(HashSet()) { it.entityId }
        return catalog + widgets.filter { it.entityId !in known }
    }

    /**
     * The household catalogue: the cache while it is fresh, otherwise refetched
     * and remembered, and the (stale) cache again when the fetch fails. The
     * picker fills the same cache for free whenever it lists the household, so in
     * practice this rarely has to ask.
     */
    private fun catalogDevices(ctx: Context): List<ShortcutDevice> {
        val cached = WidgetStore.shortcutCatalog(ctx)
        if (WidgetStore.shortcutCatalogFresh(ctx)) return cached
        val fresh = runCatching { catalogOf(ApiClient(ctx).listAddable()) }.getOrNull()
        if (fresh.isNullOrEmpty()) return cached
        WidgetStore.setShortcutCatalog(ctx, fresh)
        return fresh
    }

    /** The picker's device list as shortcut candidates — no widget ids yet. */
    fun catalogOf(devices: List<Device>): List<ShortcutDevice> =
        devices.map { ShortcutDevice(null, it.entityId, it.name, it.domain, it.deviceClass) }

    /** Remember a household the picker just listed, so the menu need not refetch it. */
    fun rememberCatalog(ctx: Context, devices: List<Device>) {
        if (devices.isEmpty()) return
        WidgetStore.setShortcutCatalog(ctx.applicationContext, catalogOf(devices))
    }

    /** Every configured device widget, newest instance last — order is [DeviceShortcuts]'. */
    fun boundDevices(ctx: Context): List<ShortcutDevice> {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val ids = mgr.getAppWidgetIds(ComponentName(app, DeviceWidgetProvider::class.java))
        return ids.toList().mapNotNull { id ->
            val entityId = WidgetStore.entityId(app, id) ?: return@mapNotNull null
            if (entityId.isBlank()) return@mapNotNull null
            ShortcutDevice(
                appWidgetId = id,
                entityId = entityId,
                name = WidgetStore.name(app, id),
                domain = WidgetStore.domain(app, id),
                deviceClass = WidgetStore.deviceClass(app, id),
            )
        }
    }

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
     * The **entity id is what identifies the device** (#100). An `appWidgetId`
     * rides along only when the device actually has a widget, and then only so
     * that its tile can be re-rendered after the tap; a catalogue device carries
     * none, and no placeholder is invented for it — an arbitrary id would name
     * some other device's binding.
     */
    fun intentFor(ctx: Context, entry: ShortcutEntry): Intent {
        val id = entry.device.appWidgetId
        return when (entry.action) {
            ShortcutAction.CHOOSE -> Intent(ctx, WidgetActionActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(WidgetActionReceiver.EXTRA_ENTITY, entry.device.entityId)
                .putExtra(WidgetActionActivity.EXTRA_LOCK_CHOOSE, true)

            ShortcutAction.TOGGLE -> Intent(ctx, WidgetActionActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(WidgetActionReceiver.EXTRA_ENTITY, entry.device.entityId)
                .putExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, true)

            // A device the shortcut cannot act on opens the house page in the browser.
            ShortcutAction.OPEN -> HausPages.intent(ctx, HausPages.START)
        }
            .apply {
                if (id != null && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                }
                // Only used where the store has no name to show (no widget).
                if (entry.action != ShortcutAction.OPEN) {
                    putExtra(WidgetActionActivity.EXTRA_NAME, entry.label)
                }
            }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
