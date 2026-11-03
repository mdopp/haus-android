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
import cloud.dopp.haus.data.Card
import cloud.dopp.haus.widget.DeviceWidgetProvider
import cloud.dopp.haus.widget.WidgetStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The device widget drawn as the owner has it on his home screen, one cell
 * (owner, 2026-11-03: „Das Garagentor Widget ist viel schlechter als früher …
 * Viel Text statt Bildern.“): a garage shut and open, a lock, a lamp on and
 * off, a heating. One board of tiles on a wallpaper, in
 * `build/widget-preview/` (and `$WIDGET_PREVIEW_DIR`) to be looked at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DeviceWidgetPreviewTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private class Tile(val entityId: String, val name: String, val deviceClass: String?, val card: Card)

    private val tiles = listOf(
        Tile("cover.garagentor", "Garagentor Garage", "garage", Card("cover.garagentor", "Garagentor Garage", "cover", "garage", "closed", null)),
        Tile("cover.garagentor", "Garagentor Garage", "garage", Card("cover.garagentor", "Garagentor Garage", "cover", "garage", "open", null, position = 100)),
        Tile("lock.haustuer", "Haustür", null, Card("lock.haustuer", "Haustür", "lock", null, "locked", null)),
        Tile("light.kueche", "Küche Licht", null, Card("light.kueche", "Küche Licht", "light", null, "on", null, brightness = 153)),
        Tile("light.flur", "Flur", null, Card("light.flur", "Flur", "light", null, "off", null)),
        Tile("cover.wohnzimmer", "Rollladen Wohnzimmer", "shutter", Card("cover.wohnzimmer", "Rollladen Wohnzimmer", "cover", "shutter", "open", null, position = 40)),
        Tile("climate.bad", "Bad", null, Card("climate.bad", "Bad", "climate", null, "heat", null, temperature = 21.5, setpoint = 22.0)),
    )

    /** One tile at [wDp]×[hDp], laid out and drawn. */
    private fun tile(t: Tile, wDp: Int, hDp: Int): Bitmap {
        val mgr = AppWidgetManager.getInstance(ctx)
        val id = shadowOf(mgr).createWidget(DeviceWidgetProvider::class.java, R.layout.widget_device)
        mgr.updateAppWidgetOptions(
            id,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
            },
        )
        WidgetStore.bind(ctx, id, t.entityId, t.name, t.card.domain, t.deviceClass)
        val host = FrameLayout(ctx)
        val v = DeviceWidgetProvider.build(ctx, id, t.card).apply(ctx, host)
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt()
        val h = (hDp * d).toInt()
        host.addView(v, ViewGroup.LayoutParams(w, h))
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        host.draw(Canvas(bmp))
        return bmp
    }

    /** The tiles side by side on a launcher's wallpaper, written as [name]. */
    private fun board(wDp: Int, hDp: Int, name: String) {
        val d = ctx.resources.displayMetrics.density
        val gap = (14 * d).toInt()
        val bitmaps = tiles.map { tile(it, wDp, hDp) }
        val out = Bitmap.createBitmap(gap + bitmaps.sumOf { it.width + gap }, bitmaps.first().height + 2 * gap, Bitmap.Config.ARGB_8888)
        out.eraseColor(0xFF3B2A5C.toInt()) // the owner's purple wallpaper
        val c = Canvas(out)
        var x = gap
        bitmaps.forEach { c.drawBitmap(it, x.toFloat(), gap.toFloat(), null); x += it.width + gap }
        listOfNotNull(File(RepoFiles.root, "app/build/widget-preview"), System.getenv("WIDGET_PREVIEW_DIR")?.let(::File)).forEach { dir ->
            dir.mkdirs()
            File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun oneCell() = board(64, 72, "geraet-1x1")

    @Test
    fun twoCells() = board(140, 72, "geraet-2x1")
}
