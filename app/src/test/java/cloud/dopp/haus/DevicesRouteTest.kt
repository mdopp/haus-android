package cloud.dopp.haus

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.ServerStore
import cloud.dopp.haus.ui.HomeActivity
import cloud.dopp.haus.widget.HausPages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where a widget's body tap lands (#167, #176): Haus's own pages, in the
 * browser — `/start` for the house, `/box` for the Box. The app has no web
 * shell; unpaired, the tap opens the app, which asks for Haus's address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevicesRouteTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun unpair() = ServerStore.clear(ctx)

    @Test
    fun `the pages are Haus's start and box`() {
        assertEquals("/start", HausPages.START)
        assertEquals("/box", HausPages.BOX)
    }

    @Test
    fun `it joins onto the paired base`() {
        assertEquals("https://haus.example/start", HausPages.url("https://haus.example/", HausPages.START))
    }

    @Test
    fun `paired, a tap opens the page in the browser`() {
        ServerStore.setBaseUrl(ctx, "https://haus.example")
        val i = HausPages.intent(ctx, HausPages.BOX)
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("https://haus.example/box", i.data.toString())
    }

    @Test
    fun `unpaired, a tap opens the app`() {
        val i = HausPages.intent(ctx, HausPages.START)
        assertEquals(HomeActivity::class.java.name, i.component?.className)
    }
}
