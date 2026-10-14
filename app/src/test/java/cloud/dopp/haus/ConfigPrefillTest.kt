package cloud.dopp.haus

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.data.ToolDefs
import cloud.dopp.haus.widget.ConfigEntry
import cloud.dopp.haus.widget.ConfigPrefill
import cloud.dopp.haus.widget.ToolWidgetConfigActivity
import cloud.dopp.haus.widget.WidgetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The **pre-fill** half of re-entering a config screen (#96).
 *
 * `ReconfigureTest` pins the dangerous half — what a back-out answers. This pins
 * the half the device test found missing: a reconfigure must open **showing the
 * binding it already has**, and a first placement must show nothing, because there
 * is nothing.
 *
 * Two levels, because the device symptom lived in the gap between them: the rule
 * itself ([ConfigPrefill.markedIndex]) and the screens actually drawing it — the
 * mark used to be a suffix on a muted second line and the scroll only promised
 * visibility, so a screen that "had" the pre-fill still read as empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigPrefillTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** A catalog big enough that the bound entry is far from the top of the list. */
    private val catalog = (0 until 30).joinToString(
        prefix = """{"defs":[""", separator = ",", postfix = "]}",
    ) { i ->
        """{"tool-id":"t$i","tool-label":"Tool $i","tool-api-path":"/napi/portal/t$i",
           "tool-compose-path":"#/p/t$i/new","tool-cell-schema":{"title":"title"}}"""
    }

    // --- the rule -------------------------------------------------------------

    @Test fun aReconfigureMarksTheRowCarryingTheCurrentBinding() {
        val rows = listOf("light.flur", "cover.buero", "switch.terrasse")
        assertEquals(
            1,
            ConfigPrefill.markedIndex(ConfigEntry.RECONFIGURE, rows) { it == "cover.buero" },
        )
        assertTrue(ConfigPrefill.marks(1))
    }

    @Test fun aFirstPlacementMarksNothingEvenWhenTheStoreWouldMatch() {
        // A recycled appWidgetId must not pre-fill a widget the user has not
        // chosen anything for yet — the entry mode decides, not the store.
        val rows = listOf("light.flur", "cover.buero")
        assertEquals(
            ConfigPrefill.NONE,
            ConfigPrefill.markedIndex(ConfigEntry.FIRST_PLACEMENT, rows) { it == "cover.buero" },
        )
        assertFalse(ConfigPrefill.marks(ConfigPrefill.NONE))
    }

    @Test fun aBindingHiddenByTheSearchFilterMarksNothing() {
        val rows = listOf("light.flur")
        assertEquals(
            ConfigPrefill.NONE,
            ConfigPrefill.markedIndex(ConfigEntry.RECONFIGURE, rows) { it == "cover.buero" },
        )
    }

    @Test fun theMarkedIndexCountsRenderRows_notDevices() {
        // The device picker interleaves room headers, so the index the list is
        // scrolled to must be the *render* position, not the device's ordinal.
        val rows = listOf("§Büro", "cover.buero", "§Flur", "light.flur")
        assertEquals(
            3,
            ConfigPrefill.markedIndex(ConfigEntry.RECONFIGURE, rows) { it == "light.flur" },
        )
    }

    @Test fun paintingARowTurnsTheMarkOnAndOffAgain() {
        // Rows are recycled: a row that stops being the current one must lose the
        // check as well as gain it, or the mark smears down the list.
        val row = View(ctx)
        val check = ImageView(ctx)
        ConfigPrefill.paint(row, current = true, check = check)
        assertEquals(View.VISIBLE, check.visibility)
        assertNotNull(row.background)
        ConfigPrefill.paint(row, current = false, check = check)
        assertEquals(View.GONE, check.visibility)
    }

    // --- the screens drawing it ----------------------------------------------

    private fun pair() {
        // Unreachable on purpose: the pickers fall back to the last-seen catalog,
        // which is what a config screen opened offline sees too.
        ServerStore.setBaseUrl(ctx, "http://127.0.0.1:1")
        TokenStore.save(ctx, "haus_device_TESTTOKEN123")
        WidgetStore.setToolCatalogJson(ctx, catalog)
    }

    private fun <T : android.app.Activity> open(cls: Class<T>, widgetId: Int): T {
        val intent = Intent(ctx, cls).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        val activity = Robolectric.buildActivity(cls, intent).setup().get()
        val list = activity.findViewById<ListView>(R.id.config_list)
        var tries = 0
        while (list.adapter == null && tries < 100) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
            tries++
        }
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    /** Render row [pos] the way the list would, fresh (no recycled view). */
    private fun rowAt(activity: android.app.Activity, pos: Int): View {
        val list = activity.findViewById<ListView>(R.id.config_list)
        return list.adapter.getView(pos, null, list)
    }

    private fun label(v: View) = v.findViewById<TextView>(R.id.type_label).text.toString()

    private fun check(v: View) = v.findViewById<ImageView>(R.id.type_current).visibility

    @Test fun reconfiguringAToolWidgetOpensOnItsCurrentTool() {
        pair()
        WidgetStore.bindTool(ctx, 77, "t25", "Tool 25")
        val a = open(ToolWidgetConfigActivity::class.java, 77)
        val list = a.findViewById<ListView>(R.id.config_list)
        assertNotNull(list.adapter)
        assertEquals(30, list.adapter.count)

        // Marked: named as current, checked, and set apart from its neighbours.
        val bound = rowAt(a, 25)
        assertTrue(label(bound).startsWith("Tool 25"))
        assertTrue(label(bound).contains("aktuell"))
        assertEquals(View.VISIBLE, check(bound))
        assertNotNull(bound.background)

        // …and only it.
        val other = rowAt(a, 0)
        assertEquals("Tool 0", label(other))
        assertEquals(View.GONE, check(other))

        // Opened *on* it: the list is positioned at the bound row, not at the top.
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 1080, 600)
        assertTrue(
            "bound row must be on screen, was ${list.firstVisiblePosition}..${list.lastVisiblePosition}",
            25 in list.firstVisiblePosition..list.lastVisiblePosition,
        )
        assertTrue("must not open at the top of the household", list.firstVisiblePosition > 0)
    }

    @Test fun placingAFreshToolWidgetMarksNothing() {
        pair()
        val a = open(ToolWidgetConfigActivity::class.java, 78)
        val list = a.findViewById<ListView>(R.id.config_list)
        assertEquals(30, list.adapter.count)
        for (pos in intArrayOf(0, 25, 29)) {
            val v = rowAt(a, pos)
            assertFalse(label(v).contains("aktuell"))
            assertEquals(View.GONE, check(v))
        }
        assertEquals(0, list.firstVisiblePosition)
    }

    /** Cancelling a reconfigure is `ReconfigureTest`'s subject; this only guards
     *  that showing the pre-fill did not start writing to the store. */
    @Test fun openingAReconfigureWritesNothing() {
        pair()
        WidgetStore.bindTool(ctx, 91, "t7", "Tool 7")
        open(ToolWidgetConfigActivity::class.java, 91)
        assertEquals("t7", WidgetStore.toolId(ctx, 91))
        assertEquals("Tool 7", WidgetStore.toolLabel(ctx, 91))
    }
}
