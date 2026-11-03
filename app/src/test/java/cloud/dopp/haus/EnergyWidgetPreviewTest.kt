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
import cloud.dopp.haus.widget.EnergyFluss
import cloud.dopp.haus.widget.EnergyPicture
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
 * The energy widget drawn at its common sizes, with the owner's evening of
 * 2026-11-03 (no sun, the battery at 83 % carries the house): 4×2 as on his
 * phone, with and without Heute and the charge, 2×2, 4×1 and 2×1. Asserted:
 * the picture is drawn at the screen's density for its view. The drawings
 * land in `build/widget-preview/` (and in `$WIDGET_PREVIEW_DIR` when set) to
 * be looked at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class EnergyWidgetPreviewTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun energy(today: Boolean, charge: Boolean = true): Energy = ApiClient.parseEnergy(
        JSONObject(
            """{"ok":true,"energy":{
              "flow":[
                {"entity_id":"sensor.pv","label":"PV","sense":"supply","state":"0","unit":"W"},
                {"entity_id":"sensor.haus","label":"Haus","sense":"draw","state":"271","unit":"W"},
                {"entity_id":"sensor.netz","label":"Netz","sense":"grid","state":"-4","unit":"W"},
                {"entity_id":"sensor.akku","label":"Akku","sense":"battery","state":"-276","unit":"W"}],
              "totals":[
                {"entity_id":"sensor.pv_total","label":"PV-Erzeugung","state":"5012.4","unit":"kWh"${if (today) ""","today":8.2""" else ""}},
                {"entity_id":"sensor.einspeisung_total","label":"Einspeisung","state":"2306","unit":"kWh"${if (today) ""","today":0.3""" else ""}},
                {"entity_id":"sensor.netzbezug_total","label":"Netzbezug","state":"1803.1","unit":"kWh"${if (today) ""","today":2.0""" else ""}},
                {"entity_id":"sensor.batterie_laden","label":"Batterie geladen","state":"700.5","unit":"kWh"${if (today) ""","today":5.7""" else ""}}],
              ${if (charge) CHARGE else ""}
              "circuits":[]}}""",
        ),
    )!!

    private val CHARGE = "\"charge\":{\"entity_id\":\"sensor.akku_soc\",\"label\":\"Akku-Füllstand\",\"state\":\"83\",\"unit\":\"%\"},"

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
        val v = EnergyRender.build(ctx, id, e, true, EnergyWidgetProvider.tapPending(ctx, id))
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

    private fun View.picture(name: Int): Bitmap? =
        (findViewById<android.widget.ImageView>(name).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap

    /** The picture is drawn at the screen's density and fills its view: never blurred up, never a stamp in a corner. */
    private fun assertSharp(v: View, id: Int, label: String) {
        val bmp = v.picture(id) ?: error("$label: no picture")
        val iv = v.findViewById<View>(id)
        assertTrue("$label: picture ${bmp.width}x${bmp.height} for a view of ${iv.width}x${iv.height}",
            bmp.width >= iv.width * 0.85 || bmp.height >= iv.height * 0.85)
    }

    @Test
    fun fourByTwo() {
        val v = draw(347, 163, energy(true), "energy-4x2")
        assertSharp(v, R.id.e_flow, "4×2")
        assertEquals("8,2 kWh", v.findViewById<android.widget.TextView>(R.id.e_today_v0).text.toString())
        assertSharp(draw(347, 163, energy(false), "energy-4x2-ohne-heute"), R.id.e_flow, "4×2 without heute")
        draw(347, 163, energy(true, charge = false), "energy-4x2-ohne-akkustand")
    }

    @Test
    fun twoByTwo() = assertSharp(draw(160, 163, energy(true), "energy-2x2"), R.id.e_flow, "2×2")

    @Test
    fun oneHigh() {
        assertSharp(draw(150, 72, energy(true), "energy-2x1"), R.id.e_ring, "2×1")
        draw(347, 72, energy(true), "energy-4x1")
        draw(150, 72, energy(true, charge = false), "energy-2x1-ohne-akkustand")
    }

    /** The house with these legs (W) — pv, house, grid (+drawn), battery (+charging) — at 83 %. */
    private fun legs(pv: Int, haus: Int, netz: Int, akku: Int): Energy =
        energy(true).copy(
            flow = listOf(
                cloud.dopp.haus.data.EnergyFlow("PV", pv.toDouble(), "W", "supply", "sensor.pv"),
                cloud.dopp.haus.data.EnergyFlow("Haus", haus.toDouble(), "W", "draw", "sensor.haus"),
                cloud.dopp.haus.data.EnergyFlow("Netz", netz.toDouble(), "W", "grid", "sensor.netz"),
                cloud.dopp.haus.data.EnergyFlow("Akku", akku.toDouble(), "W", "battery", "sensor.akku"),
            ),
        )

    /** Each of the six ways the power can take, the named one in it (coordinator, 2026-11-03). */
    private val flows = mapOf(
        "solar-haus" to legs(800, 800, 0, 0),
        "solar-akku" to legs(1500, 500, 0, 1000),
        "solar-netz" to legs(3000, 500, -2500, 0),
        "netz-haus" to legs(0, 800, 800, 0),
        "akku-haus" to legs(0, 271, -4, -276),
        "netz-akku" to legs(0, 300, 1300, 1000),
    )

    @Test
    fun everyWay() {
        for ((name, e) in flows) {
            val (von, nach) = name.split('-')
            val f = cloud.dopp.haus.widget.EnergyFluss.of(e)
            assertTrue("$name: the way is there", f.aktiv.any { it.von == von && it.nach == nach })
            draw(347, 163, e, "energy-4x2-flows-$name")
        }
    }

    /**
     * No way and no arrow lies on a pill, or on Akku's ring, at 4×2, 2×2 and
     * a flat 4×2; the ring stays inside the field. Every way of the six.
     */
    @Test
    fun noArrowOnAPill() {
        val ways = listOf("solar" to "haus", "solar" to "akku", "solar" to "netz", "netz" to "haus", "akku" to "haus", "netz" to "akku")
        for ((w, h) in listOf(199f to 147f, 136f to 147f, 323f to 147f, 199f to 120f)) {
            val l = EnergyPicture.lage(w, h)
            // a pill as the stadium it is, with Akku's ring (and its stroke) around it
            class Guard(val b: android.graphics.RectF, val m: Float) {
                fun contains(x: Float, y: Float): Boolean {
                    val r = b.height() / 2
                    val dx = kotlin.math.max(kotlin.math.abs(x - b.centerX()) - (b.width() / 2 - r), 0f)
                    return kotlin.math.hypot(dx, y - b.centerY()) <= r + m
                }
            }
            fun guard(key: String) = Guard(l.box(key), if (key == "akku") 7f else 1f)
            val akku = l.box("akku").apply { inset(-8f, -8f) }
            assertTrue("${w}x$h: ring inside", akku.left >= 0 && akku.right <= w && akku.top >= 0 && akku.bottom <= h)
            for ((von, nach) in ways) {
                val path = EnergyPicture.way(von, nach, l)
                val m = android.graphics.PathMeasure(path, false)
                assertTrue("${w}x$h $von→$nach: long enough for its arrow", m.length >= 14f)
                // every point of the way, and the arrow's corners, off every pill
                val pt = FloatArray(2)
                var d = 0f
                while (d <= m.length) {
                    m.getPosTan(d, pt, null)
                    for (k in EnergyFluss.KEYS) assertTrue("${w}x$h $von→$nach at $d (${pt.toList()}) on $k", !guard(k).contains(pt[0], pt[1]))
                    d += 1f
                }
                val (pos, tan) = EnergyPicture.arrowAt(path)!!
                val (long, half) = EnergyPicture.arrowSize(6f)
                val corners = listOf(
                    pos[0] + tan[0] * long to pos[1] + tan[1] * long,
                    pos[0] - tan[0] * long - tan[1] * half to pos[1] - tan[1] * long + tan[0] * half,
                    pos[0] - tan[0] * long + tan[1] * half to pos[1] - tan[1] * long - tan[0] * half,
                )
                for ((x, y) in corners) for (k in EnergyFluss.KEYS) {
                    assertTrue("${w}x$h $von→$nach: arrow on $k", !guard(k).contains(x, y))
                }
            }
        }
    }
}
