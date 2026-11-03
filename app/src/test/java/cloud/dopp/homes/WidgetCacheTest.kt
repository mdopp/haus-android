package cloud.dopp.homes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.homes.data.Card
import cloud.dopp.homes.widget.WidgetCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The device widget's last-good card (#46): a failed read draws it, so
 * encode→decode keeps what the tile and the lock chooser read; a widget
 * that never loaded reads back null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetCacheTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun aCardRoundTrips() {
        val c = Card("lock.haustuer", "Haustür", "lock", null, "locked", null, supportedFeatures = 1, page = "/raeume/lock.haustuer")
        WidgetCache.putCard(ctx, 3, c)
        assertEquals(c, WidgetCache.getCard(ctx, 3))
        val lamp = Card("light.flur", "Flur", "light", null, "on", null, brightness = 128)
        WidgetCache.putCard(ctx, 4, lamp)
        assertEquals(lamp, WidgetCache.getCard(ctx, 4))
    }

    @Test fun neverLoadedReadsNullAndClearDrops() {
        assertNull(WidgetCache.getCard(ctx, 999))
        WidgetCache.putCard(ctx, 11, Card("switch.x", "X", "switch", null, "on", null))
        WidgetCache.clear(ctx, 11)
        assertNull(WidgetCache.getCard(ctx, 11))
    }
}
