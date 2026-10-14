package cloud.dopp.haus.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Finishing what `requestPinAppWidget` starts (#105).
 *
 * The in-app offers place widgets with `requestPinAppWidget`. Its **third**
 * argument is the `successCallback` — the only channel through which the app ever
 * learns the `appWidgetId` the host just assigned. Every call passed `null` and
 * relied on the provider's `configure` activity being started for the new
 * instance instead. **It is not**: the host places the widget and is done, so a
 * tool widget pinned from the app landed unbound and showed "Tool wählen" while
 * the picked tool sat parked in [WidgetStore.setPendingToolId] until its TTL ran
 * out. Confirmed on the device: long-press → *Einrichten* (which starts the
 * config activity by hand) redeemed the parked id and the rows appeared.
 *
 * So the callback is the primary path now and the parked id stays as the
 * **fallback** for hosts that do start the config activity. Both can reach the
 * same fresh instance, and together they must bind **exactly once**:
 * [WidgetStore.isBound] is that guard — whoever arrives first binds, the loser
 * steps aside — and the winner of the callback race spends the parked id
 * ([WidgetStore.clearPendingToolId]) so the config screen has nothing left to
 * re-bind from.
 *
 * Providers without a tool to bind get the callback too, for the same reason the
 * ticket names: they are one `configure` attribute away from the identical hole.
 * They are only [nudge]d into a redraw, **not** pushed into their config screen —
 * a host that *does* run the config activity would then be showing one in its own
 * task while we open a second in ours, and backing out of a first placement
 * answers `RESULT_CANCELED`, which costs the user the widget. The way out of an
 * unbound tile is its own tap instead (#104 for the 1×1 device tile,
 * [ToolWidgetProvider] here).
 */
object WidgetPin {

    const val ACTION_PINNED = "cloud.dopp.haus.widget.PINNED"
    const val EXTRA_TOOL_ID = "cloud.dopp.haus.widget.PIN_TOOL_ID"
    const val EXTRA_TOOL_LABEL = "cloud.dopp.haus.widget.PIN_TOOL_LABEL"
    const val EXTRA_PROVIDER = "cloud.dopp.haus.widget.PIN_PROVIDER"

    /** What the callback did with the instance the host just placed. */
    enum class Outcome {
        /** This callback bound it. */
        BOUND,

        /** Someone got there first (the config activity did run) — hands off. */
        ALREADY_BOUND,

        /** Nothing to bind from; the tile shows its own way into configuration. */
        NEEDS_CONFIG,
    }

    /**
     * Ask the host to place [provider], with the callback that finishes the job.
     * [toolId] (the generic tool widget) is both handed to the callback **and**
     * parked for the config activity — the two paths cross-check each other.
     */
    fun request(
        ctx: Context,
        mgr: AppWidgetManager,
        provider: Class<*>,
        toolId: String? = null,
        toolLabel: String? = null,
    ): Boolean {
        val component = ComponentName(ctx, provider)
        if (toolId != null) WidgetStore.setPendingToolId(ctx, toolId)
        return mgr.requestPinAppWidget(component, null, callback(ctx, component, toolId, toolLabel))
    }

    /** The intent the host completes with `EXTRA_APPWIDGET_ID` and sends back. */
    fun pinIntent(
        ctx: Context, provider: ComponentName, toolId: String?, toolLabel: String?,
    ): Intent {
        val i = Intent(ctx, WidgetPinReceiver::class.java)
            .setAction(ACTION_PINNED)
            .putExtra(EXTRA_PROVIDER, provider.className)
        if (toolId != null) {
            i.putExtra(EXTRA_TOOL_ID, toolId).putExtra(EXTRA_TOOL_LABEL, toolLabel.orEmpty())
        }
        return i
    }

    /**
     * Redeem a freshly placed instance. Idempotent by [WidgetStore.isBound], which
     * is what makes callback + parked id add up to exactly one binding no matter
     * which of them the host lets run first.
     */
    fun onPinned(ctx: Context, appWidgetId: Int, toolId: String?, toolLabel: String?): Outcome {
        if (WidgetStore.isBound(ctx, appWidgetId)) return Outcome.ALREADY_BOUND
        val tool = toolId?.ifBlank { null } ?: return Outcome.NEEDS_CONFIG
        WidgetStore.bindTool(ctx, appWidgetId, tool, toolLabel.orEmpty())
        // Spend the fallback: the config activity must not bind this a second time.
        WidgetStore.clearPendingToolId(ctx, tool)
        ToolWidgetProvider.requestRefresh(ctx, appWidgetId)
        return Outcome.BOUND
    }

    /**
     * Make a just-placed instance draw itself now — an explicit `APPWIDGET_UPDATE`
     * to its own provider, so an unbound tile shows its "einrichten" state (and its
     * tap into configuration) instead of an empty frame until the next refresh.
     */
    fun nudge(ctx: Context, appWidgetId: Int, providerClass: String?) {
        val cls = providerClass?.ifBlank { null } ?: return
        val i = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .setComponent(ComponentName(ctx.packageName, cls))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
        runCatching { ctx.sendBroadcast(i) }
    }

    private fun callback(
        ctx: Context, provider: ComponentName, toolId: String?, toolLabel: String?,
    ): PendingIntent {
        // MUTABLE is not a detail here: the host fills the freshly assigned
        // EXTRA_APPWIDGET_ID into this very intent, and an immutable one would
        // arrive without it — the id would be lost exactly as it is today.
        val mutable =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            ctx, requestCode(provider, toolId), pinIntent(ctx, provider, toolId, toolLabel),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
    }

    /** Per provider + tool, so two offers tapped in a row don't share a callback. */
    private fun requestCode(provider: ComponentName, toolId: String?): Int =
        700_000 + ((provider.className.hashCode() xor toolId.hashCode()) and 0xFFF)
}

/**
 * The `successCallback` receiver (#105). It runs with our identity (the host only
 * fires our own [PendingIntent]), so it is not exported.
 */
class WidgetPinReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        // No id means no widget to redeem — a host that answers without one leaves
        // the parked-id fallback untouched rather than binding something at random.
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val ctx = context.applicationContext
        val outcome = WidgetPin.onPinned(
            ctx, appWidgetId,
            intent.getStringExtra(WidgetPin.EXTRA_TOOL_ID),
            intent.getStringExtra(WidgetPin.EXTRA_TOOL_LABEL),
        )
        if (outcome == WidgetPin.Outcome.NEEDS_CONFIG) {
            WidgetPin.nudge(ctx, appWidgetId, intent.getStringExtra(WidgetPin.EXTRA_PROVIDER))
        }
    }
}
