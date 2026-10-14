package cloud.dopp.haus

import cloud.dopp.haus.data.ToolDefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tool catalog contract (#70/#72, Haus's `internal/napi/tools.go`).
 * Everything here is pure JSON→model work, so the whole discovery path is
 * locked on the JVM: the `/napi/` paths Haus declares, the closed cell-role
 * vocabulary, which tools may be offered as a list widget, and how a malformed
 * def degrades.
 *
 * The fixture is the catalog Haus serves with every Haushalt backend set
 * (`GET /napi/defs/tool`, TestToolCatalog there), plus two defs a list cannot
 * show: one without rows, one without a title — if Haus ships a new shape,
 * this test is where it must show up first.
 *
 * Robolectric only for `org.json` (the JVM stub throws).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolDefsTest {

    /** The catalog as Haus's `GET /napi/defs/tool` serves it. */
    private val shippedCatalog = """
        {"ok": true, "kind": "tool", "defs": [
          {"tool-id":"contacts","tool-label":"Kontakte","tool-api-path":"/napi/portal/contacts",
           "tool-cell-schema":{"title":"name","meta":["phone","email","birthday"]},
           "tool-actions":[],"tool-actions-titled":[],"tool-action-params":{}},
          {"tool-id":"doc","tool-label":"Dokumente","tool-api-path":"/napi/portal/documents",
           "tool-cell-schema":{"title":"title","meta":["created","correspondent"]},
           "tool-actions":[],"tool-actions-titled":[],"tool-action-params":{}},
          {"tool-id":"note","tool-label":"Notizen","tool-api-path":"/napi/portal/notes",
           "tool-cell-schema":{"title":"title","meta":["changed"]},
           "tool-actions":[],"tool-actions-titled":[],"tool-action-params":{}},
          {"tool-id":"task","tool-label":"Erinnerungen","tool-api-path":"/napi/portal/tasks",
           "tool-cell-schema":{"title":"title","meta":["due"],"actions":["task.set_status"]},
           "tool-actions":["task.set_status"],
           "tool-actions-titled":[{"id":"task.set_status","title":"Erledigt"}],
           "tool-action-params":{"task.set_status":{"entity_id":"${'$'}id","status":"done"}}},
          {"tool-id":"rowless","tool-label":"Ohne Zeilen","tool-api-path":"",
           "tool-cell-schema":{"title":"title"},"tool-actions":[]},
          {"tool-id":"card","tool-label":"Karte","tool-api-path":"/napi/portal/energy",
           "tool-cell-schema":{},"tool-actions":[]}
        ]}
    """.trimIndent()

    @Test
    fun parsesEveryShippedDef() {
        val defs = ToolDefs.parseCatalog(shippedCatalog)
        assertEquals(listOf("contacts", "doc", "note", "task", "rowless", "card"), defs.map { it.id })
        assertEquals("Kontakte", defs.first().label)
        assertEquals("/napi/portal/tasks", defs.single { it.id == "task" }.apiPath)
    }

    /** Haus names its rows under `/napi/`; such a path is taken as it is, query and all. */
    @Test
    fun aNapiPathIsTakenAsDeclared() {
        assertEquals("/napi/portal/tasks", ToolDefs.napiPath("/napi/portal/tasks"))
        assertEquals("/napi/portal/tasks?done=1", ToolDefs.napiPath("  /napi/portal/tasks?done=1 "))
    }

    /** Only a path under `/napi/` is ever sent the device token. */
    @Test
    fun missingOrForeignPathsYieldNull() {
        assertNull(ToolDefs.napiPath(null))
        assertNull(ToolDefs.napiPath(""))
        assertNull(ToolDefs.napiPath("   "))
        assertNull(ToolDefs.napiPath("/napi/"))
        assertNull("not under /napi/", ToolDefs.napiPath("/api/portal/tasks"))
        assertNull("relative", ToolDefs.napiPath("portal/energy"))
        assertNull("bare", ToolDefs.napiPath("/portal/energy"))
        assertNull("a way out", ToolDefs.napiPath("/napi/../pair-device"))
        // An absolute URL is not ours — never send the device token there.
        assertNull(ToolDefs.napiPath("https://example.com/napi/portal/tasks"))
    }

    @Test
    fun everyShippedSchemaMapsToItsRoles() {
        val defs = ToolDefs.parseCatalog(shippedCatalog).associateBy { it.id }
        assertEquals("name", defs.getValue("contacts").schema.title)
        assertEquals(listOf("phone", "email", "birthday"), defs.getValue("contacts").schema.meta)
        assertEquals("title", defs.getValue("doc").schema.title)
        assertEquals(listOf("created", "correspondent"), defs.getValue("doc").schema.meta)
        assertEquals("title", defs.getValue("note").schema.title)
        assertEquals(listOf("changed"), defs.getValue("note").schema.meta)
        assertEquals("title", defs.getValue("task").schema.title)
        assertEquals(listOf("due"), defs.getValue("task").schema.meta)
        assertEquals(listOf("task.set_status"), defs.getValue("task").schema.actions)
        assertTrue(defs.getValue("card").schema.isEmpty)
    }

    /**
     * Which tools the picker may offer: one without rows (nothing to fetch) and
     * one with an empty schema (no title) would pin a permanently blank widget.
     */
    @Test
    fun onlyListableToolsAreOfferable() {
        val defs = ToolDefs.parseCatalog(shippedCatalog)
        assertEquals(listOf("contacts", "doc", "note", "task"), defs.filter { it.isListable }.map { it.id })
        val byId = defs.associateBy { it.id }
        assertFalse("no tool-api-path", byId.getValue("rowless").isListable)
        assertFalse("empty schema", byId.getValue("card").isListable)
    }

    @Test
    fun allRolesOfTheVocabularyAreRead() {
        val body = """
            {"ok":true,"defs":[{"tool-id":"x","tool-label":"X","tool-api-path":"/napi/x",
             "tool-actions":["x.do","x.other"],
             "tool-cell-schema":{"title":"name","subtitle":"role","meta":["phone","email"],
               "badge":"state","icon":"glyph","actions":["x.do"]}}]}
        """.trimIndent()
        val s = ToolDefs.parseCatalog(body).single().schema
        assertEquals("name", s.title)
        assertEquals("role", s.subtitle)
        assertEquals(listOf("phone", "email"), s.meta)
        assertEquals("state", s.badge)
        assertEquals("glyph", s.icon)
        assertEquals(listOf("x.do"), s.actions)
    }

    /** `state` is the accepted alias for the `badge` chip role. */
    @Test
    fun stateIsAnAliasForBadge() {
        val body = """{"defs":[{"tool-id":"x","tool-cell-schema":{"title":"t","state":"status"}}]}"""
        assertEquals("status", ToolDefs.parseCatalog(body).single().schema.badge)
    }

    /**
     * Anything outside the closed vocabulary — an unknown role, a role whose value
     * is markup-ish or the wrong JSON type — is skipped, never guessed at.
     */
    @Test
    fun unknownRolesAndWrongTypesDegrade() {
        val body = """
            {"defs":[{"tool-id":"x","tool-label":"X","tool-api-path":"/napi/x",
             "tool-cell-schema":{"title":"name","template":"<b>{{name}}</b>",
               "onClick":"doThing()","meta":{"nope":1},"subtitle":42,"badge":{"f":"x"}}}]}
        """.trimIndent()
        val def = ToolDefs.parseCatalog(body).single()
        assertEquals("name", def.schema.title)
        assertNull(def.schema.subtitle)
        assertNull(def.schema.badge)
        assertTrue(def.schema.meta.isEmpty())
        // A title survives → the tool is still usable as a list.
        assertTrue(def.isListable)
    }

    /** A schema may only reference action ids the def actually declares. */
    @Test
    fun undeclaredActionIdsAreDropped() {
        val body = """
            {"defs":[{"tool-id":"x","tool-actions":["x.do"],
             "tool-cell-schema":{"title":"t","actions":["x.do","x.ghost"]}}]}
        """.trimIndent()
        assertEquals(listOf("x.do"), ToolDefs.parseCatalog(body).single().schema.actions)
    }

    @Test
    fun malformedCatalogNeverThrows() {
        assertTrue(ToolDefs.parseCatalog(null).isEmpty())
        assertTrue(ToolDefs.parseCatalog("").isEmpty())
        assertTrue(ToolDefs.parseCatalog("not json").isEmpty())
        assertTrue(ToolDefs.parseCatalog("""{"ok":false}""").isEmpty())
        // A def without a tool-id is skipped; the rest of the catalog survives.
        val mixed = """{"defs":[{"tool-label":"kaputt"},{"tool-id":"ok","tool-label":"OK"}]}"""
        assertEquals(listOf("ok"), ToolDefs.parseCatalog(mixed).map { it.id })
    }

    // --- tool-action-params (#90) ----------------------------

    /** Haus's reminder declaration — the only tool that ships one today. */
    @Test
    fun taskActionParamsAreReadPerActionId() {
        val byId = ToolDefs.parseCatalog(shippedCatalog).associateBy { it.id }
        val task = byId.getValue("task").actionParams
        assertEquals(setOf("task.set_status"), task.keys)
        assertEquals(
            mapOf("entity_id" to "\$id", "status" to "done"),
            task["task.set_status"],
        )
        assertEquals(mapOf("task.set_status" to "Erledigt"), byId.getValue("task").actionTitles)
        // Every other shipped tool declares none — so no row of theirs gets a button.
        listOf("contacts", "doc", "note", "rowless", "card").forEach {
            assertTrue(it, byId.getValue(it).actionParams.isEmpty())
        }
    }

    /**
     * A source must be a non-empty string; a bare `$` names no field (the server
     * lints exactly that). Such an action is dropped whole rather than sent with a
     * hole in its params.
     */
    @Test
    fun unusableActionParamMappingsAreDropped() {
        val body = """
            {"defs":[{"tool-id":"x","tool-actions":["a","b","c","d"],
             "tool-action-params":{"a":{"p":"${'$'}"},"b":{"p":42},"c":{},
               "d":{"p":"${'$'}field","q":"lit"}}}]}
        """.trimIndent()
        val params = ToolDefs.parseCatalog(body).single().actionParams
        assertEquals(setOf("d"), params.keys)
        assertEquals(mapOf("p" to "\$field", "q" to "lit"), params["d"])
    }

    @Test
    fun missingOrMalformedActionParamsYieldAnEmptyMap() {
        assertTrue(ToolDefs.parseActionParams(null).isEmpty())
        val body = """{"defs":[{"tool-id":"x","tool-action-params":"nope"}]}"""
        assertTrue(ToolDefs.parseCatalog(body).single().actionParams.isEmpty())
    }

    // --- item payloads --------------------------------------------------------

    @Test
    fun rowsAreFoundUnderTheHandlerSpecificKey() {
        assertEquals(2, ToolDefs.rows("""{"ok":true,"tasks":[{"a":1},{"a":2}]}""").size)
        assertEquals(1, ToolDefs.rows("""{"ok":true,"contacts":[{"a":1}]}""").size)
        assertEquals(1, ToolDefs.rows("""{"ok":true,"documents":[{"a":1}]}""").size)
        // A brand-new tool with a key we've never seen still renders.
        assertEquals(3, ToolDefs.rows("""{"ok":true,"widgets":[{},{},{}]}""").size)
        // …and a bare array works too.
        assertEquals(2, ToolDefs.rows("""[{"a":1},{"a":2}]""").size)
    }

    @Test
    fun rowsAreBoundedAndFailSoft() {
        val many = (1..100).joinToString(",", "[", "]") { """{"i":$it}""" }
        assertEquals(ToolDefs.MAX_ROWS, ToolDefs.rows(many).size)
        assertEquals(5, ToolDefs.rows(many, cap = 5).size)
        assertTrue(ToolDefs.rows(null).isEmpty())
        assertTrue(ToolDefs.rows("nope").isEmpty())
        assertTrue(ToolDefs.rows("""{"ok":true}""").isEmpty())
    }

    /** The `/napi/action-callback` wire shape (Haus, `internal/napi/tools.go`). */
    @Test
    fun actionBodyCarriesActionIdAndParams() {
        val params = org.json.JSONObject().put("entity_id", "task.42").put("status", "done")
        val body = ToolDefs.actionBody("task.set_status", params)
        assertEquals("task.set_status", body.getString("action_id"))
        assertEquals("task.42", body.getJSONObject("params").getString("entity_id"))
        assertFalse(body.has("confirmed"))
        assertTrue(ToolDefs.actionBody("x.do", null, confirmed = true).getBoolean("confirmed"))
    }
}
