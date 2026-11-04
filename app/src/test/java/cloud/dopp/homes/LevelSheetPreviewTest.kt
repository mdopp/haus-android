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
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.ui.StartDevices
import com.google.android.material.bottomsheet.BottomSheetDialog
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

/**
 * The favorites' levels and their sheet drawn (owner, 2026-11-03): the rows
 * with their level to tap, a lamp's sheet, an off lamp's („aus“), a cover's sheet. Into
 * `build/widget-preview/regler-<name>.png` (and `$WIDGET_PREVIEW_DIR`), to be
 * looked at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LevelSheetPreviewTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun drawTheRows() = draw("zeilen", null)

    @Test
    fun drawTheLampsSheet() = draw("blatt-licht", 0)

    @Test
    fun drawTheCoversSheet() = draw("blatt-rollo", 2)

    @Test
    fun drawTheOffLampsSheet() = draw("blatt-aus", 1)

    private fun draw(name: String, open: Int?) {
        ServerStore.setBaseUrl(ctx(), "https://homes.example")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "homes_device_TESTTOKEN123")
        Assistent.save(ctx(), "Homes")
        StartDevices.save(ctx(), FAVORITES)
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().visible().get()
        ShadowLooper.idleMainLooper()
        val d = a.resources.displayMetrics.density
        val w = (411 * d).toInt()
        val h = (891 * d).toInt()
        val content = a.findViewById<ViewGroup>(R.id.home_root).getChildAt(0)
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(ctx().getColor(R.color.homes_bg))
        val canvas = Canvas(bmp)
        canvas.save()
        canvas.translate(0f, 24 * d)
        content.draw(canvas)
        canvas.restore()
        if (open != null) {
            val group = a.findViewById<ViewGroup>(R.id.devices_group)
            group.getChildAt(open).findViewById<TextView>(R.id.row_level).performClick()
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
            File(dir, "regler-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    companion object {
        /** A lamp on, a lamp off, a shutter open, a shutter closed, as `/napi/portal/favorites` sends them. */
        val FAVORITES = """{"ok":true,"favorites":[
            {"entity_id":"light.kueche","name":"Küchenlicht","domain":"light","state":"on","brightness":153,"supported_color_modes":["brightness"]},
            {"entity_id":"light.flur","name":"Flurlicht","domain":"light","state":"off","supported_color_modes":["brightness"]},
            {"entity_id":"cover.wohnzimmer","name":"Rollo","domain":"cover","device_class":"shutter","state":"open","current_position":60,"supported_features":15},
            {"entity_id":"cover.bad","name":"Rollo Bad","domain":"cover","device_class":"shutter","state":"closed","current_position":0,"supported_features":15}]}"""
    }
}
