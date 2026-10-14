package cloud.dopp.haus

import android.appwidget.AppWidgetManager
import android.view.LayoutInflater
import android.view.ViewGroup
import cloud.dopp.haus.widget.ActionRow
import cloud.dopp.haus.widget.ActionSheets
import cloud.dopp.haus.widget.ToolActionActivity
import cloud.dopp.haus.widget.ToolCell
import cloud.dopp.haus.widget.ToolOffer
import cloud.dopp.haus.widget.ToolRow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * What a tap on a tool widget row carries and offers (#90, #176).
 *
 * #90 was attempted three times as a *second click target* inside the row, and
 * every attempt lost the press on the device: this launcher delivers one target
 * per collection row. So the row has one target, its actions live in the sheet
 * the tap opens — the lock chooser's pattern (#92) — and a row without an action
 * offers nothing: Haus has no page for an item, and no address is guessed.
 *
 * Asserted here: the fill-in carries the actions and the title and nothing else,
 * the sheet is the *shared* one (#113), and the check mark is **gone from the
 * layout** rather than hidden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolRowTapTest {

    private fun cell(
        actionId: String? = "task.set_status",
        params: String? = """{"entity_id":"task.42","status":"done"}""",
        offers: List<ToolOffer> = emptyList(),
    ) = ToolCell(
        title = "Müll rausbringen",
        subtitle = null,
        meta = null,
        badge = null,
        actionId = actionId,
        actionParams = params,
        offers = offers,
    )

    // --- the fill-in ----------------------------------------------------------

    /** One fill-in carries everything the tap needs — there is no second one. */
    @Test
    fun theRowsFillInCarriesTheActionAndTitle() {
        val i = ToolRow.fillIn(cell(), 7)
        assertEquals("task.set_status", i.getStringExtra(ToolActionActivity.EXTRA_ACTION_ID))
        assertEquals(
            """{"entity_id":"task.42","status":"done"}""",
            i.getStringExtra(ToolActionActivity.EXTRA_PARAMS),
        )
        assertEquals("Müll rausbringen", i.getStringExtra(ToolActionActivity.EXTRA_TITLE))
        assertEquals(7, i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    /** An absent extra is how "this row offers none" travels — not an empty one. */
    @Test
    fun aRowThatOffersNothingCarriesNoAction() {
        val i = ToolRow.fillIn(cell(actionId = null, params = null), 7)
        assertNull(i.getStringExtra(ToolActionActivity.EXTRA_ACTION_ID))
        assertNull(i.getStringArrayExtra(ToolActionActivity.EXTRA_ACTION_IDS))
        val blank = ToolRow.fillIn(cell(actionId = "  ", params = null), 7)
        assertNull(blank.getStringExtra(ToolActionActivity.EXTRA_ACTION_ID))
    }

    /** Several actions travel together, index-aligned (#169). */
    @Test
    fun severalActionsTravelTogether() {
        val offers = listOf(ToolOffer("a1", "{}", "Erledigt"), ToolOffer("a2", "{\"x\":1}", null))
        val i = ToolRow.fillIn(cell(offers = offers), 7)
        assertArrayEquals(arrayOf("a1", "a2"), i.getStringArrayExtra(ToolActionActivity.EXTRA_ACTION_IDS))
        assertArrayEquals(arrayOf("{}", "{\"x\":1}"), i.getStringArrayExtra(ToolActionActivity.EXTRA_ACTION_BODIES))
        assertArrayEquals(arrayOf("Erledigt", ""), i.getStringArrayExtra(ToolActionActivity.EXTRA_ACTION_TITLES))
    }

    // --- the sheet is the shared one (#113) -----------------------------------

    @Test
    fun theSheetListsTheActionsInOrderAndEndsWithCancel() {
        val sheet = ActionSheets.toolActions(listOf("Erledigt", null))
        assertEquals(2, sheet.items.size)
        assertEquals("Erledigt", sheet.items[0].label)
        assertEquals(R.string.tool_action_do, sheet.items[1].labelRes)
        assertEquals(ActionRow.Cancel, sheet.rows.last())
        assertEquals(sheet.rows.lastIndex, sheet.cancelIndex)
        sheet.rows.filterIsInstance<ActionRow.Pick>().forEachIndexed { i, pick -> assertEquals(i, pick.index) }
    }

    /** Nothing a tool declares is treated as dangerous — the server gates that. */
    @Test
    fun theToolSheetCarriesNoDangerousEntry() {
        assertFalse(ActionSheets.toolActions(listOf("Erledigt", null)).hasDangerous)
    }

    // --- the layout: the check mark is gone, not hidden -----------------------

    /**
     * "Removed from the layout, not hidden" is the user's own wording: a hidden
     * view keeps inviting a fifth attempt at making it take the press.
     */
    @Test
    fun theActionButtonIsGoneFromTheLayout() {
        val xml = File(locate("src/main"), "res/layout/item_tool_row.xml").readText()
        assertFalse(
            "the check mark must be removed from item_tool_row.xml, not hidden (#90)",
            xml.contains("@+id/tw_item_action"),
        )
    }

    /** …and with it the second tap target: the row root is the only one left. */
    @Test
    fun theRowHasExactlyOneTapTarget() {
        assertEquals(R.id.tw_item_root, ToolRow.TAP_TARGET)

        val ctx = RuntimeEnvironment.getApplication()
        val row = LayoutInflater.from(ctx).inflate(R.layout.item_tool_row, null)
        assertEquals(R.id.tw_item_root, row.id)
        assertFalse("nothing inside the row claims the press", anyChildClickable(row as ViewGroup))
    }

    private fun anyChildClickable(group: ViewGroup): Boolean {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.isClickable) return true
            if (child is ViewGroup && anyChildClickable(child)) return true
        }
        return false
    }

    /** Unit tests run from the module dir, but don't depend on it. */
    private fun locate(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, rel), File(dir, "app/$rel"))) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $rel from ${File("").absolutePath}")
    }
}
