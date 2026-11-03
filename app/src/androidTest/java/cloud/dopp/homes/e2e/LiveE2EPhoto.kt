package cloud.dopp.homes.e2e

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A photo for the share (e2e/live_e2e.sh step 7): an image in the phone's
 * MediaStore, its content URI reported as the status `uri`. The script hands
 * it to the share activity with `am start` (`ACTION_SEND` `image/png`), as a
 * camera app does — not from here: the instrumentation's process ends with
 * this test, and an activity started in it would die with it.
 */
@RunWith(AndroidJUnit4::class)
class LiveE2EPhoto {

    @Test
    fun sharePhoto() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "homes-e2e.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("no MediaStore entry", uri)
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(61, 220, 132)) }
        ctx.contentResolver.openOutputStream(uri!!)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("uri", uri.toString()) })
    }
}
