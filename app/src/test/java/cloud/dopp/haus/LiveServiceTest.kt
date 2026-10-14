package cloud.dopp.haus

import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.ui.HomeActivity
import cloud.dopp.haus.widget.ActiveDevicesWidgetProvider
import cloud.dopp.haus.widget.BoxOverviewWidgetProvider
import cloud.dopp.haus.widget.BoxServicesWidgetProvider
import cloud.dopp.haus.widget.DeviceWidgetProvider
import cloud.dopp.haus.widget.EnergyStatsWidgetProvider
import cloud.dopp.haus.widget.EnergyTotalsWidgetProvider
import cloud.dopp.haus.widget.EnergyWidgetProvider
import cloud.dopp.haus.widget.ToolWidgetProvider
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The live widgets (#176, the owner's call: the old app's mechanism, for
 * widgets only). One foreground service holds `/napi/portal/events` while the
 * screen is on: it listens for the screen itself, connects on screen-on, drops
 * the connection on screen-off, and once connected posts the watch set and has
 * every widget it keeps current read again. A 401 stops it; the switch in the
 * app turns it off. Without a paired Haus or a widget to feed, it does not run.
 *
 * [Reach] finds the service and its seams by name (red first): `Live.open`
 * stands in for the SSE connection, `Live.postWatch` for the watch post.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveServiceTest {

    private val live = "cloud.dopp.haus.realtime.Live"
    private val serviceClass = "cloud.dopp.haus.realtime.LiveService"

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    /** A connection that never touches the network. */
    private class FakeSource(private val req: Request) : EventSource {
        var cancelled = false
        override fun request() = req
        override fun cancel() { cancelled = true }
    }

    private val opened = mutableListOf<FakeSource>()
    private val listeners = mutableListOf<EventSourceListener>()
    private var watchPosts = 0

    /** Every widget the stream keeps current; a static tile is not one of them. */
    private val fed: List<Pair<Class<out android.appwidget.AppWidgetProvider>, Int>> = listOf(
        DeviceWidgetProvider::class.java to R.layout.widget_device_small,
        ActiveDevicesWidgetProvider::class.java to R.layout.widget_active_small,
        BoxOverviewWidgetProvider::class.java to R.layout.widget_box_overview,
        BoxServicesWidgetProvider::class.java to R.layout.widget_box_services,
        EnergyWidgetProvider::class.java to R.layout.widget_energy_small,
        EnergyStatsWidgetProvider::class.java to R.layout.widget_energy_stats,
        EnergyTotalsWidgetProvider::class.java to R.layout.widget_energy_totals,
        ToolWidgetProvider::class.java to R.layout.widget_tool,
    )

    @Before
    fun pairedWithWidgetsAndTheScreenOn() {
        ServerStore.setBaseUrl(ctx(), "https://haus.example")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "haus_device_T1")
        screen(true)
        runCatching { Reach.call(live, "setEnabled", ctx(), true) }
        runCatching {
            Reach.set(live, "open", { req: Request, l: EventSourceListener ->
                listeners += l
                FakeSource(req).also { opened += it }
            })
        }
        runCatching { Reach.set(live, "postWatch", { _: Context -> watchPosts++ }) }
    }

    private fun place(vararg which: Pair<Class<out android.appwidget.AppWidgetProvider>, Int>) {
        val shadow = shadowOf(AppWidgetManager.getInstance(ctx()))
        which.forEach { (cls, layout) -> shadow.createWidget(cls, layout) }
    }

    private fun screen(on: Boolean) {
        shadowOf(ctx().getSystemService(PowerManager::class.java)).setIsInteractive(on)
    }

    private fun broadcast(action: String) {
        ctx().sendBroadcast(Intent(action))
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Suppress("UNCHECKED_CAST")
    private fun start(): ServiceController<Service> {
        val cls = Class.forName(serviceClass) as Class<Service>
        val c = Robolectric.buildService(cls).create().startCommand(0, 1)
        idle()
        return c
    }

    private fun response(code: Int) = Response.Builder()
        .request(Request.Builder().url("https://haus.example/napi/portal/events").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("x").build()

    @Test
    fun screenOnConnectsScreenOffDisconnects() {
        place(fed.first())
        start()
        assertEquals("one connection while the screen is on", 1, opened.size)
        val req = opened[0].request()
        assertEquals("https://haus.example/napi/portal/events", req.url.toString())
        assertEquals("Bearer haus_device_T1", req.header("Authorization"))
        assertEquals("text/event-stream", req.header("Accept"))

        screen(false)
        broadcast(Intent.ACTION_SCREEN_OFF)
        assertTrue("screen off drops the connection", opened[0].cancelled)
        assertEquals(1, opened.size)

        screen(true)
        broadcast(Intent.ACTION_SCREEN_ON)
        broadcast(Intent.ACTION_USER_PRESENT)
        assertEquals("screen on connects again, once for both broadcasts", 2, opened.size)
        assertFalse(opened[1].cancelled)
    }

    @Test
    fun withTheScreenOffItDoesNotConnect() {
        place(fed.first())
        screen(false)
        start()
        assertEquals(0, opened.size)
    }

    @Test
    fun onceConnectedItPostsTheWatchSetAndReadsEveryFedWidgetAgain() {
        place(*fed.toTypedArray())
        start()
        val app = shadowOf(ctx() as android.app.Application)
        app.clearBroadcastIntents()
        listeners.last().onOpen(opened.last(), response(200))
        idle()
        assertTrue("no watch post", watchPosts >= 1)
        val asked = app.broadcastIntents.mapNotNull { it.component?.className }.toSet()
        val missed = fed.map { it.first.name }.filter { it !in asked }
        assertTrue("not read again: $missed", missed.isEmpty())
    }

    @Test
    fun a401StopsTheService() {
        place(fed.first())
        val c = start()
        listeners.last().onFailure(opened.last(), null, response(401))
        idle()
        assertTrue("still running after a 401", shadowOf(c.get()).isStoppedBySelf)
        assertEquals("tried again after a 401", 1, opened.size)
    }

    @Test
    fun anotherFailureIsTriedAgainWithBackoff() {
        place(fed.first())
        start()
        listeners.last().onFailure(opened.last(), null, response(502))
        idle()
        assertEquals("not at once", 1, opened.size)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertEquals("again after the first backoff", 2, opened.size)
    }

    @Test
    fun withoutAWidgetToFeedItDoesNotRun() {
        place(cloud.dopp.haus.widget.CameraWidgetProvider::class.java to R.layout.widget_camera)
        val c = start()
        assertEquals(0, opened.size)
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }

    @Test
    fun itRunsInTheForegroundWithAQuietNotification() {
        place(fed.first())
        val c = start()
        val s = shadowOf(c.get())
        assertNotNull("no foreground notification", s.lastForegroundNotification)
        assertTrue(s.lastForegroundNotificationId != 0)
        val nm = ctx().getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(s.lastForegroundNotification.channelId)
        assertNotNull("no channel", channel)
        assertEquals("Live-Widgets", channel.name.toString())
        assertTrue("not low importance: ${channel.importance}", channel.importance <= NotificationManager.IMPORTANCE_LOW)
        assertFalse("shows a badge", channel.canShowBadge())
        val tap = shadowOf(s.lastForegroundNotification.contentIntent).savedIntent
        assertEquals(HomeActivity::class.java.name, tap.component?.className)
    }

    @Test
    fun theSwitchTurnsItOff() {
        place(fed.first())
        Reach.call(live, "setEnabled", ctx(), false)
        assertEquals(false, Reach.call(live, "isEnabled", ctx()))
        val c = start()
        assertEquals("connected although switched off", 0, opened.size)
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }

    @Test
    fun theSwitchInTheAppStopsTheService() {
        place(fed.first())
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        val id = ctx().resources.getIdentifier("live_switch", "id", ctx().packageName)
        assertTrue("no live_switch in the app", id != 0)
        val sw = a.findViewById<android.widget.CompoundButton>(id)
        assertTrue("on by default once paired", sw.isChecked)
        sw.performClick()
        idle()
        assertEquals(false, Reach.call(live, "isEnabled", ctx()))
        val stopped = shadowOf(ctx() as android.app.Application).nextStoppedService
        assertEquals(serviceClass, stopped?.component?.className)
    }

    @Test
    fun theEnergyFlowIsWatchedAndItsFramesReadTheEnergyWidgetsAgain() {
        place(fed.first(), fed[4], fed[6])
        Reach.call("cloud.dopp.haus.realtime.WatchSet", "rememberEnergy", ctx(), listOf("sensor.pv_power"))
        @Suppress("UNCHECKED_CAST")
        val watched = Reach.call("cloud.dopp.haus.realtime.WatchSet", "currentEntities", ctx()) as List<String>
        assertTrue("energy not watched: $watched", "sensor.pv_power" in watched)

        start()
        listeners.last().onOpen(opened.last(), response(200))
        idle()
        val app = shadowOf(ctx() as android.app.Application)
        app.clearBroadcastIntents()
        listeners.last().onEvent(
            opened.last(), null, "card_state",
            """{"entity_id":"sensor.pv_power","card":{"entity_id":"sensor.pv_power","domain":"sensor","state":"812"}}""",
        )
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(15))
        val asked = app.broadcastIntents.mapNotNull { it.component?.className }.toSet()
        assertTrue("energy widget not read: $asked", EnergyWidgetProvider::class.java.name in asked)
        assertTrue("energy totals not read: $asked", EnergyTotalsWidgetProvider::class.java.name in asked)
    }

    @Test
    fun bootStartsItAgain() {
        place(fed.first())
        val receiver = Class.forName("cloud.dopp.haus.realtime.BootReceiver").getDeclaredConstructor()
            .newInstance() as android.content.BroadcastReceiver
        receiver.onReceive(ctx(), Intent(Intent.ACTION_BOOT_COMPLETED))
        val started = shadowOf(ctx() as android.app.Application).nextStartedService
        assertEquals(serviceClass, started?.component?.className)
        assertEquals(
            ComponentName(ctx(), serviceClass),
            started?.component,
        )
    }
}
