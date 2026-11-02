package cloud.dopp.haus

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.haus.data.BoxHome
import cloud.dopp.haus.widget.WidgetCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip + stale-then-fresh checks for the last-good render cache (#46): a
 * failed fetch re-renders from here, so encode→decode must preserve the fields
 * each Box widget shows, and a never-loaded widget must read back null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetCacheTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun homeRoundTrip() {
        WidgetCache.putHome(ctx, 3, BoxHome(5, 1, 0, 2, 4))
        val back = WidgetCache.getHome(ctx, 3)!!
        assertEquals(5, back.servicesUp)
        assertEquals(1, back.servicesFailed)
        assertEquals(2, back.pendingApprovals)
        assertEquals(4, back.pendingUpdates)
    }

    @Test fun serviceCountsRoundTrip() {
        WidgetCache.putServiceCounts(ctx, 9, Triple(6, 2, 1))
        assertEquals(Triple(6, 2, 1), WidgetCache.getServiceCounts(ctx, 9))
    }

    @Test fun neverLoadedReadsNull() {
        assertNull(WidgetCache.getHome(ctx, 999))
        assertNull(WidgetCache.getServiceCounts(ctx, 999))
    }

    @Test fun clearDropsEveryPayloadForId() {
        WidgetCache.putHome(ctx, 11, BoxHome(1, 0, 0, 0, 0))
        WidgetCache.putServiceCounts(ctx, 11, Triple(1, 0, 0))
        WidgetCache.clear(ctx, 11)
        assertNull(WidgetCache.getHome(ctx, 11))
        assertNull(WidgetCache.getServiceCounts(ctx, 11))
    }
}
