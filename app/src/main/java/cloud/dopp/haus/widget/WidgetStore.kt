package cloud.dopp.haus.widget

import android.content.Context

/**
 * Per-instance binding for the device widget: maps an `appWidgetId` to the
 * chosen entity plus cached meta (name/domain/device_class) so the provider can
 * render and route taps without a network round-trip for the identity.
 */
object WidgetStore {
    private const val PREFS = "haus_widgets"

    private fun p(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bind(ctx: Context, id: Int, entityId: String, name: String, domain: String, deviceClass: String?) {
        p(ctx).edit()
            .putString("e_$id", entityId)
            .putString("n_$id", name)
            .putString("d_$id", domain)
            .putString("c_$id", deviceClass ?: "")
            .apply()
    }

    fun entityId(ctx: Context, id: Int): String? = p(ctx).getString("e_$id", null)
    fun name(ctx: Context, id: Int): String = p(ctx).getString("n_$id", "") ?: ""
    fun domain(ctx: Context, id: Int): String = p(ctx).getString("d_$id", "") ?: ""
    fun deviceClass(ctx: Context, id: Int): String? =
        p(ctx).getString("c_$id", "")?.ifBlank { null }

    /**
     * Does [id] already carry a binding of **any** kind — device or tool? The exactly-once guard of the pin path (#105): the
     * `successCallback` and the config activity can both reach a freshly placed
     * instance, so whoever arrives first binds and the other steps aside.
     */
    fun isBound(ctx: Context, id: Int): Boolean =
        entityId(ctx, id) != null || toolId(ctx, id) != null

    fun unbind(ctx: Context, id: Int) {
        p(ctx).edit()
            .remove("e_$id").remove("n_$id").remove("d_$id").remove("c_$id")
            .remove("range_$id")
            .apply()
    }

    /** Per-instance Verlauf history window (24h/7d), persisted across refreshes. */
    fun range(ctx: Context, id: Int): EnergyRange =
        EnergyRange.fromKey(p(ctx).getString("range_$id", null))

    fun setRange(ctx: Context, id: Int, range: EnergyRange) {
        p(ctx).edit().putString("range_$id", range.key).apply()
    }

    /**
     * Last-known active-devices list, persisted as JSON so the collection widget's
     * factory renders **instantly** from cache while the provider re-fetches in the
     * background (#28 stale-then-fresh). Shared across all active widgets (the list
     * is the whole household roster, not per-instance).
     */
    fun activeCacheJson(ctx: Context): String? = p(ctx).getString(KEY_ACTIVE_CACHE, null)

    fun setActiveCacheJson(ctx: Context, json: String) {
        p(ctx).edit().putString(KEY_ACTIVE_CACHE, json).apply()
    }

    private const val KEY_ACTIVE_CACHE = "active_cache_json"

    // --- app-icon shortcut menu (#97/#100) -----------------------------------

    /** Last-seen household catalogue for the app-icon menu — see [ShortcutCatalog]. */
    fun shortcutCatalog(ctx: Context): List<ShortcutDevice> =
        ShortcutCatalog.decode(p(ctx).getString(KEY_SHORTCUT_CATALOG, null))

    fun setShortcutCatalog(ctx: Context, devices: List<ShortcutDevice>) {
        p(ctx).edit()
            .putString(KEY_SHORTCUT_CATALOG, ShortcutCatalog.encode(devices))
            .putLong(KEY_SHORTCUT_CATALOG_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Is the cached catalogue recent enough to publish from without asking the
     * server? The menu is republished on every widget change and every visit to
     * the hub, and a household roster changes in days, not minutes — refetching
     * each time would spend a request on an answer we already have.
     */
    fun shortcutCatalogFresh(ctx: Context): Boolean {
        if (p(ctx).getString(KEY_SHORTCUT_CATALOG, null).isNullOrBlank()) return false
        val at = p(ctx).getLong(KEY_SHORTCUT_CATALOG_AT, 0L)
        return System.currentTimeMillis() - at in 0..SHORTCUT_CATALOG_TTL_MS
    }

    /** Entity ids by last switched, most recent first — see [ShortcutUsage]. */
    fun recentEntities(ctx: Context): List<String> =
        ShortcutUsage.decode(p(ctx).getString(KEY_SHORTCUT_RECENT, null))

    /**
     * Remember that [entityId] was just switched, so the app-icon menu can order
     * by last use (#100). Called from every path that actually runs a service —
     * [WidgetActionReceiver] and [WidgetActionActivity] — whether the tap came
     * from a widget, a tile or a shortcut: "zuletzt benutzt" means the device,
     * not the surface.
     */
    fun noteEntityUsed(ctx: Context, entityId: String) {
        if (entityId.isBlank()) return
        val next = ShortcutUsage.record(recentEntities(ctx), entityId)
        p(ctx).edit().putString(KEY_SHORTCUT_RECENT, ShortcutUsage.encode(next)).apply()
    }

    private const val KEY_SHORTCUT_CATALOG = "shortcut_catalog_json"
    private const val KEY_SHORTCUT_CATALOG_AT = "shortcut_catalog_at"
    private const val KEY_SHORTCUT_RECENT = "shortcut_recent_json"

    /** How long a cached catalogue is published from before it is refetched. */
    const val SHORTCUT_CATALOG_TTL_MS = 6 * 60 * 60 * 1000L

    // --- generic .tool widget (#70/#72) --------------------------------------

    /** Bind [id] to a `tool-id` from the catalog, with its label for the header. */
    fun bindTool(ctx: Context, id: Int, toolId: String, label: String) {
        p(ctx).edit()
            .putString("tool_$id", toolId)
            .putString("toolname_$id", label)
            .remove("toolcells_$id")
            .apply()
    }

    fun toolId(ctx: Context, id: Int): String? = p(ctx).getString("tool_$id", null)

    fun toolLabel(ctx: Context, id: Int): String =
        p(ctx).getString("toolname_$id", "") ?: ""

    /** Update just the header label (the catalog renamed the tool) — keeps the rows. */
    fun setToolLabel(ctx: Context, id: Int, label: String) {
        p(ctx).edit().putString("toolname_$id", label).apply()
    }

    fun unbindTool(ctx: Context, id: Int) {
        p(ctx).edit()
            .remove("tool_$id").remove("toolname_$id").remove("toolcells_$id")
            .apply()
    }

    /**
     * Last-good mapped rows for one tool widget, as [ToolCells] JSON. Same
     * stale-then-fresh contract as [activeCacheJson]: the collection factory draws
     * from here instantly, and a failed refresh leaves the previous rows standing
     * instead of blanking the widget (#46).
     */
    fun toolCellsJson(ctx: Context, id: Int): String? = p(ctx).getString("toolcells_$id", null)

    fun setToolCellsJson(ctx: Context, id: Int, json: String) {
        p(ctx).edit().putString("toolcells_$id", json).apply()
    }

    /**
     * Last-seen raw `/napi/defs/tool` body — the picker's offline fallback, so the
     * in-app tool section still lists the known tools when the catalog fetch fails.
     * Shared across instances (the catalog is per-server, not per-widget).
     */
    fun toolCatalogJson(ctx: Context): String? = p(ctx).getString(KEY_TOOL_CATALOG, null)

    fun setToolCatalogJson(ctx: Context, json: String) {
        p(ctx).edit().putString(KEY_TOOL_CATALOG, json).apply()
    }

    /**
     * One-shot hand-off from the in-app tool picker to the widget's config screen:
     * `requestPinAppWidget` can't carry an extra to the new instance, so the picked
     * `tool-id` is parked here and consumed by the config activity, which then
     * binds without asking again. Pinning from the launcher's widget tray leaves it
     * unset, so that path still shows the tool list.
     *
     * Since #105 this is the **fallback**, not the binding path: most hosts never
     * start the config activity after a pin, so the primary redemption runs off the
     * pin `successCallback` ([WidgetPin]) — which spends this id via
     * [clearPendingToolId] so the two paths bind exactly once between them.
     */
    fun setPendingToolId(ctx: Context, toolId: String) {
        p(ctx).edit()
            .putString(KEY_PENDING_TOOL, toolId)
            .putLong(KEY_PENDING_TOOL_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Take the parked id, if any, and forget it. Expires after
     * [PENDING_TOOL_TTL_MS] so a dismissed pin dialog can't silently bind a widget
     * the user later adds from the launcher's tray — that path asks again.
     */
    fun consumePendingToolId(ctx: Context): String? {
        val v = p(ctx).getString(KEY_PENDING_TOOL, null)
        val at = p(ctx).getLong(KEY_PENDING_TOOL_AT, 0L)
        if (v != null) {
            p(ctx).edit().remove(KEY_PENDING_TOOL).remove(KEY_PENDING_TOOL_AT).apply()
        }
        val fresh = System.currentTimeMillis() - at in 0..PENDING_TOOL_TTL_MS
        return if (fresh) v?.ifBlank { null } else null
    }

    /**
     * Drop the parked id **iff** it is still [toolId] — the pin callback (#105) has
     * just bound that tool, so the fallback has nothing left to do. Scoped to the
     * id it recognises so a later pin, already parked, keeps its own hand-off.
     */
    fun clearPendingToolId(ctx: Context, toolId: String) {
        if (p(ctx).getString(KEY_PENDING_TOOL, null) != toolId) return
        p(ctx).edit().remove(KEY_PENDING_TOOL).remove(KEY_PENDING_TOOL_AT).apply()
    }

    private const val KEY_TOOL_CATALOG = "tool_catalog_json"
    private const val KEY_PENDING_TOOL = "pending_tool_id"
    private const val KEY_PENDING_TOOL_AT = "pending_tool_at"

    /** How long a picked-but-not-yet-placed tool id stays valid. */
    const val PENDING_TOOL_TTL_MS = 5 * 60 * 1000L
}
