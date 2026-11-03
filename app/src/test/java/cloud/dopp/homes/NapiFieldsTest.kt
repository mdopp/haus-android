package cloud.dopp.homes

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.ApiClient
import cloud.dopp.homes.data.Napi
import cloud.dopp.homes.data.Assistent
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.ToolDefs
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.widget.ToolActionActivity
import cloud.dopp.homes.widget.ToolCells
import cloud.dopp.homes.widget.ToolRow
import cloud.dopp.homes.widget.WidgetCache
import cloud.dopp.homes.widget.WidgetStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The `/napi` fields Homes added for the app (#178, mdopp/homes#30):
 *
 * - no SVG library, no SVG code (the app draws its own icon only);
 * - every card and every tool row with an id names its own page on Homes
 *   (`page`): a start-screen device and a tool row open base + page, a
 *   card without one opens `/start`, and only a path on Homes is ever opened;
 * - the watch names entities only: no active widget asks for `active_set`
 *   any more (owner, 2026-11-02);
 *
 * Written before the code (red first): new names are reached through [Reach].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NapiFieldsTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private val pages = "cloud.dopp.homes.widget.HomesPages"

    @Before
    fun paired() {
        ServerStore.setBaseUrl(ctx(), "https://homes.example")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_T1")
    }

    @After
    fun unpair() {
        ServerStore.clear(ctx())
        Assistent.clear(ctx())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun extraPage(): String = ToolActionActivity::class.java.getField("EXTRA_PAGE").get(null) as String

    /** No SVG is left: not the library, not a path, not a parser. */
    @Test
    fun noSvgIsLeft() {
        val gradle = File(RepoFiles.root, "app/build.gradle").readText()
        assertFalse("androidsvg is still a dependency", gradle.contains("androidsvg"))
        val svg = RepoFiles.mainKotlin().filter { f ->
            f.readLines().any { NapiContractTest.code(it).contains("svg", ignoreCase = true) }
        }.map { RepoFiles.relative(it) }
        assertTrue("SVG in the code: $svg", svg.isEmpty())
    }

    // --- pages: only a path on Homes --------------------------------------------

    @Test
    fun aPageIsAPathOnHomesAndNothingElse() {
        listOf(
            "/raeume/light.kueche", "/kalender/erinnerung/abc-123", "/kontakte/u%201",
            "/dokumente/42", "/notizen/einkauf/liste.md", "/notizen/Notiz:%20Mai.md",
        ).forEach { assertEquals(it, Reach.call(pages, "page", it)) }
        assertEquals("trimmed", "/raeume/light.k", Reach.call(pages, "page", " /raeume/light.k "))
        listOf(
            null, "", " ", "raeume/light.k", "https://evil.example/x", "http://homes.example/raeume/x",
            "//evil.example/x", "/\\evil.example", "/raeume/../box", "/raeume/a b", "/raeume/a\tb",
            "/raeume/a\nb", "javascript:alert(1)", "\u0000/raeume",
        ).forEach { assertNull("taken: $it", Reach.call(pages, "page", it)) }
    }

    @Test
    fun anItemWithoutAPageOpensStart() {
        assertEquals("/raeume/light.k", Reach.call(pages, "item", "/raeume/light.k"))
        assertEquals("/start", Reach.call(pages, "item", null))
        assertEquals("/start", Reach.call(pages, "item", "https://evil.example/x"))
    }

    // --- the card's page ---------------------------------------------------------

    private fun card(page: String?) = ApiClient.parseCardJson(
        JSONObject("""{"entity_id":"light.k","name":"Küche","domain":"light","state":"on"}""")
            .apply { page?.let { put("page", it) } },
    )

    @Test
    fun aCardCarriesItsPage() {
        assertEquals("/raeume/light.k", Reach.get(card("/raeume/light.k"), "page"))
        assertNull(Reach.get(card(null), "page"))
    }

    // --- the row-tap screen ------------------------------------------------------

    private fun tap(extras: Intent.() -> Unit): Activity {
        val i = Intent(ctx(), ToolActionActivity::class.java).apply(extras)
        return Robolectric.buildActivity(ToolActionActivity::class.java, i).setup().get()
    }

    @Test
    fun aRowWithAPageAndNoActionOpensThePage() {
        val a = tap { putExtra(extraPage(), "/kontakte/u1") }
        val opened = shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened?.action)
        assertEquals("https://homes.example/kontakte/u1", opened?.dataString)
        assertTrue(a.isFinishing)
    }

    /** A row with an action and a page: its sheet ends with "Öffnen", which opens the page. */
    @Test
    fun aRowWithAnActionOpensItsPageFromTheSheet() {
        val a = tap {
            putExtra(ToolActionActivity.EXTRA_ACTION_ID, "task.set_status")
            putExtra(ToolActionActivity.EXTRA_PARAMS, """{"entity_id":"abc","status":"done"}""")
            putExtra(extraPage(), "/kalender/erinnerung/abc")
        }
        idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull("no sheet", dialog)
        val items = dialog.findViewById<android.view.ViewGroup>(R.id.ad_items)
        val labels = (0 until items.childCount).map { items.getChildAt(it).findViewById<TextView>(R.id.ar_label)?.text?.toString() }
        assertEquals("the action first, the page after it", listOf(ctx().getString(R.string.tool_action_do), ctx().getString(R.string.tool_open)), labels)
        items.getChildAt(1).performClick()
        idle()
        assertEquals("https://homes.example/kalender/erinnerung/abc", shadowOf(a).nextStartedActivity?.dataString)
    }

    @Test
    fun aForeignPageIsNeverOpened() {
        val a = tap { putExtra(extraPage(), "https://evil.example/x") }
        assertNull(shadowOf(a).nextStartedActivity)
        assertTrue(a.isFinishing)
    }

    /** A row with actions keeps its sheet; its page is one more entry, after the actions. */
    @Test
    fun aRowWithActionsOffersItsPageAfterThem() {
        val sheets = "cloud.dopp.homes.widget.ActionSheets"
        val open = ctx().resources.getIdentifier("tool_open", "string", ctx().packageName)
        assertTrue("no string tool_open", open != 0)
        val withPage = Reach.call(sheets, "toolActions", listOf("Erledigt"), true)!!
        @Suppress("UNCHECKED_CAST")
        val items = Reach.get(withPage, "items") as List<Any>
        assertEquals(2, items.size)
        assertEquals("Erledigt", Reach.get(items[0], "label"))
        assertEquals(open, Reach.get(items[1], "labelRes"))
        @Suppress("UNCHECKED_CAST")
        val without = Reach.get(Reach.call(sheets, "toolActions", listOf("Erledigt"), false)!!, "items") as List<Any>
        assertEquals(1, without.size)
    }

    // --- the tool row's page -----------------------------------------------------

    private val taskDef = ToolDefs.parseCatalog(
        """{"defs":[{"tool-id":"task","tool-label":"Erinnerungen","tool-api-path":"/napi/portal/tasks",
            "tool-cell-schema":{"title":"title","meta":["due"],"actions":["task.set_status"]},
            "tool-actions":["task.set_status"],
            "tool-action-params":{"task.set_status":{"entity_id":"${'$'}id","status":"done"}}}]}""",
    ).single()

    private fun row(page: String?) = ToolCells.mapAll(
        taskDef,
        listOf(JSONObject("""{"id":"abc","title":"Müll"}""").apply { page?.let { put("page", it) } }),
    ).single()

    @Test
    fun aToolRowCarriesItsPageOnHomesOnly() {
        assertEquals("/kalender/erinnerung/abc", Reach.get(row("/kalender/erinnerung/abc"), "page"))
        assertNull(Reach.get(row(null), "page"))
        assertNull("a foreign page taken", Reach.get(row("https://evil.example/x"), "page"))
        val back = ToolCells.decode(ToolCells.encode(listOf(row("/kalender/erinnerung/abc")))).single()
        assertEquals("the page lost in the cache", "/kalender/erinnerung/abc", Reach.get(back, "page"))
        assertEquals("/kalender/erinnerung/abc", ToolRow.fillIn(row("/kalender/erinnerung/abc"), 7).getStringExtra(extraPage()))
        assertNull(ToolRow.fillIn(row(null), 7).getStringExtra(extraPage()))
    }

    // --- the watch: entities only ------------------------------------------------

    @Test
    fun theWatchNamesEntitiesOnly() {
        assertEquals("""{"entity_ids":["sensor.pv"]}""", ApiClient.watchBody(listOf("sensor.pv")).toString())
        assertEquals(listOf("entity_ids"), Napi.WATCH.body)
    }
}
