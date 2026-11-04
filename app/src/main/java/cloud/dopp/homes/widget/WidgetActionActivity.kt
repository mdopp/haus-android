package cloud.dopp.homes.widget

import android.app.Activity
import android.os.Bundle
import cloud.dopp.homes.R
import cloud.dopp.homes.data.ApiClient
import kotlin.concurrent.thread

/**
 * A device action's one visible screen. Three jobs, all of them things a
 * headless receiver cannot do itself:
 *
 * 1. **Confirm dialog** for a sensitive action (garage/door/gate, lock, alarm
 *    panel). Launched by the headless [WidgetActionReceiver] only when the
 *    server returns the 403 confirm gate — so ordinary toggles never spawn a
 *    visible screen. Whether a confirm is needed stays server-authoritative;
 *    only the wording is decided here, via [ConfirmVerb].
 * 2. **Lock chooser** (#92) — the one dialog that is not a reaction to a call,
 *    but the thing that decides *which* call is made. A lock on the start
 *    screen and its app-icon shortcut (#97) open exactly this, never a call.
 * 3. **Shortcut trampoline** (#97): an app-icon shortcut can only start an
 *    activity, so a non-sensitive device's shortcut lands here and is handed
 *    straight to [WidgetActionReceiver] as the device's own toggle.
 *
 * The activity deliberately declares **no `showWhenLocked`** and dismisses no
 * keyguard: sitting behind the device lock is what makes a front-door control
 * acceptable at all.
 */
class WidgetActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entityId = intent.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY)?.takeIf { it.isNotBlank() }
        if (entityId == null) {
            finish()
            return
        }
        // Lock chooser (#92) carries no service — the pick names it.
        if (intent.getBooleanExtra(EXTRA_LOCK_CHOOSE, false)) {
            showLockChooser(entityId)
            return
        }
        // App-icon shortcut tap (#97). The domain is **never taken from the
        // intent**: it is the entity id's own prefix, the very string the call
        // would be made against, so a stale or forged shortcut aimed at a lock
        // does nothing at all here.
        if (intent.getBooleanExtra(EXTRA_SHORTCUT_TOGGLE, false)) {
            if (DeviceShortcuts.actsDirectly(DeviceShortcuts.domainOf(entityId))) {
                sendBroadcast(WidgetActionReceiver.tap(this, entityId, label(entityId), null))
            }
            finish()
            return
        }
        val service = intent.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE)
        if (service == null) {
            finish()
            return
        }
        showConfirm(entityId, service)
    }

    /**
     * The confirm dialog for one service call — the single shape every action
     * dialog wears (#113): the question on a Homes card, the yes as a full-width
     * row, Abbrechen underneath it. Wording follows the service's own direction
     * (#85, [ConfirmVerb]).
     */
    private fun showConfirm(entityId: String, service: String) {
        val action = ConfirmVerb.of(service)
        val verb = getString(ConfirmWording.verbRes(action))
        ActionDialog.show(
            activity = this,
            title = getString(R.string.widget_confirm_title),
            message = getString(R.string.widget_confirm_msg, label(entityId), verb),
            sheet = ActionSheets.confirm(action),
            onPick = { runService(entityId, service) },
            onCancel = { finish() },
        )
    }

    /**
     * A lock's chooser (#92). **This pick is the confirmation** — the user named
     * the direction in a dialog that carries the device's name, so the call goes
     * out `confirmed = true`. "Tür öffnen" appears only when the lock's card
     * said it has a latch ([EXTRA_FEATURES], read from the card the start screen
     * shows); without it — an app-icon shortcut carries none — [LockChooser]
     * offers the two bolt directions only, the conservative side.
     */
    private fun showLockChooser(entityId: String) {
        val features = intent.getIntExtra(EXTRA_FEATURES, -1).takeIf { it >= 0 }
        val sheet = ActionSheets.lock(features)
        ActionDialog.show(
            activity = this,
            title = getString(R.string.widget_lock_title, label(entityId)),
            message = null,
            sheet = sheet,
            onPick = { i -> sheet.items[i].service?.let { runService(entityId, it) } ?: finish() },
            // Abbrechen does nothing at all — no call.
            onCancel = { finish() },
        )
    }

    /**
     * Run one service for [entityId], then finish. Always `confirmed = true`:
     * every caller here got an explicit yes from the user first.
     */
    private fun runService(entityId: String, service: String) {
        val name = label(entityId)
        thread {
            // The call's own answer decides (#111): a refused "Abschließen" must not end in silence.
            val ok = runCatching { ApiClient(applicationContext).call(entityId, service, confirmed = true) }.getOrDefault(false)
            if (!ok) WidgetActionReceiver.reportFailure(applicationContext, name)
            DeviceTaps.done(applicationContext, entityId)
            runOnUiThread { finish() }
        }
    }

    /** What to call the device in a dialog: the name the caller carried, else the entity id. */
    private fun label(entityId: String): String =
        intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { entityId }

    companion object {
        /** Boolean extra: open the lock chooser instead of a confirm (#92). */
        const val EXTRA_LOCK_CHOOSE = "lock_choose"

        /**
         * Boolean extra: an app-icon shortcut (#97) wants the device's ordinary
         * toggle. Only honoured for a domain [DeviceShortcuts] lets act directly
         * — never for a lock.
         */
        const val EXTRA_SHORTCUT_TOGGLE = "shortcut_toggle"

        /** String extra: the device's name, for wording only — never to decide anything. */
        const val EXTRA_NAME = "device_name"

        /** Int extra: the lock's `supported_features` from its card; -1 when unknown. */
        const val EXTRA_FEATURES = "supported_features"
    }
}
