package cloud.dopp.haus.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import cloud.dopp.haus.R

/**
 * Backs the scrollable ListView of the generic tool widget (#70) — the #28
 * collection pattern, one row per mapped [ToolCell].
 *
 * The factory is deliberately dumb: [ToolWidgetProvider] does the fetching and the
 * schema→cell mapping and persists the result per instance, so a redraw is
 * instant and survives process death. Which slots a row shows is decided entirely
 * by the tool's `tool-cell-schema` — an unused role simply arrives as null here
 * and its view is hidden. The cell also arrives with its action's callback body
 * already resolved (#90), so a row tap assembles its sheet without touching the
 * catalog.
 */
class ToolRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        return ToolCellFactory(applicationContext, appWidgetId)
    }
}

/**
 * A tool row's **one** tap target and what the tap carries (#90).
 *
 * Three attempts put the action on a button *inside* the row and all three lost
 * the press on the device: first attributes (`clickable`, a 48dp target), then
 * structure (no fill-in on the row root, text column and button as siblings with
 * one fill-in each). Android documents several click targets per collection row;
 * this launcher evidently delivers one.
 *
 * So the row carries exactly one fill-in, the check mark is **gone from the
 * layout**, and a tap opens [ActionSheets.toolActions] with the row's actions
 * and its page. A row without an action opens its page on Haus (#178); a row
 * with neither does nothing rather than open a guessed address.
 *
 * Split out of the factory so the assembly is JVM-testable.
 */
object ToolRow {

    /** The row's single tap target — the whole row, nothing nested inside it. */
    val TAP_TARGET = R.id.tw_item_root

    /**
     * The one fill-in that completes the collection's template (#90). It carries
     * everything the tap needs to assemble its sheet without a catalog lookup:
     * the actions the row can fill, its page and the row's title for the wording.
     */
    fun fillIn(cell: ToolCell, appWidgetId: Int): Intent {
        val i = Intent()
            .putExtra(ToolActionActivity.EXTRA_TITLE, cell.title)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        cell.page?.let { i.putExtra(ToolActionActivity.EXTRA_PAGE, it) }
        if (cell.offers.size > 1) {
            // Several fillable actions (#169): the sheet offers each under its own
            // title. The single-action extras stay filled too.
            i.putExtra(ToolActionActivity.EXTRA_ACTION_IDS, cell.offers.map { it.id }.toTypedArray())
            i.putExtra(ToolActionActivity.EXTRA_ACTION_BODIES, cell.offers.map { it.params }.toTypedArray())
            i.putExtra(
                ToolActionActivity.EXTRA_ACTION_TITLES,
                cell.offers.map { it.title.orEmpty() }.toTypedArray(),
            )
        }
        if (!cell.actionId.isNullOrBlank()) {
            i.putExtra(ToolActionActivity.EXTRA_ACTION_ID, cell.actionId)
            i.putExtra(ToolActionActivity.EXTRA_PARAMS, cell.actionParams.orEmpty())
        }
        return i
    }
}

private class ToolCellFactory(
    private val ctx: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var cells: List<ToolCell> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        cells = ToolCells.decode(WidgetStore.toolCellsJson(ctx, appWidgetId))
    }

    override fun onDestroy() {
        cells = emptyList()
    }

    override fun getCount(): Int = cells.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = RemoteViews(ctx.packageName, R.layout.item_tool_row)
        val cell = cells.getOrNull(position)
            ?: return row.also { it.setViewVisibility(R.id.tw_item_root, View.GONE) }

        row.setTextViewText(R.id.tw_item_title, cell.title)

        // subtitle + meta share the one muted detail line; both optional roles.
        val detail = listOfNotNull(cell.subtitle, cell.meta).joinToString(ToolCells.META_SEP)
        if (detail.isBlank()) {
            row.setViewVisibility(R.id.tw_item_meta, View.GONE)
        } else {
            row.setViewVisibility(R.id.tw_item_meta, View.VISIBLE)
            row.setTextViewText(R.id.tw_item_meta, detail)
        }

        if (cell.badge.isNullOrBlank()) {
            row.setViewVisibility(R.id.tw_item_badge, View.GONE)
        } else {
            row.setViewVisibility(R.id.tw_item_badge, View.VISIBLE)
            row.setTextViewText(R.id.tw_item_badge, cell.badge)
        }

        // One tap target for the whole row (#90). What the tap then offers is
        // decided in ToolActionActivity off these extras, because a collection has
        // exactly one PendingIntentTemplate and this launcher delivers one target
        // per row.
        row.setOnClickFillInIntent(ToolRow.TAP_TARGET, ToolRow.fillIn(cell, appWidgetId))
        return row
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}
