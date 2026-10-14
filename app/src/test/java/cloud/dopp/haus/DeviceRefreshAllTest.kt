package cloud.dopp.haus

import android.appwidget.AppWidgetManager
import cloud.dopp.haus.widget.DeviceWidgetProvider
import cloud.dopp.haus.widget.BoxServicesWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Catch-up on reconnect (#138).
 *
 * The realtime SSE is dropped while the screen is off, and the server publishes
 * only on the NEXT `state_changed` — there is no snapshot on connect. So a device
 * that flipped while the screen was off is invisible to the tile until something
 * re-fetches it. The live stream calls [DeviceWidgetProvider.refreshAll] once it
 * is up after screen-on (`LiveStream`, laib ADR 0022 §2) for exactly that; this pins the contract that call relies on — **every**
 * bound tile is asked to refresh, not just the first, and none when there are none.
 *
 * The reported symptom: a garage door closed by hand kept showing "offen" until
 * the tile was tapped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceRefreshAllTest {

    private fun bind(n: Int): List<Int> {
        val ctx = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(AppWidgetManager.getInstance(ctx))
        shadow.createWidgets(DeviceWidgetProvider::class.java, R.layout.widget_device_small, n)
        return AppWidgetManager.getInstance(ctx)
            .getAppWidgetIds(android.content.ComponentName(ctx, DeviceWidgetProvider::class.java))
            .toList()
    }

    /** One refresh broadcast per bound tile — the whole point of the catch-up. */
    private fun refreshedIds(n: Int): List<Int> {
        val ctx = RuntimeEnvironment.getApplication()
        val ids = bind(n)
        val app = shadowOf(ctx)
        app.clearBroadcastIntents()
        DeviceWidgetProvider.refreshAll(ctx)
        return app.broadcastIntents
            .filter { it.action == DeviceWidgetProvider.ACTION_REFRESH }
            .map { it.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) }
            .also { assertEquals("bound tiles", n, ids.size) }
    }

    @Test
    fun `refreshes every bound tile, not just one`() {
        assertEquals(3, refreshedIds(3).size)
    }

    @Test
    fun `each refresh names its own tile`() {
        val refreshed = refreshedIds(3)
        assertEquals("no tile addressed twice", refreshed.size, refreshed.distinct().size)
        assertEquals("no unaddressed broadcast", 0, refreshed.count { it == -1 })
    }

    /** No tiles placed → nothing broadcast. A catch-up must be free when idle. */
    @Test
    fun `is a no-op without tiles`() {
        assertEquals(0, refreshedIds(0).size)
    }

    /**
     * The Box services tile (#137) — the other half of the same report
     * ("Dienste bleiben gleich bis manuell reloaded"). It has `updatePeriodMillis=0`
     * and no `card_state` can ever wake it, since a Box service is not an HA
     * entity, so the connect-time refresh is its ONLY automatic path. If this test
     * goes, the tile silently freezes again.
     */
    @Test
    fun `box services tile refreshes on connect too`() {
        val ctx = RuntimeEnvironment.getApplication()
        shadowOf(AppWidgetManager.getInstance(ctx))
            .createWidgets(BoxServicesWidgetProvider::class.java, R.layout.widget_sb_services, 2)
        val app = shadowOf(ctx)
        app.clearBroadcastIntents()
        BoxServicesWidgetProvider.refreshAll(ctx)
        val refreshed = app.broadcastIntents
            .filter { it.action == BoxServicesWidgetProvider.ACTION_REFRESH }
            .map { it.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) }
        assertEquals("one refresh per placed tile", 2, refreshed.size)
        assertEquals("no unaddressed broadcast", 0, refreshed.count { it == -1 })
    }
}
