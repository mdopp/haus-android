package cloud.dopp.haus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.Assistent
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.data.TokenStore
import cloud.dopp.haus.ui.HomeActivity
import cloud.dopp.haus.ui.StartDevices
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * The paired start screen drawn whole, with a favorite of each kind (owner,
 * 2026-11-03: the favorites' fine controls, the live line under the widgets,
 * „Gekoppelt mit …“ beside „Kopplung aufheben“). Drawn into
 * `build/widget-preview/start-<tag>.png` (and `$WIDGET_PREVIEW_DIR`), the tag
 * from `$START_PREVIEW_TAG`, to be looked at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class StartPreviewTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun drawTheStartScreen() {
        ServerStore.setBaseUrl(ctx(), "https://haus.example")
        TokenStore.backend = ctx().getSharedPreferences("test_tokens", Context.MODE_PRIVATE)
        TokenStore.save(ctx(), "haus_device_TESTTOKEN123")
        Assistent.save(ctx(), "Homes")
        StartDevices.save(ctx(), FAVORITES)
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        val content = (a.findViewById<ViewGroup>(R.id.home_root)).getChildAt(0)
        val w = (411 * a.resources.displayMetrics.density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        assertTrue(content.measuredHeight > 0)
        val bmp = Bitmap.createBitmap(content.measuredWidth, content.measuredHeight, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(ctx().getColor(R.color.terminal_ground))
        content.draw(Canvas(bmp))
        val tag = System.getenv("START_PREVIEW_TAG") ?: "nachher"
        listOfNotNull(File(RepoFiles.root, "app/build/widget-preview"), System.getenv("WIDGET_PREVIEW_DIR")?.let(::File)).forEach { dir ->
            dir.mkdirs()
            File(dir, "start-$tag.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    companion object {
        /** One favorite of each kind, as Haus's `/napi/portal/favorites` sends them. */
        val FAVORITES = """{"ok":true,"favorites":[
            {"entity_id":"light.kueche","name":"Küche","domain":"light","state":"on","brightness":153,"supported_color_modes":["brightness"],"page":"/raeume/light.kueche"},
            {"entity_id":"switch.kaffee","name":"Kaffeemaschine","domain":"switch","state":"off","page":"/raeume/switch.kaffee"},
            {"entity_id":"cover.wohnzimmer","name":"Rollladen Wohnzimmer","domain":"cover","device_class":"shutter","state":"open","current_position":40,"supported_features":15,"page":"/raeume/cover.wohnzimmer"},
            {"entity_id":"cover.garage","name":"Garage","domain":"cover","device_class":"garage","state":"closed","supported_features":3,"page":"/raeume/cover.garage"},
            {"entity_id":"climate.bad","name":"Bad","domain":"climate","state":"heat","current_temperature":21.5,"temperature":22,"target_temp_step":0.5,"min_temp":7,"max_temp":30,"page":"/raeume/climate.bad"},
            {"entity_id":"media_player.kueche","name":"Radio Küche","domain":"media_player","state":"playing","supported_features":21517,"volume_level":0.3,"media_title":"Nachrichten","page":"/raeume/media_player.kueche"},
            {"entity_id":"lock.haustuer","name":"Haustür","domain":"lock","state":"locked","supported_features":1,"page":"/raeume/lock.haustuer"}]}"""
    }
}
