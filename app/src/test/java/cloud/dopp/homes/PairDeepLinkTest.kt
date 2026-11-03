package cloud.dopp.homes

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.ui.HomeActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reproduces the pairing-return path on the JVM (Robolectric — no emulator/VM):
 * the server redirects the Custom Tab to `cloud.dopp.homes://pair#token=…`, which
 * launches [HomeActivity]. This drives the full lifecycle with that
 * intent so a crash in onCreate/onNewIntent/deep-link handling surfaces here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingDeepLinkTest {

    private fun pairIntent(): Intent {
        val uri = Uri.parse("cloud.dopp.homes://pair#token=homes_device_TESTTOKEN123&id=abc")
        return Intent(Intent.ACTION_VIEW, uri)
    }

    @Test
    fun launchWithPairDeepLink_doesNotCrash() {
        Robolectric.buildActivity(HomeActivity::class.java, pairIntent()).setup()
    }

    @Test
    fun deliverPairDeepLinkViaOnNewIntent_doesNotCrash() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        controller.newIntent(pairIntent())
    }

    /** Faithful device state: server already configured (from Connect), then the
     *  pair deep link returns → render shows the "connected" section. */
    @Test
    fun pairReturnWhenServerConfigured_showsConnected_doesNotCrash() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ServerStore.setBaseUrl(ctx, "https://homes.example")
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        controller.newIntent(pairIntent())
    }
}
