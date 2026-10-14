package cloud.dopp.haus.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import cloud.dopp.haus.R
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.ui.HomeActivity
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Headless handler for every device-widget control — toggle, brightness ±,
 * cover up/stop/down, and the lock's bolt (#84). Runs in the background with
 * **no visible UI** (so taps don't flash a screen). Two things defer to
 * [WidgetActionActivity]: the server's 403 confirm gate — a garage/door open,
 * any `lock.*` — and the 1×1 lock tile's chooser ([OP_LOCK_CHOOSE], #92), which
 * asks *before* anything is called rather than after.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TAP) return
        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val op = intent.getStringExtra(EXTRA_OP) ?: OP_TOGGLE
        // Refresh is a pure re-render (#26) — no entity call, just re-fetch state.
        if (op == OP_REFRESH) {
            DeviceWidgetProvider.requestRefresh(context.applicationContext, id)
            return
        }
        // With a widget, identity comes from the store — the intent's own entity is
        // ignored, as it always was. Without one (an app-icon shortcut for a device
        // that is not on the home screen, #100) the entity id *is* the identity and
        // the domain is its prefix, so nothing here can be pointed at a device the
        // caller did not name. This receiver is not exported.
        val hasWidget = id != AppWidgetManager.INVALID_APPWIDGET_ID
        val entityId = if (hasWidget) {
            WidgetStore.entityId(context, id) ?: return
        } else {
            intent.getStringExtra(EXTRA_ENTITY)?.takeIf { it.isNotBlank() } ?: return
        }
        val domain = if (hasWidget) WidgetStore.domain(context, id) else DeviceShortcuts.domainOf(entityId)
        // The 1×1 lock tile never acts on a tap (#92): it opens the chooser, and
        // the pick made there is the confirmation. Handled before the worker
        // thread — no fetch, no service call, so nothing can fire on this path.
        // Domain-checked so a stale PendingIntent can't aim it at another device.
        if (op == OP_LOCK_CHOOSE) {
            if (domain == "lock") {
                context.startActivity(
                    Intent(context, WidgetActionActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .putExtra(EXTRA_ENTITY, entityId)
                        .putExtra(WidgetActionActivity.EXTRA_LOCK_CHOOSE, true)
                        .addFlags(ActionDialog.TASK_FLAGS),
                )
            }
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        thread {
            try {
                val api = ApiClient(app)
                // `api.call` RETURNS false on a non-2xx — during the outage of
                // 29./30.08. `/napi/ha/call` answered 500 to every tap and this
                // block threw nothing, so the result was dropped and the tap looked
                // like it had worked (#111). Every branch now carries its outcome.
                val outcome = when (op) {
                    OP_BRIGHT_UP -> tapOutcome(api.call(entityId, "light.turn_on", data = step(+STEP)))
                    OP_BRIGHT_DOWN -> tapOutcome(api.call(entityId, "light.turn_on", data = step(-STEP)))
                    OP_COVER_STOP -> tapOutcome(api.call(entityId, "cover.stop_cover"))
                    OP_COVER_CLOSE -> tapOutcome(api.call(entityId, "cover.close_cover"))
                    OP_COVER_OPEN -> callOrConfirm(api, app, id, entityId, "cover.open_cover")
                    OP_LOCK -> callOrConfirm(api, app, id, entityId, "lock.lock")
                    OP_UNLOCK -> callOrConfirm(api, app, id, entityId, "lock.unlock")
                    else -> when (domain) { // OP_TOGGLE
                        "light", "switch" -> tapOutcome(api.call(entityId, "$domain.toggle"))
                        "cover" -> {
                            val card = api.getCard(entityId)
                            val service = if (card?.isOn == true) "cover.close_cover" else "cover.open_cover"
                            callOrConfirm(api, app, id, entityId, service)
                        }
                        "lock" -> callOrConfirm(
                            api, app, id, entityId, lockToggleService(api.getCard(entityId)?.state),
                        )
                        else -> Tap.DONE
                    }
                }
                if (outcome != Tap.CONFIRMING) {
                    // Ordering fuel for the app-icon menu (#100) — the device was
                    // used, whichever surface asked and whatever the server said.
                    WidgetStore.noteEntityUsed(app, entityId)
                }
                // The tap is also a measurement of the connection (#111) — the one
                // that happens exactly while the user is looking. A CONFIRMING tap
                // counts as reached: that dialog exists because the *server*
                // answered 403.
                ActionProbe.record(app, id, reachOf(outcome))
                when (outcome) {
                    Tap.DONE -> if (hasWidget) DeviceWidgetProvider.requestRefresh(app, id)
                    // A refused/failed call must not look like nothing happened.
                    Tap.FAILED -> {
                        reportFailure(app, label(app, id, entityId))
                        // …and the tile says so too, without waiting out the 90 min:
                        // this is not a guess about age, it is a failure just seen.
                        if (hasWidget) DeviceWidgetProvider.requestRefresh(app, id)
                    }
                    Tap.CONFIRMING -> Unit // the dialog is the feedback
                }
            } catch (e: ApiClient.NotConfiguredException) {
                openHome(app)
            } catch (e: ApiClient.NotPairedException) {
                openHome(app)
            } catch (e: ApiClient.SensitiveException) {
                // A directly-called domain turned out to be gated after all: the
                // call did not run, but the reason is the confirm gate, not a dead
                // server — so no "Haus antwortet nicht" here, and the tile is
                // not marked either. The 403 came *from* the server (#111).
                ActionProbe.record(app, id, ActionProbe.Reach.REACHED)
            } catch (e: Exception) {
                // Network or server error: the tile keeps its last rendered state
                // (#46) — but the user gets told the tap went nowhere (#111), and
                // the tile is marked at once, because this is a measurement, not a
                // guess about how old a value might be.
                ActionProbe.record(app, id, ActionProbe.Reach.UNREACHABLE)
                reportFailure(app, label(app, id, entityId))
                if (hasWidget) DeviceWidgetProvider.requestRefresh(app, id)
            } finally {
                pending?.finish()
            }
        }
    }

    /**
     * What one tap achieved. [CONFIRMING] is not a failure — the confirm dialog is
     * on screen and the user has yet to answer — and telling it apart from
     * [FAILED] is the whole point: only [FAILED] earns a "did not work" (#111).
     */
    private enum class Tap { DONE, CONFIRMING, FAILED }

    /** A plain `api.call` result as an outcome: `false` = the server refused it. */
    private fun tapOutcome(ok: Boolean): Tap = if (ok) Tap.DONE else Tap.FAILED

    /**
     * What the tap measured about the connection (#111). Only [Tap.FAILED] is a
     * failure to reach the server; [Tap.CONFIRMING] means the server answered 403,
     * which is as alive as it gets.
     */
    private fun reachOf(outcome: Tap): ActionProbe.Reach =
        if (outcome == Tap.FAILED) ActionProbe.Reach.UNREACHABLE else ActionProbe.Reach.REACHED

    /** Run the service; on the 403 sensitive gate, launch the confirm dialog. */
    private fun callOrConfirm(api: ApiClient, app: Context, id: Int, entityId: String, service: String): Tap {
        return try {
            tapOutcome(api.call(entityId, service))
        } catch (e: ApiClient.SensitiveException) {
            app.startActivity(
                Intent(app, WidgetActionActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(EXTRA_ENTITY, entityId)
                    .putExtra(EXTRA_SERVICE, service)
                    .addFlags(ActionDialog.TASK_FLAGS),
            )
            Tap.CONFIRMING
        }
    }

    /** What to call the device when reporting a failed tap. */
    private fun label(app: Context, id: Int, entityId: String): String =
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            WidgetStore.name(app, id).ifBlank { entityId }
        } else {
            entityId
        }

    private fun step(pct: Int) = JSONObject().put("brightness_step_pct", pct)

    private fun openHome(app: Context) {
        app.startActivity(
            Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        const val ACTION_TAP = "cloud.dopp.haus.widget.TAP"
        const val EXTRA_OP = "op"
        const val EXTRA_ENTITY = "entity_id"
        const val EXTRA_SERVICE = "service"

        const val OP_TOGGLE = "toggle"
        const val OP_REFRESH = "refresh"
        const val OP_BRIGHT_UP = "bright_up"
        const val OP_BRIGHT_DOWN = "bright_down"
        const val OP_COVER_OPEN = "cover_open"
        const val OP_COVER_STOP = "cover_stop"
        const val OP_COVER_CLOSE = "cover_close"
        const val OP_LOCK = "lock_lock"
        const val OP_UNLOCK = "lock_unlock"

        /** 1×1 lock tile (#92): open the chooser — this op runs no service itself. */
        const val OP_LOCK_CHOOSE = "lock_choose"

        private const val STEP = 20 // brightness step, %

        /**
         * Say out loud that a tap did **not** land (#111). The user's account of
         * the outage was "ich tippe und nichts passiert": the call 500'd, the tile
         * kept its (correct-looking, day-old) value and nothing else happened, so
         * there was no way to tell a dead server from a slow one.
         *
         * A text toast, not a dialog: the tap already cost the user a touch, and a
         * modal per failed tap during an hours-long outage is its own punishment.
         * Best-effort — a failure to complain never breaks the tap path.
         */
        fun reportFailure(ctx: Context, deviceLabel: String) {
            val app = ctx.applicationContext
            val text = app.getString(R.string.widget_action_failed, deviceLabel.ifBlank { "Gerät" })
            Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
            }
        }

        /**
         * Which bolt service a lock's toggle runs (#84). Only a confirmed `locked`
         * unlocks; every other reading — `unlocked`, `jammed`, `unknown`, a missing
         * state — falls to `lock.lock`, the securing direction. It never returns
         * `lock.open`: that pulls the latch and opens the door, which no widget tap
         * may reach. Pure → JVM-testable.
         */
        fun lockToggleService(state: String?): String =
            if (state?.trim()?.lowercase() == "locked") "lock.unlock" else "lock.lock"
    }
}
