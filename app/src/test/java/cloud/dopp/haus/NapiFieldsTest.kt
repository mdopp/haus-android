package cloud.dopp.haus

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.BoxHome
import cloud.dopp.haus.data.Napi
import cloud.dopp.haus.data.Persona
import cloud.dopp.haus.data.PersonaStore
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.ToolDefs
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.ui.HomeActivity
import cloud.dopp.haus.widget.ActiveCache
import cloud.dopp.haus.widget.ActiveDevicesWidgetProvider
import cloud.dopp.haus.widget.BoxOverviewWidgetProvider
import cloud.dopp.haus.widget.DeviceWidgetProvider
import cloud.dopp.haus.widget.ToolActionActivity
import cloud.dopp.haus.widget.ToolCells
import cloud.dopp.haus.widget.ToolRow
import cloud.dopp.haus.widget.WidgetCache
import cloud.dopp.haus.widget.WidgetStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
 * The `/napi` fields Haus added for the app (#178, mdopp/haus#30):
 *
 * - the persona's icon is a PNG at `/napi/persona/icon.png`, drawn with
 *   BitmapFactory; no SVG library, no SVG code;
 * - every card and every tool row with an id names its own page on Haus
 *   (`page`): a device, an active row and a tool row open base + page, a
 *   card without one opens `/start`, and only a path on Haus is ever opened;
 * - the watch says `active: true` while an active-devices widget is placed,
 *   so Haus sends it the `active_set` frame;
 * - the Box widget shows approvals and updates apart, as Haus now counts them.
 *
 * Written before the code (red first): new names are reached through [Reach].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NapiFieldsTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private val pages = "cloud.dopp.haus.widget.HausPages"

    /** A 1x1 PNG, as Haus serves a persona's `icon.png`. */
    private val png: ByteArray = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
        Base64.DEFAULT,
    )

    @Before
    fun paired() {
        ServerStore.setBaseUrl(ctx(), "https://haus.example")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "haus_device_T1")
    }

    @After
    fun unpair() {
        ServerStore.clear(ctx())
        PersonaStore.clear(ctx())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun extraPage(): String = ToolActionActivity::class.java.getField("EXTRA_PAGE").get(null) as String

    // --- the persona's icon: a PNG ----------------------------------------------

    @Test
    fun theIconIsThePngHausServes() {
        assertEquals("/napi/persona/icon.png", Napi.PERSONA_ICON.path)
    }

    /** A PNG within the cap is taken whole; SVG text, an oversized file and nothing are not. */
    @Test
    fun onlyAPngWithinTheCapIsTaken() {
        val icon = "cloud.dopp.haus.data.PersonaIcon"
        assertArrayEquals(png, Reach.call(icon, "accept", png) as ByteArray?)
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()
        assertNull("SVG taken as an icon", Reach.call(icon, "accept", svg))
        val max = Class.forName(icon).getField("MAX_BYTES").getInt(null)
        val atCap = png + ByteArray(max - png.size)
        assertArrayEquals("a PNG exactly at the cap", atCap, Reach.call(icon, "accept", atCap) as ByteArray?)
        assertNull("a PNG over the cap", Reach.call(icon, "accept", atCap + byteArrayOf(0)))
        assertNull("half a signature", Reach.call(icon, "accept", png.copyOf(7)))
        assertNull(Reach.call(icon, "accept", null))
    }

    /** The stored PNG is drawn on the app's screen when the persona names an icon, and not when it does not. */
    @Test
    fun theScreenDrawsThePng() {
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1") // the screen asks Haus again: nobody answers
        PersonaStore.save(ctx(), Persona("Joshua", "#3ddc84", "#0c120e", hasIcon = true))
        val save = PersonaStore::class.java.methods.firstOrNull {
            it.name == "saveIcon" && it.parameterTypes.getOrNull(1) == ByteArray::class.java
        }
        assertNotNull("PersonaStore keeps no PNG", save)
        save!!.invoke(PersonaStore, ctx(), png)
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        val icon = a.findViewById<View>(R.id.persona_icon)
        repeat(100) {
            idle()
            if (icon.visibility == View.VISIBLE) return@repeat
            Thread.sleep(20)
        }
        assertEquals("the PNG is not drawn", View.VISIBLE, icon.visibility)

        PersonaStore.save(ctx(), Persona("Joshua", "#3ddc84", "#0c120e", hasIcon = false))
        val b = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        idle()
        assertEquals("drawn although Haus names none", View.GONE, b.findViewById<View>(R.id.persona_icon).visibility)
    }

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

    // --- pages: only a path on Haus --------------------------------------------

    @Test
    fun aPageIsAPathOnHausAndNothingElse() {
        listOf(
            "/raeume/light.kueche", "/kalender/erinnerung/abc-123", "/kontakte/u%201",
            "/dokumente/42", "/notizen/einkauf/liste.md", "/notizen/Notiz:%20Mai.md",
        ).forEach { assertEquals(it, Reach.call(pages, "page", it)) }
        assertEquals("trimmed", "/raeume/light.k", Reach.call(pages, "page", " /raeume/light.k "))
        listOf(
            null, "", " ", "raeume/light.k", "https://evil.example/x", "http://haus.example/raeume/x",
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
    fun aCardCarriesItsPageThroughBothCaches() {
        val c = card("/raeume/light.k")
        assertEquals("/raeume/light.k", Reach.get(c, "page"))
        assertNull(Reach.get(card(null), "page"))
        WidgetCache.putCard(ctx(), 5, c)
        assertEquals("/raeume/light.k", Reach.get(WidgetCache.getCard(ctx(), 5)!!, "page"))
        assertEquals("/raeume/light.k", Reach.get(ActiveCache.decode(ActiveCache.encode(listOf(c))).single(), "page"))
        assertNull(Reach.get(ActiveCache.decode(ActiveCache.encode(listOf(card(null)))).single(), "page"))
    }

    /** What a tap on [id]'s name opens, as the launcher would fire it. */
    private fun tapName(id: Int): String? {
        val v = shadowOf(AppWidgetManager.getInstance(ctx())).getViewFor(id)
        v.findViewById<View>(R.id.w_name).performClick()
        idle()
        return shadowOf(ctx() as android.app.Application).nextStartedActivity?.dataString
    }

    @Test
    fun aDeviceOpensItsPage() {
        val id = shadowOf(AppWidgetManager.getInstance(ctx()))
            .createWidget(DeviceWidgetProvider::class.java, R.layout.widget_device_small)
        WidgetStore.bind(ctx(), id, "light.k", "Küche", "light", null)
        DeviceWidgetProvider.renderCard(ctx(), id, card(null))
        assertEquals("https://haus.example/start?von=widget.device", tapName(id))
        DeviceWidgetProvider.renderCard(ctx(), id, card("/raeume/light.k"))
        assertEquals("https://haus.example/raeume/light.k?von=widget.device", tapName(id))
        DeviceWidgetProvider.renderCard(ctx(), id, card("https://evil.example/x"))
        assertEquals("https://haus.example/start?von=widget.device", tapName(id))
    }

    /** A refresh that cannot read the device keeps drawing the card last read, and its page with it. */
    @Test
    fun aDeviceKeepsItsPageWhenAReadFails() {
        TokenStore.clear(ctx()) // the read fails at once, three times
        val mgr = AppWidgetManager.getInstance(ctx())
        val id = shadowOf(mgr).createWidget(DeviceWidgetProvider::class.java, R.layout.widget_device_small)
        WidgetStore.bind(ctx(), id, "light.k", "Küche", "light", null)
        WidgetCache.putCard(ctx(), id, card("/raeume/light.k"))
        DeviceWidgetProvider().onUpdate(ctx(), mgr, intArrayOf(id))
        repeat(50) { idle(); Thread.sleep(100) } // past the three tries, 1.2 s apart
        assertEquals("https://haus.example/raeume/light.k?von=widget.device", tapName(id))
    }

    /** A failed read of the active list is no news: the widget keeps the list last read, never "…" or "Alles aus". */
    @Test
    fun theActiveWidgetKeepsTheListLastReadWhenAReadFails() {
        ServerStore.setBaseUrl(ctx(), "https://127.0.0.1:1") // paired, but nobody answers
        val mgr = AppWidgetManager.getInstance(ctx())
        val id = shadowOf(mgr).createWidget(ActiveDevicesWidgetProvider::class.java, R.layout.widget_active_small)
        repeat(50) { idle(); Thread.sleep(20) } // the placement's own read fails too
        WidgetStore.setActiveCacheJson(ctx(), ActiveCache.encode(listOf(card(null))))
        ActiveDevicesWidgetProvider().onUpdate(ctx(), mgr, intArrayOf(id))
        val count = { shadowOf(mgr).getViewFor(id).findViewById<TextView>(R.id.av_count).text.toString() }
        repeat(100) { if (count() != "…") return@repeat; idle(); Thread.sleep(100) }
        assertEquals(ctx().getString(R.string.active_count, 1), count())
        assertEquals(1, ActiveCache.decode(WidgetStore.activeCacheJson(ctx())).size)
    }

    /** An active row carries its page, or `/start`, to the row-tap screen. */
    @Test
    fun anActiveRowCarriesItsPage() {
        val row = "cloud.dopp.haus.widget.ActiveRow"
        assertEquals("/raeume/light.k", (Reach.call(row, "fillIn", card("/raeume/light.k")) as Intent).getStringExtra(extraPage()))
        assertEquals("/start", (Reach.call(row, "fillIn", card(null)) as Intent).getStringExtra(extraPage()))
    }

    /** The active list's rows open through the row-tap screen, the one target a collection row has. */
    @Test
    fun theActiveListsRowsGoToTheRowTapScreen() {
        val src = File(RepoFiles.main, "java/cloud/dopp/haus/widget/ActiveDevicesWidgetProvider.kt").readText()
        val template = src.substringAfter("setPendingIntentTemplate(").substringBefore("\n")
        assertTrue("the row template is not the row-tap screen's: $template", template.contains("ToolActionActivity.template("))
        val t = shadowOf(ToolActionActivity.template(ctx(), 1, cloud.dopp.haus.widget.WidgetKind.ACTIVE))
        assertTrue(t.isActivityIntent)
        assertEquals(ToolActionActivity::class.java.name, t.savedIntent.component?.className)
        assertTrue("not mutable: a row's fill-in could not land", (t.flags and android.app.PendingIntent.FLAG_MUTABLE) != 0)
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
        assertEquals("https://haus.example/kontakte/u1", opened?.dataString)
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
        assertEquals("https://haus.example/kalender/erinnerung/abc", shadowOf(a).nextStartedActivity?.dataString)
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
        val sheets = "cloud.dopp.haus.widget.ActionSheets"
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
    fun aToolRowCarriesItsPageOnHausOnly() {
        assertEquals("/kalender/erinnerung/abc", Reach.get(row("/kalender/erinnerung/abc"), "page"))
        assertNull(Reach.get(row(null), "page"))
        assertNull("a foreign page taken", Reach.get(row("https://evil.example/x"), "page"))
        val back = ToolCells.decode(ToolCells.encode(listOf(row("/kalender/erinnerung/abc")))).single()
        assertEquals("the page lost in the cache", "/kalender/erinnerung/abc", Reach.get(back, "page"))
        assertEquals("/kalender/erinnerung/abc", ToolRow.fillIn(row("/kalender/erinnerung/abc"), 7).getStringExtra(extraPage()))
        assertNull(ToolRow.fillIn(row(null), 7).getStringExtra(extraPage()))
    }

    // --- the watch: active while the active-devices widget is placed -------------

    private fun watchBody(ids: List<String>, active: Boolean): JSONObject {
        val m = ApiClient.Companion::class.java.methods.firstOrNull { it.name == "watchBody" && it.parameterCount == 2 }
        assertNotNull("watchBody takes no active flag", m)
        return m!!.invoke(ApiClient.Companion, ids, active) as JSONObject
    }

    @Test
    fun theWatchSaysActiveOnlyWhenAsked() {
        assertEquals("""{"entity_ids":["light.k"],"active":true}""", watchBody(listOf("light.k"), true).toString())
        assertEquals("""{"entity_ids":["light.k"]}""", watchBody(listOf("light.k"), false).toString())
        assertEquals(listOf("entity_ids", "active"), Napi.WATCH.body)
    }

    @Test
    fun theWatchIsActiveWhileAnActiveWidgetIsPlaced() {
        val ws = "cloud.dopp.haus.realtime.WatchSet"
        val shadow = shadowOf(AppWidgetManager.getInstance(ctx()))
        val device = shadow.createWidget(DeviceWidgetProvider::class.java, R.layout.widget_device_small)
        WidgetStore.bind(ctx(), device, "light.k", "Küche", "light", null)
        assertEquals("""{"entity_ids":["light.k"]}""", Reach.call(ws, "body", ctx()).toString())
        shadow.createWidget(ActiveDevicesWidgetProvider::class.java, R.layout.widget_active_small)
        assertEquals("""{"entity_ids":["light.k"],"active":true}""", Reach.call(ws, "body", ctx()).toString())
    }

    /** Placing the first active widget and removing the last tell Haus at once, not at the next re-post. */
    @Test
    fun theFirstAndLastActiveWidgetPostTheWatch() {
        val live = "cloud.dopp.haus.realtime.Live"
        val before = Reach.get(Reach.obj(live), "postWatch")
        var posts = 0
        Reach.set(live, "postWatch", { _: Context -> posts++ })
        try {
            val p = ActiveDevicesWidgetProvider()
            p.onEnabled(ctx())
            assertEquals("placing the first active widget posts no watch", 1, posts)
            p.onDisabled(ctx())
            assertEquals("removing the last active widget posts no watch", 2, posts)
        } finally {
            Reach.set(live, "postWatch", before)
        }
    }

    // --- the Box: approvals and updates apart ------------------------------------

    @Test
    fun theBoxShowsApprovalsAndUpdatesApart() {
        ServerStore.clear(ctx()) // no fetch: the widget draws its last numbers
        val shadow = shadowOf(AppWidgetManager.getInstance(ctx()))
        val id = shadow.createWidget(BoxOverviewWidgetProvider::class.java, R.layout.widget_box_overview)
        WidgetCache.putHome(ctx(), id, BoxHome(servicesUp = 5, servicesFailed = 0, servicesDown = 0, pendingApprovals = 1, pendingUpdates = 3))
        BoxOverviewWidgetProvider().onUpdate(ctx(), AppWidgetManager.getInstance(ctx()), intArrayOf(id))
        repeat(50) { idle(); Thread.sleep(10) }
        val v = shadow.getViewFor(id)
        assertEquals("1", v.findViewById<TextView>(R.id.box_approvals).text.toString())
        assertEquals("3", v.findViewById<TextView>(R.id.box_updates).text.toString())
    }
}
