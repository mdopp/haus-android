package cloud.dopp.haus

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.widget.EnergyRender
import cloud.dopp.haus.widget.EnergyWidgetProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The one energy widget (owner, 2026-11-02): "jetzt" (the four flow legs) and
 * "heute" (what the counters added since midnight) together, and a tap opens
 * Haus's Energie page at the paired address — not hard-coded — counted as
 * this widget's (`von=widget.energy`, #180).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnergyWidgetTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun unpair() = ServerStore.clear(ctx)

    /** Haus's energy picture (internal/napi/energy.go): the flow, and totals with today. */
    private val energy = ApiClient.parseEnergy(
        JSONObject(
            """{"ok":true,"energy":{
              "flow":[
                {"entity_id":"sensor.pv","label":"PV","sense":"supply","state":"3200","unit":"W"},
                {"entity_id":"sensor.haus","label":"Haus","sense":"draw","state":"800","unit":"W"},
                {"entity_id":"sensor.netz","label":"Netz","sense":"grid","state":"-2100","unit":"W"},
                {"entity_id":"sensor.akku","label":"Akku","sense":"battery","state":"-300","unit":"W"}],
              "totals":[
                {"entity_id":"sensor.pv_total","label":"PV-Erzeugung","state":"5012.4","unit":"kWh","today":12.4},
                {"entity_id":"sensor.einspeisung_total","label":"Einspeisung","state":"2306","unit":"kWh","today":6.0},
                {"entity_id":"sensor.netzbezug_total","label":"Netzbezug","state":"1803.1","unit":"kWh","today":3.1},
                {"entity_id":"sensor.batterie_laden","label":"Batterie geladen","state":"700.5","unit":"kWh"}],
              "circuits":[]}}""",
        ),
    )!!

    /**
     * Draw the picture into a placed widget of [w]×[h] dp, applied to a view of
     * our own: the provider's own refresh of the placed widget runs on a thread
     * and must not draw over what is asserted here.
     */
    private fun drawn(w: Int, h: Int, paired: Boolean = true): View {
        val mgr = AppWidgetManager.getInstance(ctx)
        val id = shadowOf(mgr).createWidget(EnergyWidgetProvider::class.java, R.layout.widget_energy)
        mgr.updateAppWidgetOptions(
            id,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
            },
        )
        val views = EnergyRender.build(
            ctx, id, energy, paired,
            EnergyWidgetProvider.tapPending(ctx, id), EnergyWidgetProvider.refreshPending(ctx, id),
        )
        return views.apply(ctx, android.widget.FrameLayout(ctx))
    }

    private fun View.text(name: String): String {
        val id = ctx.resources.getIdentifier(name, "id", ctx.packageName)
        assertTrue("no view $name", id != 0)
        return findViewById<TextView>(id).text.toString()
    }

    @Test
    fun itShowsJetztAndHeuteTogether() {
        val v = drawn(250, 120)
        // jetzt: every leg, in its direction's words
        assertEquals("3,2 kW", v.text("e_now_v0"))
        assertEquals("800 W", v.text("e_now_v1"))
        assertEquals("2,1 kW", v.text("e_now_v2"))
        assertEquals("Ins Netz", v.text("e_now_l2"))
        assertEquals("Akku entlädt", v.text("e_now_l3"))
        // heute: what each counter added since midnight; no today is a dash, never the lifetime count
        assertEquals("Erzeugt", v.text("e_today_l0"))
        assertEquals("12,4 kWh", v.text("e_today_v0"))
        assertEquals("6,0 kWh", v.text("e_today_v1"))
        assertEquals("3,1 kWh", v.text("e_today_v2"))
        assertEquals("—", v.text("e_today_v3"))
        assertEquals("Jetzt", v.text("e_now_label"))
        assertEquals("Heute", v.text("e_today_label"))
    }

    /** Green only for the sun and for feeding the grid; the house's draw and a draw from the grid stay neutral. */
    @Test
    fun consumptionIsNotRed() {
        val v = drawn(250, 120)
        fun color(name: String) = v.findViewById<TextView>(ctx.resources.getIdentifier(name, "id", ctx.packageName)).currentTextColor
        assertEquals("PV", 0xFF3DDC84.toInt(), color("e_now_v0"))
        assertEquals("Haus", 0xFFF2F2F5.toInt(), color("e_now_v1"))
        assertEquals("Netz, Einspeisung", 0xFF3DDC84.toInt(), color("e_now_v2"))
        assertEquals("Akku", 0xFFF2F2F5.toInt(), color("e_now_v3"))
        assertEquals("Bezogen heute", 0xFFF2F2F5.toInt(), color("e_today_v2"))
        val drawing = cloud.dopp.haus.widget.EnergyStyle
        assertEquals("drawn from the grid", drawing.NEUTRAL, drawing.colorFor("grid", 900.0))
    }

    /** A Haus that says nothing about today (not yet updated): no Heute part, no row of dashes. */
    @Test
    fun withoutTodayTheHeutePartIsLeftOut() {
        val noToday = energy.copy(totals = energy.totals.map { it.copy(today = null) })
        val mgr = AppWidgetManager.getInstance(ctx)
        val id = shadowOf(mgr).createWidget(EnergyWidgetProvider::class.java, R.layout.widget_energy)
        mgr.updateAppWidgetOptions(id, Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250); putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110) })
        val v = EnergyRender.build(ctx, id, noToday, true, EnergyWidgetProvider.tapPending(ctx, id), EnergyWidgetProvider.refreshPending(ctx, id))
            .apply(ctx, android.widget.FrameLayout(ctx))
        assertNull("no Heute part at all", v.findViewById<View>(R.id.e_today_block))
        assertEquals("3,2 kW", v.findViewById<TextView>(R.id.e_now_v0).text.toString())
        assertEquals(View.VISIBLE, drawn(250, 120).findViewById<View>(R.id.e_today_block).visibility)
    }

    /** No capitals anywhere in the widget's layouts (owner, 2026-11-03). */
    @Test
    fun noCapitals() {
        listOf("widget_energy.xml", "widget_energy_jetzt.xml", "widget_energy_small.xml").forEach {
            val xml = java.io.File(RepoFiles.main, "res/layout/$it").readText()
            assertTrue(it, !xml.contains("textAllCaps=\"true\""))
        }
    }

    @Test
    fun smallItShowsJetztAndWithRoomALineOfHeute() {
        val bare = drawn(250, 60)
        assertEquals("2,1 kW", bare.text("e_main"))
        assertEquals("Ins Netz", bare.text("e_title"))
        assertEquals(View.GONE, bare.findViewById<View>(R.id.e_sub).visibility)
        assertEquals(View.GONE, bare.findViewById<View>(R.id.e_today).visibility)
        val taller = drawn(250, 100)
        assertEquals("Heute 12,4 kWh erzeugt", taller.text("e_today"))
        assertEquals(View.VISIBLE, taller.findViewById<View>(R.id.e_today).visibility)
        assertNull("no today from Haus, no line", EnergyRender.todayLine(energy.copy(totals = emptyList())))
    }

    /** A tap opens https://<the paired Haus>/energie in the browser, once counted. */
    @Test
    fun aTapOpensHaussEnergiePage() {
        ServerStore.setBaseUrl(ctx, "https://haus.dopp.example")
        val v = drawn(250, 120)
        v.findViewById<View>(R.id.e_root).performClick()
        val opened = shadowOf(ctx as android.app.Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened?.action)
        assertEquals("https://haus.dopp.example/energie?von=widget.energy", opened?.dataString)
        assertTrue(opened!!.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    /** Another box, another domain: the page follows the pairing, nothing is hard-coded. */
    @Test
    fun theAddressIsThePairedOne() {
        ServerStore.setBaseUrl(ctx, "https://haus.anderes.example")
        drawn(110, 40).findViewById<View>(R.id.e_root).performClick()
        assertEquals(
            "https://haus.anderes.example/energie?von=widget.energy",
            shadowOf(ctx as android.app.Application).nextStartedActivity?.dataString,
        )
    }

    /** The arrows read again; they open no page. */
    @Test
    fun theArrowsReadAgain() {
        ServerStore.setBaseUrl(ctx, "https://haus.dopp.example")
        val v = drawn(250, 120)
        v.findViewById<View>(R.id.e_refresh).performClick()
        val app = shadowOf(ctx as android.app.Application)
        assertNull(app.nextStartedActivity)
        assertTrue(app.broadcastIntents.any { it.action == EnergyWidgetProvider.ACTION_REFRESH })
    }
}
