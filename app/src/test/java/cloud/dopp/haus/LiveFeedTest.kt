package cloud.dopp.haus

import cloud.dopp.haus.realtime.LiveStream
import cloud.dopp.haus.widget.ActiveDevicesWidgetProvider
import cloud.dopp.haus.widget.BoxOverviewWidgetProvider
import cloud.dopp.haus.widget.BoxServicesWidgetProvider
import cloud.dopp.haus.widget.DeviceWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Which widgets open the live stream (#177 review, finding 9): only those it
 * keeps current — the device widgets take its frames, the active list
 * refetches on them, the Box widgets re-read on the catch-up. A static tile,
 * an energy or a tool widget alone opens nothing. Removing a fed widget looks
 * again, so the last one gone closes the stream. The gate's rule is LiveGateTest's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveFeedTest {

    @Test
    fun onlyTheWidgetsTheStreamFeedsOpenIt() {
        assertEquals(
            setOf(
                DeviceWidgetProvider::class.java, ActiveDevicesWidgetProvider::class.java,
                BoxOverviewWidgetProvider::class.java, BoxServicesWidgetProvider::class.java,
            ),
            LiveStream.FED.toSet(),
        )
    }

    @Test
    fun removingAFedWidgetLooksAgain() {
        val missing = LiveStream.FED.filter { cls ->
            val src = File(RepoFiles.main, "java/" + cls.name.replace('.', '/') + ".kt").readText()
            val deleted = src.substringAfter("override fun onDeleted(", "")
            deleted.isEmpty() || !deleted.substringBefore("\n    }").contains("LiveStream.refresh(")
        }
        assertTrue("onDeleted does not refresh the stream: $missing", missing.isEmpty())
    }
}
