package cloud.dopp.haus

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.ApiClient
import cloud.dopp.haus.data.Energy
import cloud.dopp.haus.widget.EnergyRender
import cloud.dopp.haus.widget.EnergyWidgetProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The energy widget drawn at its two common sizes, 4×2 and 2×1 (owner,
 * 2026-11-03: on his phone it left empty space above „Jetzt“ and below
 * „Heute“). Asserted: the content reaches the bottom of the cell — no band of
 * nothing below the last row. The drawings land in `build/widget-preview/`
 * (and in `$WIDGET_PREVIEW_DIR` when set) to be looked at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class EnergyWidgetPreviewTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun energy(today: Boolean): Energy = ApiClient.parseEnergy(
        JSONObject(
            """{"ok":true,"energy":{
              "flow":[
                {"entity_id":"sensor.pv","label":"PV","sense":"supply","state":"3200","unit":"W"},
                {"entity_id":"sensor.haus","label":"Haus","sense":"draw","state":"800","unit":"W"},
                {"entity_id":"sensor.netz","label":"Netz","sense":"grid","state":"-2100","unit":"W"},
                {"entity_id":"sensor.akku","label":"Akku","sense":"battery","state":"-300","unit":"W"}],
              "totals":[
                {"entity_id":"sensor.pv_total","label":"PV-Erzeugung","state":"5012.4","unit":"kWh"${if (today) ""","today":12.4""" else ""}},
                {"entity_id":"sensor.einspeisung_total","label":"Einspeisung","state":"2306","unit":"kWh"${if (today) ""","today":6.0""" else ""}},
                {"entity_id":"sensor.netzbezug_total","label":"Netzbezug","state":"1803.1","unit":"kWh"${if (today) ""","today":3.1""" else ""}},
                {"entity_id":"sensor.batterie_laden","label":"Batterie geladen","state":"700.5","unit":"kWh"${if (today) ""","today":2.0""" else ""}}],
              "circuits":[]}}""",
        ),
    )!!

    /** The widget at [wDp]×[hDp], laid out and drawn; written as [name]. */
    private fun draw(wDp: Int, hDp: Int, e: Energy, name: String): View {
        val mgr = AppWidgetManager.getInstance(ctx)
        val id = shadowOf(mgr).createWidget(EnergyWidgetProvider::class.java, R.layout.widget_energy)
        mgr.updateAppWidgetOptions(
            id,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
            },
        )
        val host = FrameLayout(ctx)
        val v = EnergyRender.build(ctx, id, e, true, EnergyWidgetProvider.tapPending(ctx, id), EnergyWidgetProvider.refreshPending(ctx, id))
            .apply(ctx, host)
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt()
        val h = (hDp * d).toInt()
        host.addView(v, ViewGroup.LayoutParams(w, h))
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF2A2F3A.toInt()) // a launcher's wallpaper behind the rounded card
        host.draw(Canvas(bmp))
        listOfNotNull(File(RepoFiles.root, "app/build/widget-preview"), System.getenv("WIDGET_PREVIEW_DIR")?.let(::File)).forEach { dir ->
            dir.mkdirs()
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return v
    }

    /** The lowest bottom edge of any visible text, in the widget's own coordinates. */
    private fun lowestText(v: View, top: Int = 0): Int {
        if (v.visibility != View.VISIBLE) return 0
        if (v is android.widget.TextView && v.text.isNotEmpty()) return top + v.bottom - v.top
        if (v !is ViewGroup) return 0
        return (0 until v.childCount).maxOfOrNull { lowestText(v.getChildAt(it), top + v.getChildAt(it).top) } ?: 0
    }

    private fun assertFills(v: View, label: String) {
        val slack = v.height - lowestText(v)
        val limit = (28 * ctx.resources.displayMetrics.density).toInt() // the bottom padding and a little air
        assertTrue("$label: ${slack}px of nothing below the last row (at most $limit)", slack <= limit)
    }

    @Test
    fun fourByTwoFillsItsCell() {
        val v = draw(300, 160, energy(true), "energy-4x2")
        assertFills(v, "4×2 with heute")
        assertEquals("12,4 kWh", v.findViewById<android.widget.TextView>(R.id.e_today_v0).text.toString())
        // every value fits its cell: drawn whole, not cut off at the bottom
        listOf(R.id.e_now_v0, R.id.e_now_v2, R.id.e_today_v2).forEach { id ->
            val t = v.findViewById<View>(id)
            assertTrue("value $id cut: ${t.height} < ${t.measuredHeight}", t.height >= t.measuredHeight)
        }
        assertFills(draw(300, 160, energy(false), "energy-4x2-ohne-heute"), "4×2 without heute")
    }

    @Test
    fun twoByOneFillsItsCell() {
        assertFills(draw(150, 72, energy(true), "energy-2x1"), "2×1")
    }
}
