package cloud.dopp.haus

import cloud.dopp.haus.data.ToolCellSchema
import cloud.dopp.haus.data.ToolDefs
import cloud.dopp.haus.widget.ToolCell
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
 * The schema→cell mapper (#70) — the piece that makes the tool widget generic.
 * Every real shipped schema must map its items correctly with **no per-tool code**,
 * and a plugin that ships something unexpected must lose a line, not crash the
 * home screen.
 *
 * Robolectric only for `org.json`; the mapper itself is Android-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolCellsTest {

    private fun schema(json: String): ToolCellSchema = ToolDefs.parseSchema(JSONObject(json))

    private fun map(
        schemaJson: String,
        rowJson: String,
    ): ToolCell? = ToolCells.map(schema(schemaJson), JSONObject(rowJson))

    // --- the six real shipped schemas ----------------------------------------

    @Test
    fun taskSchemaMapsTitleAndDue() {
        val cell = map(
            """{"title":"title","meta":["due"]}""",
            """{"entity_id":"task.42","title":"Müll rausbringen","due":"2026-08-25"}""",
        )!!
        assertEquals("Müll rausbringen", cell.title)
        assertEquals("2026-08-25", cell.meta)
        assertNull(cell.subtitle)
        assertNull(cell.badge)
    }

    @Test
    fun contactsSchemaJoinsBothMetaFields() {
        val cell = map(
            """{"title":"name","meta":["phone","email"]}""",
            """{"id":"p7","name":"Anna","phone":"0170 1234","email":"anna@example.org"}""",
        )!!
        assertEquals("Anna", cell.title)
        assertEquals("0170 1234${ToolCells.META_SEP}anna@example.org", cell.meta)
    }

    @Test
    fun docSchemaMapsTitleAndCategory() {
        val cell = map(
            """{"title":"title","meta":["category"]}""",
            """{"title":"Stromvertrag","category":"Verträge"}""",
        )!!
        assertEquals("Stromvertrag", cell.title)
        assertEquals("Verträge", cell.meta)
    }

    /** A photo's `people` is a list field — bounded and comma-joined. */
    @Test
    fun photoSchemaFlattensTheListField() {
        val cell = map(
            """{"title":"name","meta":["people"]}""",
            """{"name":"Strand.jpg","people":["Anna","Ben","Cem"]}""",
        )!!
        assertEquals("Strand.jpg", cell.title)
        assertEquals("Anna, Ben, Cem", cell.meta)
    }

    @Test
    fun noteSchemaIsTitleOnly() {
        val cell = map("""{"title":"label"}""", """{"label":"Einkaufen"}""")!!
        assertEquals("Einkaufen", cell.title)
        assertNull(cell.meta)
    }

    /** An empty schema can never produce a row — that's why such tools aren't offered. */
    @Test
    fun emptySchemaYieldsNoRow() {
        assertNull(map("{}", """{"label":"egal","state":"on"}"""))
        assertTrue(ToolCells.mapAll(
            cloud.dopp.haus.data.ToolDef(
                id = "energy", label = "Energie", apiPath = "/napi/portal/energy",
                actions = emptyList(), schema = ToolCellSchema.EMPTY,
            ),
            listOf(JSONObject("""{"label":"x"}""")),
        ).isEmpty())
    }

    // --- the richer roles -----------------------------------------------------

    @Test
    fun subtitleBadgeAndIconRolesAreMapped() {
        val cell = map(
            """{"title":"name","subtitle":"role","meta":["phone"],"badge":"state"}""",
            """{"name":"Anna","role":"Mieterin","phone":"0170","state":"aktiv"}""",
        )!!
        assertEquals("Anna", cell.title)
        assertEquals("Mieterin", cell.subtitle)
        assertEquals("0170", cell.meta)
        assertEquals("aktiv", cell.badge)
    }

    @Test
    fun declaredActionsRideAlongOnTheCell() {
        val cell = ToolCells.map(
            schema("""{"title":"title","actions":["task.set_status"]}"""),
            JSONObject("""{"title":"Müll"}"""),
            declaredActions = listOf("task.set_status", "task.add"),
        )!!
        assertEquals(listOf("task.set_status"), cell.actions)
    }

    // --- degradation ----------------------------------------------------------

    @Test
    fun missingTitleFieldDropsTheRow() {
        assertNull(map("""{"title":"title","meta":["due"]}""", """{"due":"morgen"}"""))
        assertNull(map("""{"title":"title"}""", """{"title":"   "}"""))
        assertNull(map("""{"title":"title"}""", """{"title":null}"""))
    }

    @Test
    fun missingMetaFieldsAreSkippedNotBlank() {
        val cell = map(
            """{"title":"name","meta":["phone","email"]}""",
            """{"name":"Ben","email":"ben@example.org"}""",
        )!!
        assertEquals("ben@example.org", cell.meta)
    }

    @Test
    fun fieldTypesOutsideTheClosedSetAreSkipped() {
        // A nested object has no sensible one-line rendering — leave the slot out
        // rather than dumping JSON into the widget.
        val cell = map(
            """{"title":"name","meta":["nested"],"badge":"flag"}""",
            """{"name":"X","nested":{"a":1},"flag":true}""",
        )!!
        assertNull(cell.meta)
        assertEquals("ja", cell.badge)
    }

    @Test
    fun numbersRenderWithoutTrailingZero() {
        val cell = map(
            """{"title":"name","meta":["count","ratio"]}""",
            """{"name":"X","count":3.0,"ratio":1.5}""",
        )!!
        assertEquals("3${ToolCells.META_SEP}1.5", cell.meta)
    }

    @Test
    fun metaLineIsBounded() {
        val cell = map(
            """{"title":"n","meta":["a","b","c","d","e"]}""",
            """{"n":"X","a":"1","b":"2","c":"3","d":"4","e":"5"}""",
        )!!
        assertEquals(ToolCells.META_MAX, cell.meta!!.split(ToolCells.META_SEP).size)
    }

    @Test
    fun mapAllDropsUnusableRowsAndKeepsTheRest() {
        val def = cloud.dopp.haus.data.ToolDef(
            id = "task", label = "Aufgabe", apiPath = "/napi/portal/tasks",
            actions = emptyList(),
            schema = schema("""{"title":"title","meta":["due"]}"""),
        )
        val rows = ToolDefs.rows(
            """{"ok":true,"tasks":[{"title":"A","due":"heute"},{"due":"morgen"},{"title":"B"}]}""",
        )
        assertEquals(listOf("A", "B"), ToolCells.mapAll(def, rows).map { it.title })
    }

    // --- row actions (#90, tool-action-params) --------------------------------

    /** The real `.task` declaration: `$id` reads the row, `"done"` is a literal. */
    private val taskParams = mapOf(
        "task.set_status" to mapOf("entity_id" to "\$id", "status" to "done"),
    )

    @Test
    fun taskRowResolvesItsCallbackBodyFromTheCatalog() {
        val cell = ToolCells.map(
            schema("""{"title":"title","meta":["due"],"actions":["task.set_status"]}"""),
            JSONObject("""{"id":"t42","title":"Müll rausbringen","due":"2026-08-25"}"""),
            declaredActions = listOf("task.set_status", "task.add"),
            actionParams = taskParams,
        )!!
        assertEquals("task.set_status", cell.actionId)
        val params = JSONObject(cell.actionParams!!)
        assertEquals("t42", params.getString("entity_id"))
        assertEquals("done", params.getString("status"))
    }

    /** A row missing a `$`-referenced field gets no button — never a half body. */
    @Test
    fun rowWithoutTheReferencedFieldGetsNoButton() {
        val cell = ToolCells.map(
            schema("""{"title":"title","actions":["task.set_status"]}"""),
            JSONObject("""{"title":"Müll"}"""),
            declaredActions = listOf("task.set_status"),
            actionParams = taskParams,
        )!!
        assertNull(cell.actionId)
        assertNull(cell.actionParams)
    }

    /** Today's other tools declare no params at all — they behave as before. */
    @Test
    fun toolWithoutDeclaredParamsGetsNoButton() {
        val cell = ToolCells.map(
            schema("""{"title":"name","actions":["contact.add"]}"""),
            JSONObject("""{"id":"p7","name":"Anna"}"""),
            declaredActions = listOf("contact.add"),
        )!!
        assertEquals(listOf("contact.add"), cell.actions)
        assertNull(cell.actionId)
    }

    /** A field value keeps its raw JSON type — the server wants the id, not "ja". */
    @Test
    fun fieldValuesAreResolvedRawNotAsDisplayText() {
        val params = ToolCells.resolveParams(
            mapOf("n" to "\$count", "flag" to "\$done", "lit" to "done"),
            JSONObject("""{"count":7,"done":true}"""),
        )!!
        assertEquals(7, params.getInt("n"))
        assertTrue(params.getBoolean("flag"))
        assertEquals("done", params.getString("lit"))
    }

    @Test
    fun resolveParamsRefusesEmptyAndUnusableSources() {
        val row = JSONObject("""{"id":"x","nested":{"a":1}}""")
        assertNull(ToolCells.resolveParams(emptyMap(), row))
        assertNull(ToolCells.resolveParams(mapOf("p" to "\$"), row))
        assertNull(ToolCells.resolveParams(mapOf("p" to "\$missing"), row))
        assertNull(ToolCells.resolveParams(mapOf("p" to "\$nested"), row))
    }

    /** An id the schema doesn't name (or the def doesn't declare) is never offered. */
    @Test
    fun onlySchemaDeclaredActionsBecomeTheButton() {
        assertTrue(ToolCells.resolveOffers(emptyList(), JSONObject("""{"id":"t"}"""), taskParams).isEmpty())
        assertEquals(
            listOf("task.set_status"),
            ToolCells.resolveOffers(
                listOf("task.add", "task.set_status"),
                JSONObject("""{"id":"t"}"""),
                taskParams,
            ).map { it.id },
        )
    }

    // --- the declared item id field (#107) ------------------------------------

    // --- cache codec ----------------------------------------------------------

    @Test
    fun cellsSurviveTheCacheRoundTrip() {
        val cells = listOf(
            ToolCell(title = "A", subtitle = "s", meta = "m", badge = "b"),
            ToolCell(title = "B", subtitle = null, meta = null, badge = null),
        )
        val back = ToolCells.decode(ToolCells.encode(cells))
        assertEquals(2, back.size)
        assertEquals(cells[0].copy(actions = emptyList()), back[0])
        assertEquals("B", back[1].title)
        assertNull(back[1].meta)
    }

    /** The button must survive a cold redraw — the tap does no catalog lookup. */
    @Test
    fun theResolvedActionSurvivesTheCacheRoundTrip() {
        val cells = listOf(
            ToolCell(
                title = "Müll", subtitle = null, meta = "morgen", badge = null,
                actionId = "task.set_status",
                actionParams = """{"entity_id":"t42","status":"done"}""",
            ),
            ToolCell(title = "Anna", subtitle = null, meta = null, badge = null),
        )
        val back = ToolCells.decode(ToolCells.encode(cells))
        assertEquals("task.set_status", back[0].actionId)
        assertEquals("t42", JSONObject(back[0].actionParams!!).getString("entity_id"))
        // A row without an action stays without one — no empty placeholder button.
        assertNull(back[1].actionId)
        assertNull(back[1].actionParams)
    }

    @Test
    fun malformedCacheDecodesToEmpty() {
        assertTrue(ToolCells.decode(null).isEmpty())
        assertTrue(ToolCells.decode("").isEmpty())
        assertTrue(ToolCells.decode("{oops").isEmpty())
    }
}
