package cloud.dopp.homes

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.ServerStore
import cloud.dopp.homes.ui.HomeActivity
import cloud.dopp.homes.widget.HomesPages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where a widget's body tap lands (#167, #176): Homes' own pages, in the
 * browser — `/start` for the house, `/box` for the Box. The app has no web
 * shell; unpaired, the tap opens the app, which asks for Homes' address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevicesRouteTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun unpair() = ServerStore.clear(ctx)

    @Test
    fun `the pages are Homes' start and box`() {
        assertEquals("/start", HomesPages.START)
        assertEquals("/box", HomesPages.BOX)
    }

    @Test
    fun `it joins onto the paired base`() {
        assertEquals("https://homes.example/start", HomesPages.url("https://homes.example/", HomesPages.START))
    }

    @Test
    fun `paired, a tap opens the page in the browser`() {
        ServerStore.setBaseUrl(ctx, "https://homes.example")
        val i = HomesPages.intent(ctx, HomesPages.BOX)
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("https://homes.example/box", i.data.toString())
    }

    @Test
    fun `unpaired, a tap opens the app`() {
        val i = HomesPages.intent(ctx, HomesPages.START)
        assertEquals(HomeActivity::class.java.name, i.component?.className)
    }
}
