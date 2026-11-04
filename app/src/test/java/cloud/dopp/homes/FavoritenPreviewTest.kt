package cloud.dopp.homes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.Assistent
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.data.TokenStore
import cloud.dopp.homes.verbindung.Verbindung
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.ui.StartDevices
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.net.ConnectException
import java.util.Calendar

/**
 * „Deine Geräte“ as tiles with the owner's own favourites (2026-11-04, A2):
 * drawn as a phone screen that starts at the section's title, on a 411 dp
 * and a 360 dp phone, the box not reached, and a blind's sheet. Asserted on
 * each: no name is cut and every tile's buttons stay inside it, 40 dp wide
 * and 48 dp high. Into `build/widget-preview/favoriten-<name>.png` (and
 * `$WIDGET_PREVIEW_DIR`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FavoritenPreviewTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun drawTheTiles() = draw("kacheln", null)

    @Test
    fun drawABlindsSheet() = draw("blatt", "cover.esszimmer")

    @Test
    fun drawTheTilesOffline() = draw("offline", null, offline = true)

    /** A narrow phone: a cover's ▲ ■ ▼ and its name still fit, nothing cut. */
    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun drawTheTilesNarrow() = draw("schmal", null)

    /** A small tablet: still a phone's rows, two cells of a cover's line do not fit (owner, 2026-11-04: larger screens). */
    @Test
    @Config(qualifiers = "w600dp-h960dp-xhdpi")
    fun drawTheTilesAt600() = draw("600", null)

    /** A foldable's inner screen: a grid of two equal cells, each tile one line. */
    @Test
    @Config(qualifiers = "w900dp-h1000dp-xhdpi")
    fun drawTheTilesAt900() = draw("900", null)

    /** A tablet across: three equal cells, the owner's six favourites in two rows, in their order. */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun drawTheTilesAt1280() = draw("1280", null)

    private fun draw(name: String, open: String?, offline: Boolean = false) {
        ServerStore.setBaseUrl(ctx(), "https://homes.example")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
        Assistent.save(ctx(), "Homes")
        StartDevices.save(ctx(), FAVORITES)
        if (offline) {
            Verbindung.ergebnis(ctx(), ConnectException("Connection refused"), null)
            ctx().getSharedPreferences("homes_verbindung", Context.MODE_PRIVATE).edit().putLong("stand", at0814()).commit()
        }
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().visible().get()
        // the screen's own read of homes.example fails here and would grey everything: let it end,
        // then say what the drawing shows — Homes answering, or (offline) not reached since 08:14
        Thread.sleep(SETTLE_MS)
        ShadowLooper.idleMainLooper()
        if (!offline) Verbindung.ergebnis(ctx(), null, 200) else Verbindung.ergebnis(ctx(), ConnectException("Connection refused"), null)
        if (offline) ctx().getSharedPreferences("homes_verbindung", Context.MODE_PRIVATE).edit().putLong("stand", at0814()).commit()
        HomeActivity::class.java.getDeclaredMethod("renderDevices").apply { isAccessible = true }.invoke(a)
        ShadowLooper.idleMainLooper()
        val d = a.resources.displayMetrics.density
        val w = (a.resources.configuration.screenWidthDp * d).toInt()
        val h = (WINDOW_DP * d).toInt()
        val content = a.findViewById<ViewGroup>(R.id.home_root).getChildAt(0)
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val group = a.findViewById<ViewGroup>(R.id.devices_group)
        val section = group.parent as ViewGroup
        // offline: from the „Box nicht erreichbar … Stand 08:14“ line above the title
        val banner = a.findViewById<View>(R.id.verbindung)
        val title = if (offline) banner else section.getChildAt(section.indexOfChild(group) - 2)
        if (offline) assertEquals(View.VISIBLE, banner.visibility)
        nothingCut(group, d)
        val r = android.graphics.Rect(0, 0, title.width, title.height)
        (content as ViewGroup).offsetDescendantRectToMyCoords(title, r)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(ctx().getColor(R.color.homes_bg))
        val canvas = Canvas(bmp)
        canvas.save()
        canvas.translate(0f, -(r.top - 20 * d))
        content.draw(canvas)
        canvas.restore()
        if (open != null) {
            group.findViewWithTag<View>(open).findViewById<TextView>(R.id.row_level).performClick()
            ShadowLooper.idleMainLooper()
            val sheet = (ShadowDialog.getLatestDialog() as BottomSheetDialog)
            assertTrue(sheet.isShowing)
            val panel = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)!!
            panel.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            canvas.drawColor(Color.argb((0.55f * 255).toInt(), 0, 0, 0))
            canvas.save()
            canvas.translate(0f, (h - panel.measuredHeight).toFloat())
            panel.draw(canvas)
            canvas.restore()
        }
        listOfNotNull(File(RepoFiles.root, "app/build/widget-preview"), System.getenv("WIDGET_PREVIEW_DIR")?.let(::File)).forEach { dir ->
            dir.mkdirs()
            File(dir, "favoriten-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Every favourite's tile: its name whole, its buttons inside it, each 40 dp wide and 48 dp high. */
    private fun nothingCut(group: ViewGroup, d: Float) {
        val tiles = Kacheln.of(group.context as android.app.Activity)
        assertEquals(6, tiles.size)
        tiles.forEach { tile ->
            val name = tile.findViewById<TextView>(R.id.row_name)
            val l = name.layout
            assertTrue("${name.text} is cut", l != null && (0 until l.lineCount).none { l.getEllipsisCount(it) > 0 })
            assertTrue("${name.text} fits its line", name.paint.measureText(name.text.toString()) <= name.width)
            Kacheln.buttons(tile).forEach { b ->
                val r = android.graphics.Rect(0, 0, b.width, b.height)
                (tile as ViewGroup).offsetDescendantRectToMyCoords(b, r)
                assertTrue("${b.contentDescription} inside its tile", r.left >= 0 && r.right <= tile.width)
                assertTrue("${b.contentDescription} 40 × 48 dp", b.width >= 40 * d - 1 && b.height >= 48 * d - 1)
            }
        }
    }

    private fun at0814() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 8)
        set(Calendar.MINUTE, 14)
    }.timeInMillis

    companion object {
        private const val SETTLE_MS = 1500L

        /** The phone's screen below the app's head, as in the owner's screenshot. */
        private const val WINDOW_DP = 760

        /** The owner's favourites (screenshot, 2026-11-04), plus a lamp that is on and a lock. */
        private const val FAVORITES = """{"ok":true,"favorites":[
            {"entity_id":"light.buero","name":"Bürolicht","domain":"light","state":"off","supported_color_modes":["brightness"],"page":"/raeume/light.buero"},
            {"entity_id":"cover.esszimmer","name":"Esszimmerjalousien","domain":"cover","device_class":"blind","state":"open","current_position":32,"supported_features":15,"page":"/raeume/cover.esszimmer"},
            {"entity_id":"cover.wohnzimmer","name":"Wohnzimmerjalousien","domain":"cover","device_class":"blind","state":"open","current_position":15,"supported_features":15,"page":"/raeume/cover.wohnzimmer"},
            {"entity_id":"cover.garage","name":"Garagentor Garage","domain":"cover","device_class":"garage","state":"closed","supported_features":3,"page":"/raeume/cover.garage"},
            {"entity_id":"light.kueche","name":"Küchenlicht","domain":"light","state":"on","brightness":179,"supported_color_modes":["brightness"],"page":"/raeume/light.kueche"},
            {"entity_id":"lock.haustuer","name":"Haustür","domain":"lock","state":"locked","supported_features":1,"page":"/raeume/lock.haustuer"}]}"""
    }
}
