package cloud.dopp.haus.widget

import android.content.Context
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.Card
import cloud.dopp.haus.data.BoxHome
import org.json.JSONObject

/**
 * Persistent **last-good render cache** for widgets (#46).
 *
 * The bug: on a transient token/network failure a provider re-rendered its
 * *empty* state — a device widget flipped to "↻ tippen", a Box widget to
 * "—" — so the homescreen looked wiped "after a while / on certain events".
 * [StateEpochGuard]'s in-memory memory doesn't survive process death either, so a
 * reboot then a slow first fetch blanked everything.
 *
 * The fix is the same **stale-then-fresh** idea the active-devices collection
 * already uses ([WidgetStore.activeCacheJson]): whenever a fetch succeeds we
 * remember the rendered payload here; when the next fetch fails the provider
 * re-renders from this cache instead of an empty placeholder. Only a widget that
 * has *never* loaded shows the placeholder.
 *
 * Storage is a **plain** `MODE_PRIVATE` prefs file — deliberately NOT encrypted,
 * so it is not coupled to the AndroidKeyStore wipe that used to take the token
 * (and thus the widgets) down with it. Values are non-secret UI state.
 */
object WidgetCache {
    private const val PREFS = "haus_widget_cache"

    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- Device card (DeviceWidgetProvider) ---------------------------------

    fun putCard(ctx: Context, id: Int, card: Card) {
        val o = JSONObject()
            .put("entityId", card.entityId)
            .put("name", card.name)
            .put("domain", card.domain)
            .putOpt("deviceClass", card.deviceClass)
            .putOpt("state", card.state)
            .putOpt("unit", card.unit)
        card.brightness?.let { o.put("brightness", it) }
        card.position?.let { o.put("position", it) }
        card.temperature?.let { o.put("temperature", it) }
        // Colour (#87) rides along so the stale-render keeps the swatch and its
        // current tint instead of dropping to the plain −/+ row on one bad fetch.
        card.rgbColor?.let { o.put("rgbColor", it) }
        if (card.supportedColorModes.isNotEmpty()) {
            o.put("colorModes", org.json.JSONArray(card.supportedColorModes))
        }
        card.colorMode?.let { o.put("colorMode", it) }
        card.colorTemp?.let { o.put("colorTemp", it) }
        card.updatedAtMs?.let { o.put("updatedAtMs", it) }
        // The capability bitmask rides along (#92): the lock chooser reads it from
        // here, so it must survive a process restart — otherwise a cold tap would
        // silently drop the "Tür öffnen" entry on a lock that has a latch.
        card.supportedFeatures?.let { o.put("supportedFeatures", it) }
        write(ctx, "card_$id", o)
        noteFetch(ctx, id)
    }

    fun getCard(ctx: Context, id: Int): Card? {
        val o = read(ctx, "card_$id") ?: return null
        return try {
            Card(
                entityId = o.getString("entityId"),
                name = o.optString("name"),
                domain = o.optString("domain"),
                deviceClass = o.optStringOrNull("deviceClass"),
                state = o.optStringOrNull("state"),
                unit = o.optStringOrNull("unit"),
                brightness = if (o.has("brightness")) o.optInt("brightness") else null,
                position = if (o.has("position")) o.optInt("position") else null,
                temperature = if (o.has("temperature")) o.optDouble("temperature") else null,
                rgbColor = if (o.has("rgbColor")) o.optInt("rgbColor") else null,
                supportedColorModes = ApiClient.stringList(o.optJSONArray("colorModes")),
                colorMode = o.optStringOrNull("colorMode"),
                colorTemp = if (o.has("colorTemp")) o.optInt("colorTemp") else null,
                updatedAtMs = if (o.has("updatedAtMs")) o.optLong("updatedAtMs") else null,
                supportedFeatures = if (o.has("supportedFeatures")) o.optInt("supportedFeatures") else null,
            )
        } catch (e: Exception) {
            null
        }
    }

    // --- Box overview (BoxOverviewWidgetProvider) ----------------------

    fun putHome(ctx: Context, id: Int, home: BoxHome) {
        val o = JSONObject()
            .put("servicesUp", home.servicesUp)
            .put("servicesFailed", home.servicesFailed)
            .put("servicesDown", home.servicesDown)
            .put("pendingApprovals", home.pendingApprovals)
            .put("pendingUpdates", home.pendingUpdates)
        write(ctx, "sbhome_$id", o)
        noteFetch(ctx, id)
    }

