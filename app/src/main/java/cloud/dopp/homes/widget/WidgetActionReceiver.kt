package cloud.dopp.homes.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.verbindung.Verbindung
import cloud.dopp.homes.verbindung.Zustand
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Headless handler for a device's tap — from the start screen's favorites,
 * a device widget (one device each, restored 2026-11-03) and an app-icon
 * shortcut (#97). Runs in the background with **no visible UI**. Two things defer to
 * [WidgetActionActivity]: the server's 403 confirm gate — a garage/door open,
 * any `lock.*` — and a lock's chooser ([OP_LOCK_CHOOSE], #92), which asks
 * *before* anything is called rather than after.
 *
 * The entity id is the identity and its domain is its prefix
 * ([DeviceShortcuts.domainOf]), so nothing here can be pointed at a device the
 * caller did not name. This receiver is not exported.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TAP) return
        // A device widget's tap may start the live service (Android 12+ allows it here).
        cloud.dopp.homes.realtime.Live.ensure(context)
        val entityId = intent.getStringExtra(EXTRA_ENTITY)?.takeIf { it.isNotBlank() } ?: return
        val op = intent.getStringExtra(EXTRA_OP) ?: OP_TOGGLE
        val domain = DeviceShortcuts.domainOf(entityId)
        val name = intent.getStringExtra(WidgetActionActivity.EXTRA_NAME).orEmpty().ifBlank { entityId }
        // A lock never acts on a tap (#92): it opens the chooser, and the pick
        // made there is the confirmation. Handled before the worker thread — no
        // fetch, no service call, so nothing can fire on this path.
        if (op == OP_LOCK_CHOOSE) {
            if (domain == "lock") {
                context.startActivity(
                    Intent(context, WidgetActionActivity::class.java)
                        .putExtra(EXTRA_ENTITY, entityId)
                        .putExtra(WidgetActionActivity.EXTRA_NAME, name)
                        .putExtra(WidgetActionActivity.EXTRA_FEATURES, intent.getIntExtra(WidgetActionActivity.EXTRA_FEATURES, -1))
                        .putExtra(WidgetActionActivity.EXTRA_LOCK_CHOOSE, true)
                        .addFlags(ActionDialog.TASK_FLAGS),
                )
            }
            return
        }
        // A start-screen control (owner, 2026-11-03): one named service on this
        // entity, never another domain's.
        val service = intent.getStringExtra(EXTRA_SERVICE)
        if (op == OP_CALL && (service == null || service.substringBefore('.') != domain)) return
        val data = intent.getStringExtra(EXTRA_DATA)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val app = context.applicationContext
        val pending = goAsync()
        thread {
            try {
                val api = ApiClient(app)
                // `api.call` RETURNS false on a non-2xx (#111): every branch carries its outcome.
                val outcome = when {
                    // with data (a brightness, a position, a setpoint) only on what Homes lets
                    // go freely: a refusal there is a failure, not a question
                    op == OP_CALL && data != null ->
                        runCatching { tapOutcome(api.call(entityId, service!!, data = data)) }
                            .getOrElse { if (it is ApiClient.SensitiveException) Tap.FAILED else throw it }
                    op == OP_CALL -> callOrConfirm(api, app, entityId, name, service!!)
                    domain == "light" || domain == "switch" -> tapOutcome(api.call(entityId, "$domain.toggle"))
                    domain == "cover" -> {
                        val card = api.getCard(entityId)
                        callOrConfirm(api, app, entityId, name, if (card?.isOn == true) "cover.close_cover" else "cover.open_cover")
                    }
                    domain == "lock" -> callOrConfirm(api, app, entityId, name, lockToggleService(api.getCard(entityId)?.state))
                    else -> Tap.DONE
                }
                if (outcome == Tap.FAILED) reportFailure(app, name)
                if (outcome != Tap.CONFIRMING) DeviceTaps.done(app, entityId)
            } catch (e: ApiClient.NotConfiguredException) {
                openHome(app)
            } catch (e: ApiClient.NotPairedException) {
                openHome(app)
            } catch (e: ApiClient.SensitiveException) {
                // A directly-called domain turned out to be gated after all: the
                // call did not run, and the reason is the confirm gate, not a dead server.
            } catch (e: Exception) {
                // Network or server error: say that the tap went nowhere (#111).
                reportFailure(app, name)
                DeviceTaps.done(app, entityId)
            } finally {
                pending?.finish()
            }
        }
    }

    /**
     * What one tap achieved. [CONFIRMING] is not a failure — the confirm dialog is
     * on screen and the user has yet to answer — and only [FAILED] earns a
     * "did not work" (#111).
     */
    private enum class Tap { DONE, CONFIRMING, FAILED }

    /** A plain `api.call` result as an outcome: `false` = the server refused it. */
    private fun tapOutcome(ok: Boolean): Tap = if (ok) Tap.DONE else Tap.FAILED

    /** Run the service; on the 403 sensitive gate, launch the confirm dialog. */
    private fun callOrConfirm(api: ApiClient, app: Context, entityId: String, name: String, service: String): Tap {
        return try {
            tapOutcome(api.call(entityId, service))
        } catch (e: ApiClient.SensitiveException) {
            app.startActivity(
                Intent(app, WidgetActionActivity::class.java)
                    .putExtra(EXTRA_ENTITY, entityId)
                    .putExtra(EXTRA_SERVICE, service)
                    .putExtra(WidgetActionActivity.EXTRA_NAME, name)
                    .addFlags(ActionDialog.TASK_FLAGS),
            )
            Tap.CONFIRMING
        }
    }

    private fun openHome(app: Context) {
        app.startActivity(
            Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        const val ACTION_TAP = "cloud.dopp.homes.widget.TAP"
        const val EXTRA_OP = "op"
        const val EXTRA_ENTITY = "entity_id"
        const val EXTRA_SERVICE = "service"

        const val OP_TOGGLE = "toggle"

        /** A start-screen control: [EXTRA_SERVICE] on the entity, with [EXTRA_DATA] (a JSON object) or none. */
        const val OP_CALL = "call"
        const val EXTRA_DATA = "data"

        /** A lock (#92): open the chooser — this op runs no service itself. */
        const val OP_LOCK_CHOOSE = "lock_choose"

        /** The tap for [entityId]: a lock opens its chooser, everything else toggles. */
        fun tap(ctx: Context, entityId: String, name: String, supportedFeatures: Int?): Intent =
            Intent(ctx, WidgetActionReceiver::class.java)
                .setAction(ACTION_TAP)
                .putExtra(EXTRA_ENTITY, entityId)
                .putExtra(WidgetActionActivity.EXTRA_NAME, name)
                .putExtra(WidgetActionActivity.EXTRA_FEATURES, supportedFeatures ?: -1)
                .putExtra(EXTRA_OP, if (DeviceShortcuts.domainOf(entityId) == "lock") OP_LOCK_CHOOSE else OP_TOGGLE)

        /** A start-screen control's call: [service] on [entityId], with [data] or none. */
        fun call(ctx: Context, entityId: String, name: String, service: String, data: Map<String, Any>?): Intent =
            Intent(ctx, WidgetActionReceiver::class.java)
                .setAction(ACTION_TAP)
                .putExtra(EXTRA_ENTITY, entityId)
                .putExtra(WidgetActionActivity.EXTRA_NAME, name)
                .putExtra(EXTRA_OP, OP_CALL)
                .putExtra(EXTRA_SERVICE, service)
                .apply { if (data != null) putExtra(EXTRA_DATA, JSONObject(data).toString()) }

        /**
         * Say out loud that a tap did **not** land (#111): a text toast, not a
         * dialog, and why, as [Verbindung] knows it — the box not reachable,
         * no network, the pairing gone. Best-effort — a failure to complain
         * never breaks the tap path.
         */
        fun reportFailure(ctx: Context, deviceLabel: String) {
            val app = ctx.applicationContext
            val why = when (Verbindung.zustand(app)) {
                Zustand.BOX_WEG -> R.string.widget_action_box_weg
                Zustand.KEIN_NETZ -> R.string.widget_action_kein_netz
                Zustand.NICHT_GEKOPPELT -> R.string.widget_action_nicht_gekoppelt
                else -> R.string.widget_action_failed
            }
            val text = app.getString(why, deviceLabel.ifBlank { "Gerät" })
            Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
            }
        }

        /**
         * Which bolt service a lock's toggle runs (#84). Only a confirmed `locked`
         * unlocks; every other reading falls to `lock.lock`, the securing
         * direction. It never returns `lock.open`. Pure → JVM-testable.
         */
        fun lockToggleService(state: String?): String =
            if (state?.trim()?.lowercase() == "locked") "lock.unlock" else "lock.lock"
    }
}

/**
 * A device tap finished: the start screen reads the favorites again (an
 * app-internal broadcast, received only while it is open), and the device
 * widgets of that entity read again.
 */
object DeviceTaps {
    const val ACTION_DONE = "cloud.dopp.homes.widget.DEVICE_TAP_DONE"

    fun done(ctx: Context, entityId: String) {
        runCatching { ctx.sendBroadcast(Intent(ACTION_DONE).setPackage(ctx.packageName)) }
        runCatching { DeviceWidgetProvider.refreshEntity(ctx, entityId) }
    }
}
