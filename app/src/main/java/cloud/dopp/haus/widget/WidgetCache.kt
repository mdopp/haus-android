package cloud.dopp.haus.widget

import android.content.Context
import cloud.dopp.haus.data.BoxHome
import org.json.JSONObject

/**
 * Persistent **last-good render cache** for widgets (#46).
 *
 * The bug: on a transient token/network failure a provider re-rendered its
 * *empty* state — a Box widget flipped to "—" — so the homescreen looked wiped
 * "after a while / on certain events".
 *
 * The fix is **stale-then-fresh**: whenever a fetch succeeds we remember the
 * rendered payload here; when the next fetch fails the provider re-renders from
 * this cache instead of an empty placeholder. Only a widget that has *never*
 * loaded shows the placeholder.
 *
 * Storage is a **plain** `MODE_PRIVATE` prefs file — deliberately NOT encrypted,
 * so it is not coupled to the AndroidKeyStore wipe that used to take the token
 * (and thus the widgets) down with it. Values are non-secret UI state.
 */
object WidgetCache {
    private const val PREFS = "haus_widget_cache"

    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- Box overview (BoxOverviewWidgetProvider) ----------------------

    fun putHome(ctx: Context, id: Int, home: BoxHome) {
        val o = JSONObject()
            .put("servicesUp", home.servicesUp)
            .put("servicesFailed", home.servicesFailed)
            .put("servicesDown", home.servicesDown)
            .put("pendingApprovals", home.pendingApprovals)
            .put("pendingUpdates", home.pendingUpdates)
        write(ctx, "sbhome_$id", o)
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
    }

    fun getServiceCounts(ctx: Context, id: Int): Triple<Int, Int, Int>? {
        val o = read(ctx, "sbsvc_$id") ?: return null
        return try {
            Triple(o.optInt("ok"), o.optInt("warn"), o.optInt("fail"))
        } catch (e: Exception) {
            null
        }
    }

    // --- lifecycle -----------------------------------------------------------

    /** Drop every cached payload for [id] (widget removed / re-bound). */
    fun clear(ctx: Context, id: Int) {
        p(ctx).edit()
            .remove("sbhome_$id")
            .remove("sbsvc_$id")
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

}
