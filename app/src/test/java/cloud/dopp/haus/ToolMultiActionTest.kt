package cloud.dopp.haus

import cloud.dopp.haus.data.ToolDefs
import cloud.dopp.haus.widget.ActionSheets
import cloud.dopp.haus.widget.ToolCells
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tool row that offers several actions (#169, the old server's ADR 0014).
 *
 * Until now exactly one was reachable: with `$id` on every row, every row could
 * fill every action, so all of them resolved to whichever id stood first. Five
 * lease durations would have become five buttons that all leased one hour — the
 * trap caught in the old server before it shipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolMultiActionTest {

    private val params = mapOf(
        "model.lease.1h" to mapOf("profile" to "\$id", "hours" to "1"),
        "model.lease.4h" to mapOf("profile" to "\$id", "hours" to "4"),
        "model.release" to mapOf("profile" to "\$id", "hours" to "0"),
    )
    private val titles = mapOf(
        "model.lease.1h" to "1 Stunde",
        "model.lease.4h" to "4 Stunden",
        "model.release" to null,
    )
    private val declared = listOf("model.lease.1h", "model.lease.4h", "model.release")
    private fun row() = JSONObject().put("id", "foundry").put("title", "Foundry")

    /** The regression: all three must survive, not only the first. */
    @Test
    fun `every fillable action is offered`() {
        val offers = ToolCells.resolveOffers(declared, row(), params, titles)
        assertEquals(listOf("model.lease.1h", "model.lease.4h", "model.release"), offers.map { it.id })
    }

    /** Declaration order is load-bearing: an older app runs the FIRST entry. */
    @Test
    fun `declaration order is kept`() {
        val offers = ToolCells.resolveOffers(declared.reversed(), row(), params, titles)
        assertEquals("model.release", offers.first().id)
    }

    @Test
    fun `titles ride along, and a missing one stays null`() {
        val offers = ToolCells.resolveOffers(declared, row(), params, titles)
        assertEquals("1 Stunde", offers[0].title)
        assertEquals("4 Stunden", offers[1].title)
        assertNull("no title means fall back to the fixed label", offers[2].title)
    }

    /** An action the row cannot fill is left out rather than sent half-empty. */
    @Test
    fun `an unfillable action is skipped`() {
        val withExtra = params + ("model.pin" to mapOf("slot" to "\$missing"))
        val offers = ToolCells.resolveOffers(declared + "model.pin", row(), withExtra, titles)
        assertTrue("model.pin" !in offers.map { it.id })
    }

    /**
     * The offers must survive the cell cache — a cold redraw after a reboot is the
     * widget's normal state, and losing them there would restore the very bug.
     */
    @Test
    fun `offers survive encode and decode`() {
        val cell = ToolCells.map(
            schema = ToolDefs.parseDef(
                JSONObject()
                    .put("tool-id", "model")
                    .put("tool-actions", org.json.JSONArray(declared))
                    .put("tool-cell-schema", JSONObject().put("title", "title").put("actions", org.json.JSONArray(declared))),
            )!!.schema,
            row = row(),
            declaredActions = declared,
            actionParams = params,
            actionTitles = titles,
        )!!
        val back = ToolCells.decode(ToolCells.encode(listOf(cell))).single()
        assertEquals(cell.offers.map { it.id }, back.offers.map { it.id })
        assertEquals(cell.offers.map { it.title }, back.offers.map { it.title })
        assertEquals(cell.offers.map { it.params }, back.offers.map { it.params })
    }

    /** The sheet labels each entry, falls back where the server sent none. */
    @Test
    fun `the sheet uses the server titles`() {
        val sheet = ActionSheets.toolActions(listOf("1 Stunde", "4 Stunden", null))
        assertEquals(listOf("1 Stunde", "4 Stunden", null), sheet.items.map { it.label })
        assertEquals("three actions plus a cancel footer", 4, sheet.rows.size)
    }
}
