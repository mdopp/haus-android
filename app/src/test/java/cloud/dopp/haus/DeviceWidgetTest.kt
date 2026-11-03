package cloud.dopp.haus

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.Card
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.widget.DeviceWidgetProvider
import cloud.dopp.haus.widget.WidgetActionActivity
import cloud.dopp.haus.widget.WidgetActionReceiver
import cloud.dopp.haus.widget.WidgetCache
import cloud.dopp.haus.widget.WidgetConfigActivity
import cloud.dopp.haus.widget.WidgetStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * One device per widget (restored by the owner, 2026-11-03): the tile draws
 * its device's name and state, and its tap switches it the way the start
 * screen does — a lock opens its chooser, a sensor its page —, the live
 * stream draws its frames, and the watch set names its device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceWidgetTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val app get() = shadowOf(ctx as android.app.Application)

    @After
    fun unpair() = ServerStore.clear(ctx)

    private fun placed(entityId: String, name: String): Int {
        val id = shadowOf(AppWidgetManager.getInstance(ctx)).createWidget(DeviceWidgetProvider::class.java, cloud.dopp.haus.R.layout.widget_device)
        WidgetStore.bind(ctx, id, entityId, name, entityId.substringBefore('.'), null)
        return id
    }

    private fun tile(id: Int, card: Card?): View = DeviceWidgetProvider.build(ctx, id, card).apply(ctx, FrameLayout(ctx))

    private fun View.text(name: Int) = findViewById<TextView>(name).text.toString()

    @Test
    fun theTileShowsTheDeviceAndItsState() {
        val id = placed("light.kueche", "Küche")
        val v = tile(id, Card("light.kueche", "Küche", "light", null, "on", null, brightness = 153))
        assertEquals("Küche", v.text(R.id.d_name))
        assertEquals("an · 60 %", v.text(R.id.d_state))
        assertEquals("before the first read: the bound name, „…“", "…", tile(id, null).text(R.id.d_state))
    }

    @Test
    fun aTapSwitchesALamp() {
        val id = placed("light.kueche", "Küche")
        app.clearBroadcastIntents()
        tile(id, null).findViewById<View>(R.id.d_root).performClick()
        val tap = app.broadcastIntents.single { it.action == WidgetActionReceiver.ACTION_TAP }
        assertEquals("light.kueche", tap.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
        assertEquals(WidgetActionReceiver.OP_TOGGLE, tap.getStringExtra(WidgetActionReceiver.EXTRA_OP))
    }

    @Test
    fun aLockOpensItsChooserWithItsLatch() {
        val id = placed("lock.haustuer", "Haustür")
        app.clearBroadcastIntents()
        tile(id, Card("lock.haustuer", "Haustür", "lock", null, "locked", null, supportedFeatures = 1))
            .findViewById<View>(R.id.d_root).performClick()
        val tap = app.broadcastIntents.single { it.action == WidgetActionReceiver.ACTION_TAP }
        assertEquals(WidgetActionReceiver.OP_LOCK_CHOOSE, tap.getStringExtra(WidgetActionReceiver.EXTRA_OP))
        assertEquals(1, tap.getIntExtra(WidgetActionActivity.EXTRA_FEATURES, -1))
    }

    @Test
    fun aSensorOpensItsPage() {
        ServerStore.setBaseUrl(ctx, "https://haus.example")
        val id = placed("sensor.bad", "Bad")
        tile(id, Card("sensor.bad", "Bad", "sensor", null, "21.5", "°C", page = "/raeume/sensor.bad"))
            .findViewById<View>(R.id.d_root).performClick()
        val opened = app.nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened?.action)
        assertEquals("https://haus.example/raeume/sensor.bad?von=widget.device", opened?.dataString)
    }

    @Test
    fun anUnboundTileOpensThePicker() {
        val id = shadowOf(AppWidgetManager.getInstance(ctx)).createWidget(DeviceWidgetProvider::class.java, R.layout.widget_device)
        DeviceWidgetProvider().onUpdate(ctx, AppWidgetManager.getInstance(ctx), intArrayOf(id))
        val v = shadowOf(AppWidgetManager.getInstance(ctx)).getViewFor(id)
        assertEquals("Einrichten", v.text(R.id.d_state))
        v.findViewById<View>(R.id.d_root).performClick()
        assertEquals(WidgetConfigActivity::class.java.name, app.nextStartedActivity?.component?.className)
    }

    /** A frame of the live stream draws every tile of its entity, and keeps the card for a failed read. */
    @Test
    fun aFrameDrawsTheTile() {
        val id = placed("switch.kaffee", "Kaffee")
        val other = placed("switch.licht", "Licht")
        val n = DeviceWidgetProvider.wakeEntity(ctx, "switch.kaffee", Card("switch.kaffee", "Kaffee", "switch", null, "on", null))
        assertEquals(1, n)
        assertEquals("an", WidgetCache.getCard(ctx, id)?.let { cloud.dopp.haus.ui.StartDevices.stateText(it) })
        assertEquals(null, WidgetCache.getCard(ctx, other))
        assertEquals("an", shadowOf(AppWidgetManager.getInstance(ctx)).getViewFor(id).text(R.id.d_state))
    }

    @Test
    fun theWatchNamesTheTilesDevice() {
        placed("switch.kaffee", "Kaffee")
        @Suppress("UNCHECKED_CAST")
        val watched = Reach.call("cloud.dopp.haus.realtime.WatchSet", "currentEntities", ctx) as List<String>
        assertTrue("$watched", "switch.kaffee" in watched)
    }
}
