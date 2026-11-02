package cloud.dopp.haus.e2e

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dopp.haus.ui.ShareActivity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A photo shared to Haus the way a camera app shares it (e2e/live_e2e.sh
 * step 7): an image in the phone's MediaStore, handed to the share activity
 * as `ACTION_SEND` `image/png` with a content URI. The script then reads the
 * fake Haus's log for the upload and the screen for „Abgelegt“.
 */
@RunWith(AndroidJUnit4::class)
class LiveE2EPhoto {

    @Test
    fun sharePhoto() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "haus-e2e.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("no MediaStore entry", uri)
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(61, 220, 132)) }
        ctx.contentResolver.openOutputStream(uri!!)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ctx.startActivity(
            Intent(ctx, ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
