package cloud.dopp.homes

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import cloud.dopp.homes.widget.ActionDialog
import cloud.dopp.homes.widget.ActionItem
import cloud.dopp.homes.widget.ActionSheet
import cloud.dopp.homes.widget.ActionSheets
import cloud.dopp.homes.widget.ActionTone
import cloud.dopp.homes.widget.ConfirmVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Every confirmation of the app looks alike (owner, 2026-11-03: „sehr
 * unterschiedlich optisch aufgebaut“): the pairing, the battery question,
 * Share → Homes, a garage's confirm, a lock's chooser — one card, the
 * question, the positive action filled, the neutral footer last, no capitals.
 * Drawn into `build/widget-preview/` (and `$WIDGET_PREVIEW_DIR`) to be looked at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DialogPreviewTest {

    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun shown(name: String): View {
        ShadowLooper.idleMainLooper()
        val v = ShadowDialog.getLatestDialog().window!!.decorView
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF070709.toInt())
        v.draw(Canvas(bmp))
        listOfNotNull(File(RepoFiles.root, "app/build/widget-preview"), System.getenv("WIDGET_PREVIEW_DIR")?.let(::File)).forEach { dir ->
            dir.mkdirs()
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return v
    }

    /** One shape: a title, the question, the actions, then the neutral footer; nothing in capitals. */
    private fun sameShape(v: View, footer: String) {
        assertEquals(footer, v.findViewById<Button>(R.id.ad_cancel).text.toString())
        fun texts(x: View): List<TextView> = if (x is ViewGroup) (0 until x.childCount).flatMap { texts(x.getChildAt(it)) } else listOfNotNull(x as? TextView)
        texts(v).forEach { assertFalse("capitals on „${it.text}“", it.isAllCaps) }
    }

    @Test
    fun theDialogsLookAlike() {
        val c = activity
        ActionDialog.show(c, c.getString(R.string.pairing_confirm_title), c.getString(R.string.pairing_confirm_link, "homes.example"),
            ActionSheet(listOf(ActionItem(R.string.pairing_confirm_yes, ActionTone.PRIMARY))), {}, {}, footer = c.getString(R.string.pairing_confirm_no), paintScrim = false)
        sameShape(shown("dialog-nachher-koppeln"), "Abbrechen")
        ActionDialog.show(c, c.getString(R.string.live_battery_title), c.getString(R.string.live_battery_why),
            ActionSheet(listOf(ActionItem(R.string.live_battery_yes, ActionTone.PRIMARY))), {}, {}, footer = c.getString(R.string.live_battery_no), paintScrim = false)
        sameShape(shown("dialog-nachher-akku"), "Später")
        ActionDialog.show(c, c.getString(R.string.widget_confirm_title), c.getString(R.string.widget_confirm_msg, "Garage", "öffnen"),
            ActionSheets.confirm(ConfirmVerb.of("cover.open_cover")), {}, {})
        sameShape(shown("dialog-nachher-garage"), "Abbrechen")
        ActionDialog.show(c, c.getString(R.string.widget_lock_title, "Haustür"), null, ActionSheets.lock(1), {}, {})
        sameShape(shown("dialog-nachher-schloss"), "Abbrechen")
        ActionDialog.show(c, c.getString(R.string.share_kept), "Rezept gemerkt: Kürbissuppe, 2 Zutaten, 1 Schritt.",
            ActionSheet(listOf(ActionItem(R.string.share_open, ActionTone.PRIMARY))), {}, {}, footer = c.getString(R.string.share_done))
        sameShape(shown("dialog-nachher-teilen"), "Fertig")
    }
}
