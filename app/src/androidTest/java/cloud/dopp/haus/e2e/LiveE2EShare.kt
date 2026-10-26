package cloud.dopp.haus.e2e

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens Android's share sheet for a piece of text, the way Chrome's „Mehr“
 * does (e2e/live_e2e.sh step 6). The script then reads the screen for the
 * persona's Direct Share target (#188).
 */
@RunWith(AndroidJUnit4::class)
class LiveE2EShare {

    @Test
    fun openShareSheet() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.org/e2e")
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