    fun getHome(ctx: Context, id: Int): BoxHome? {
        val o = read(ctx, "sbhome_$id") ?: return null
        return try {
            BoxHome(
                servicesUp = o.optInt("servicesUp"),
                servicesFailed = o.optInt("servicesFailed"),
                servicesDown = o.optInt("servicesDown"),
                pendingApprovals = o.optInt("pendingApprovals"),
                pendingUpdates = o.optInt("pendingUpdates"),
            )
        } catch (e: Exception) {
            null
        }
    }

    // --- Box service counts (BoxServicesWidgetProvider) ----------------

    fun putServiceCounts(ctx: Context, id: Int, counts: Triple<Int, Int, Int>) {
        val o = JSONObject()
            .put("ok", counts.first)
            .put("warn", counts.second)
            .put("fail", counts.third)
        write(ctx, "sbsvc_$id", o)
        noteFetch(ctx, id)
    }

    fun getServiceCounts(ctx: Context, id: Int): Triple<Int, Int, Int>? {
        val o = read(ctx, "sbsvc_$id") ?: return null
        return try {
            Triple(o.optInt("ok"), o.optInt("warn"), o.optInt("fail"))
        } catch (e: Exception) {
            null
        }
    }

    // --- freshness: when did we last hear from the server (#111) -------------

    /**
     * Remember that a fetch for [id] **succeeded** at [atMs]. Every `put*` above
     * calls this, and only they do — a payload landing here *is* a successful
     * round-trip, whether it came from a poll or from the realtime push. A failed
     * fetch writes nothing, so the recorded time keeps ageing and [Staleness]
     * eventually marks the tile.
     *
     * This is deliberately **not** `Card.updatedAtMs`: that is Home Assistant's
     * state time (ordering fuel for `StateEpochGuard`), and a lamp unchanged for a
     * week is not stale. What matters here is when *we* last heard anything.
     */
    fun noteFetch(ctx: Context, id: Int, atMs: Long = System.currentTimeMillis()) {
        // Hearing from the server clears the probe's mark in the same breath
        // (#111): whatever the last failed tap knew, it is now out of date.
        runCatching { p(ctx).edit().putLong("fetch_$id", atMs).remove("fail_$id").apply() }
    }

    /**
     * Remember that a **user-triggered action** for [id] did not reach the server
     * at [atMs] (#111 follow-up). This is the active half of the freshness
     * question: the tap itself measured the connection, and it measured it while
     * the user was watching — so the tile may say so at once instead of waiting
     * out [Staleness.THRESHOLD_MS].
     *
     * Only a genuine failure to reach the server gets here. A refusal the server
     * *authored* — the 403 confirm gate, a forbidden action — proves it is very
     * much alive; [ActionProbe] separates the two.
     *
     * The fetch time is deliberately left alone: the last known value keeps its
     * honest age, and the next successful fetch clears this mark via [noteFetch].
     */
    fun noteUnreachable(ctx: Context, id: Int, atMs: Long = System.currentTimeMillis()) {
        runCatching { p(ctx).edit().putLong("fail_$id", atMs).apply() }
    }

    /**
     * When a user-triggered action last failed to reach the server for [id], or
     * `null` when the connection has not been caught failing since.
     */
    fun unreachableSince(ctx: Context, id: Int): Long? =
        runCatching { p(ctx).getLong("fail_$id", 0L) }.getOrNull()?.takeIf { it > 0L }

    /**
     * When the last successful fetch for [id] landed, or `null` when there is no
     * record — a freshly bound tile, or a cache written before #111 tracked this.
     * "No record" is not "stale": see [Staleness.State.UNKNOWN].
     */
    fun fetchedAt(ctx: Context, id: Int): Long? =
        runCatching { p(ctx).getLong("fetch_$id", 0L) }.getOrNull()?.takeIf { it > 0L }

    // --- lifecycle -----------------------------------------------------------

    /** Drop every cached payload for [id] (widget removed / re-bound). */
    fun clear(ctx: Context, id: Int) {
        p(ctx).edit()
            .remove("card_$id")
            .remove("sbhome_$id")
            .remove("sbsvc_$id")
            // The fetch time goes with the payload it dated (#111): a re-bound
            // tile must start with no history, not inherit the old one's age —
            // nor a failed tap the previous device on this id had to endure.
            .remove("fetch_$id")
            .remove("fail_$id")
            .apply()
    }

    // --- helpers -------------------------------------------------------------

    private fun write(ctx: Context, key: String, o: JSONObject) {
        runCatching { p(ctx).edit().putString(key, o.toString()).apply() }
    }

    private fun read(ctx: Context, key: String): JSONObject? {
        val s = runCatching { p(ctx).getString(key, null) }.getOrNull() ?: return null
        return runCatching { JSONObject(s) }.getOrNull()
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key).ifBlank { null }
}
