package cloud.dopp.homes

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.Card
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.widget.DeviceLook
import cloud.dopp.homes.widget.DeviceWidgetProvider
import cloud.dopp.homes.widget.WidgetActionActivity
import cloud.dopp.homes.widget.WidgetActionReceiver
import cloud.dopp.homes.widget.WidgetCache
import cloud.dopp.homes.widget.WidgetConfigActivity
import cloud.dopp.homes.widget.WidgetStore
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
        val id = shadowOf(AppWidgetManager.getInstance(ctx)).createWidget(DeviceWidgetProvider::class.java, cloud.dopp.homes.R.layout.widget_device)
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

    /**
     * Owner, 2026-11-03: „Viel Text statt Bildern“. One cell: a garage says
     * shut or open by its picture and colour, its name on one line without
     * the room HA appends; no state in words — the picture says it.
     */
    @Test
    fun aGarageIsAPicture() {
        val id = placed("cover.garagentor", "Garagentor Garage")
        WidgetStore.bind(ctx, id, "cover.garagentor", "Garagentor Garage", "cover", "garage")
        fun card(state: String) = Card("cover.garagentor", "Garagentor Garage", "cover", "garage", state, null)
        val shut = tile(id, card("closed"))
        assertEquals("Garagentor", shut.text(R.id.d_name))
        assertEquals(1, shut.findViewById<TextView>(R.id.d_name).maxLines)
        assertEquals(View.GONE, shut.findViewById<View>(R.id.d_state).visibility)
        assertEquals("Garagentor, zu", shut.findViewById<View>(R.id.d_body).contentDescription)
        assertEquals(R.drawable.ic_garage_closed, DeviceLook.icon("cover", "garage", "closed"))
        assertEquals(R.drawable.ic_garage_open, DeviceLook.icon("cover", "garage", "open"))
        assertEquals(DeviceLook.SECURE, DeviceLook.tint("cover", "garage", card("closed")))
        assertEquals(DeviceLook.OPEN, DeviceLook.tint("cover", "garage", card("open")))
        assertEquals(DeviceLook.MOVING, DeviceLook.tint("cover", "garage", card("opening")))
        // a device that does not answer says so in words, in the grey of nothing known
        assertEquals(View.VISIBLE, tile(id, card("unavailable")).findViewById<View>(R.id.d_state).visibility)
        assertEquals(DeviceLook.UNKNOWN, DeviceLook.tint("cover", "garage", card("unavailable")))
    }

    /** Pictures that differ by silhouette, not only by colour (#91). */
    @Test
    fun theStatesHaveTheirOwnPictures() {
        assertEquals(R.drawable.ic_blind_closed, DeviceLook.icon("cover", "shutter", "closed"))
        assertEquals(R.drawable.ic_blind_open, DeviceLook.icon("cover", null, "open"))
        assertEquals(R.drawable.ic_light_on, DeviceLook.icon("light", null, "on"))
        assertEquals(R.drawable.ic_light_off, DeviceLook.icon("light", null, "off"))
        assertEquals(R.drawable.ic_lock_locked, DeviceLook.icon("lock", null, "locked"))
        assertEquals(DeviceLook.SECURE, DeviceLook.tint("lock", null, Card("lock.t", "T", "lock", null, "locked", null)))
        assertEquals(DeviceLook.ON, DeviceLook.tint("light", null, Card("light.k", "K", "light", null, "on", null)))
        assertEquals(DeviceLook.OFF, DeviceLook.tint("light", null, Card("light.k", "K", "light", null, "off", null)))
        assertEquals("nothing read yet", DeviceLook.UNKNOWN, DeviceLook.tint("light", null, null))
    }

    /** The name, one short line: the room HA repeats goes, the rest stays. */
    @Test
    fun theNameIsShort() {
        assertEquals("Garagentor", DeviceLook.shortName("Garagentor Garage"))
        assertEquals("Garagentor", DeviceLook.shortName("Garage Garagentor"))
        assertEquals("Küche Licht", DeviceLook.shortName("Küche Licht"))
        assertEquals("Bad", DeviceLook.shortName("Bad"))
        assertEquals("Haustür", DeviceLook.shortName(" Haustür "))
    }

    /** A reading or a shutter in between needs its words even on one cell; a lamp's brightness is a bar. */
    @Test
    fun wordsOnlyWhereThePictureCannotSayIt() {
        val bad = placed("climate.bad", "Bad")
        val heating = tile(bad, Card("climate.bad", "Bad", "climate", null, "heat", null, temperature = 21.5))
        assertEquals(View.VISIBLE, heating.findViewById<View>(R.id.d_state).visibility)
        assertEquals("21,5 °C", heating.text(R.id.d_state))
        val lamp = tile(placed("light.kueche", "Küche"), Card("light.kueche", "Küche", "light", null, "on", null, brightness = 153))
        assertEquals(View.GONE, lamp.findViewById<View>(R.id.d_state).visibility)
        assertEquals(View.VISIBLE, lamp.findViewById<View>(R.id.d_bar).visibility)
    }

    /** Two cells wide: the picture beside the name and the state, in words. */
    @Test
    fun wideShowsTheWordsBesideThePicture() {
        assertTrue(DeviceWidgetProvider.wide(140, 72))
        assertTrue(!DeviceWidgetProvider.wide(64, 72))
        assertTrue("2×2 stays upright", !DeviceWidgetProvider.wide(140, 140))
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
        ServerStore.setBaseUrl(ctx, "https://homes.example")
        val id = placed("sensor.bad", "Bad")
        tile(id, Card("sensor.bad", "Bad", "sensor", null, "21.5", "°C", page = "/raeume/sensor.bad"))
            .findViewById<View>(R.id.d_root).performClick()
        val opened = app.nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened?.action)
        assertEquals("https://homes.example/raeume/sensor.bad?von=widget.device", opened?.dataString)
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
        assertEquals("an", WidgetCache.getCard(ctx, id)?.let { cloud.dopp.homes.ui.StartDevices.stateText(it) })
        assertEquals(null, WidgetCache.getCard(ctx, other))
        assertEquals("an", shadowOf(AppWidgetManager.getInstance(ctx)).getViewFor(id).text(R.id.d_state))
    }

    @Test
    fun theWatchNamesTheTilesDevice() {
        placed("switch.kaffee", "Kaffee")
        @Suppress("UNCHECKED_CAST")
        val watched = Reach.call("cloud.dopp.homes.realtime.WatchSet", "currentEntities", ctx) as List<String>
        assertTrue("$watched", "switch.kaffee" in watched)
    }
}
