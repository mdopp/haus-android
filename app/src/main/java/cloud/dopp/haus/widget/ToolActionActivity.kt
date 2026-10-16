package cloud.dopp.haus.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import cloud.dopp.haus.R
import cloud.dopp.haus.data.ApiClient
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * What a tap on a collection row does: a tool widget's row (#70/#90) and an
 * active-devices row (#178).
 *
 * A collection has exactly one `PendingIntentTemplate`, so every row tap arrives
 * here and the row's fill-in says what this particular row can offer. The row has
 * one target and the choice lives in a **sheet**: the pattern the lock chooser
 * (#92) proves on the device.
 *
 * - The row offers one or several actions → [ActionDialog] shows
 *   [ActionSheets.toolActions]; the pick runs one, opens the row's page (one
 *   more entry, after the actions, when it has one), or Abbrechen does nothing.
 * - It offers none but has a page on Haus → that page opens in the browser.
 * - Neither → nothing happens: a tap that may change something must never fire
 *   on a mis-touch, and no address is guessed.
 *
 * The refusals are handled the way Haus spells them out: a `confirm_required`
 * 403 opens the same kind of dialog [WidgetActionActivity] uses and re-sends
 * confirmed; a `forbidden` 403 and an `unknown_action` 404 fail **silently** —
 * there is nothing the person could confirm.
 *
 * Invisible unless it has something to ask: no layout is set. Only the sheets
 * paint anything — [ActionDialog] draws its own scrim (#114).
 */
class ToolActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID)?.trim()?.ifBlank { null }
        // Every action this row can fill (#169); one-long or empty for most tools.
        val ids = intent.getStringArrayExtra(EXTRA_ACTION_IDS)?.toList().orEmpty()
        val bodies = intent.getStringArrayExtra(EXTRA_ACTION_BODIES)?.toList().orEmpty()
        val titles = intent.getStringArrayExtra(EXTRA_ACTION_TITLES)?.toList().orEmpty()
        val page = HausPages.page(intent.getStringExtra(EXTRA_PAGE))
        from = WidgetKind.of(intent.getStringExtra(EXTRA_VON))
        when {
            ids.size > 1 && ids.size == bodies.size -> offer(ids, bodies.map { json(it) }, titles, page)
            actionId != null -> offer(listOf(actionId), listOf(params()), emptyList(), page)
            page != null -> open(page)
            else -> finish()
        }
    }

    private fun offer(ids: List<String>, bodies: List<JSONObject>, titles: List<String>, page: String?) {
        val labels = ids.indices.map { titles.getOrNull(it)?.ifBlank { null } }
        ActionDialog.show(
            activity = this,
            title = rowTitle(),
            message = null,
            sheet = ActionSheets.toolActions(labels, open = page != null),
            onPick = { i -> if (i < ids.size) run(ids[i], bodies[i], confirmed = false) else open(page!!) },
            onCancel = { finish() },
        )
    }

    /** The widget this row is on, for the page's `von` (#180); null when the extra names no kind. */
    private var from: WidgetKind? = null

    /** The row's page on Haus, in the browser. */
    private fun open(page: String) {
        runCatching { startActivity(HausPages.intent(this, page, from)) }
        finish()
    }

    private fun json(s: String): JSONObject = try {
        JSONObject(s)
    } catch (e: Exception) {
        JSONObject()
    }

    private fun rowTitle(): CharSequence =
        intent.getStringExtra(EXTRA_TITLE)?.trim()?.ifBlank { null }
            ?: getString(R.string.tool_widget_label)

    /** The resolved `params` object, or an empty one if it didn't survive the trip. */
    private fun params(): JSONObject = json(intent.getStringExtra(EXTRA_PARAMS).orEmpty().ifBlank { "{}" })

    private fun run(actionId: String, params: JSONObject, confirmed: Boolean) {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        thread {
            val outcome = try {
                ApiClient(applicationContext).postActionCallback(actionId, params, confirmed)
            } catch (e: Exception) {
                ApiClient.ActionOutcome.FAILED
            }
            runOnUiThread {
                if (outcome == ApiClient.ActionOutcome.CONFIRM_REQUIRED && !confirmed) {
                    confirm(actionId, params, appWidgetId)
                    return@runOnUiThread
                }
                // Only a run that actually happened is worth a refetch; a refused
                // one leaves the rows exactly as they are.
                if (outcome == ApiClient.ActionOutcome.OK &&
                    appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                ) {
                    ToolWidgetProvider.requestRefresh(applicationContext, appWidgetId)
                }
                finish()
            }
        }
    }

    /**
     * The server's confirm gate, surfaced. The wording names the row (the widget
     * knows no verb for a catalog action id, and inventing one would claim a
     * direction the def never stated).
     */
    private fun confirm(actionId: String, params: JSONObject, appWidgetId: Int) {
        // The same sheet the device widget uses (#113) — one shape for every
        // action dialog, Abbrechen always the footer.
        ActionDialog.show(
            activity = this,
            title = getString(R.string.widget_confirm_title),
            message = getString(R.string.tool_action_confirm, rowTitle()),
            sheet = ActionSheets.toolConfirm(),
            onPick = { run(actionId, params, confirmed = true) },
            onCancel = { finish() },
        )
    }

    companion object {
        /** The action the tool's def declared for this row. Absent ⇒ none. */
        const val EXTRA_ACTION_ID = "action_id"

        /** [EXTRA_ACTION_ID]'s resolved `params` object, JSON-encoded. */
        const val EXTRA_PARAMS = "params"

        /** The row's title — the wording of both the sheet and the confirm. */
        const val EXTRA_TITLE = "title"

        /** Every fillable action of this row (#169), in declaration order. */
        const val EXTRA_ACTION_IDS = "action_ids"

        /** Their resolved `params` bodies, index-aligned with [EXTRA_ACTION_IDS]. */
        const val EXTRA_ACTION_BODIES = "action_bodies"

        /** Their server titles, index-aligned; an empty entry means "no title". */
        const val EXTRA_ACTION_TITLES = "action_titles"

        /** The row's own page on Haus, a path ([HausPages.page] checks it here again). Absent ⇒ none. */
        const val EXTRA_PAGE = "page"

        /** The kind of widget the row is on ([WidgetKind.kind]), carried by the template (#180). */
        const val EXTRA_VON = "von"

        /**
         * The one `PendingIntentTemplate` a collection's rows complete (#28
         * pattern): this screen, **mutable** so a row's fill-in can merge. It
         * names the widget [from], so a row's page carries `von` (#180).
         */
        fun template(ctx: Context, reqCode: Int, from: WidgetKind): PendingIntent {
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val i = Intent(ctx, ToolActionActivity::class.java).addFlags(ActionDialog.TASK_FLAGS)
                .putExtra(EXTRA_VON, from.kind)
            return PendingIntent.getActivity(ctx, reqCode, i, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
        }
    }
}
